package top.yukonga.scripta.sandbox.spike

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * SPIKE model for the self-managed editor.
 *
 * This is the "own StringBuilder backend" the plan calls for: a single character buffer plus an
 * ordered selection and an optional composing region. ALL editing semantics (IME + hardware key +
 * touch) live here so the [MiniInputConnection] adapter and the UI stay thin, and so this logic
 * could be unit-tested without any Android/Compose UI later.
 *
 * Invariants:
 *  - `0 <= selStart <= selEnd <= text.length`  (ordered selection; cursor when start == end)
 *  - composing region is either both `-1` (none) or a valid `0 <= composingStart <= composingEnd`
 *  - offsets are Java `char` (UTF-16) indices, matching Android InputConnection contracts; edits that
 *    must not split a surrogate pair use the code-point helpers below.
 *
 * The three public fields are Compose snapshot state, so the Canvas re-renders on every edit.
 * [imeListener] is a side channel used to push `InputMethodManager.updateSelection` to the platform;
 * it is fired only when not inside an IME batch edit, so a multi-step IME transaction reports once.
 */
class MiniEditorState(initialText: String = "") {

    var text: String by mutableStateOf(initialText)
        private set

    var selStart: Int by mutableStateOf(initialText.length)
        private set

    var selEnd: Int by mutableStateOf(initialText.length)
        private set

    var composingStart: Int by mutableStateOf(-1)
        private set

    var composingEnd: Int by mutableStateOf(-1)
        private set

    val hasSelection: Boolean get() = selStart != selEnd
    val hasComposing: Boolean get() = composingStart in 0..composingEnd && composingStart != composingEnd

    /** Listener the IME modifier registers to mirror selection/composing to the platform IME. */
    interface ImeListener {
        fun onSelectionChanged(selStart: Int, selEnd: Int, composingStart: Int, composingEnd: Int)
    }

    var imeListener: ImeListener? = null

    /** Set by the IME modifier while a session is active; lets a tap re-show a dismissed keyboard. */
    var requestShowKeyboard: (() -> Unit)? = null

    private var batchDepth = 0

    // --- Batch edits (IME beginBatchEdit / endBatchEdit) --------------------------------------

    fun beginBatch() {
        batchDepth++
    }

    /** @return true while still nested inside a batch (matches InputConnection.endBatchEdit contract). */
    fun endBatch(): Boolean {
        if (batchDepth > 0) batchDepth--
        if (batchDepth == 0) fireImeSelection()
        return batchDepth > 0
    }

    private fun maybeNotify() {
        if (batchDepth == 0) fireImeSelection()
    }

    /** Force an IME selection sync now (used by touch-driven cursor moves outside any batch). */
    fun fireImeSelection() {
        imeListener?.onSelectionChanged(selStart, selEnd, composingStart, composingEnd)
    }

    // --- Selection ----------------------------------------------------------------------------

    /** Set an ordered selection (endpoints may arrive reversed from a backwards drag). */
    fun setSelection(a: Int, b: Int, keepComposing: Boolean = false) {
        val lo = a.coerceIn(0, text.length)
        val hi = b.coerceIn(0, text.length)
        selStart = minOf(lo, hi)
        selEnd = maxOf(lo, hi)
        if (!keepComposing) clearComposingRegionOnly()
        maybeNotify()
    }

    fun setCursor(offset: Int) = setSelection(offset, offset)

    fun selectAll() = setSelection(0, text.length)

    private fun clearComposingRegionOnly() {
        composingStart = -1
        composingEnd = -1
    }

    // --- Composing (IME) ----------------------------------------------------------------------

    /**
     * Replace the current composing region (or the selection, if none) with [newText] and mark it as
     * the new composing region. Mirrors InputConnection.setComposingText semantics, including the
     * `newCursorPosition` convention (1 == caret immediately after the inserted text).
     */
    fun setComposingText(newText: String, newCursorPosition: Int) {
        val replaceStart: Int
        val replaceEnd: Int
        if (hasComposing) {
            replaceStart = composingStart
            replaceEnd = composingEnd
        } else {
            replaceStart = selStart
            replaceEnd = selEnd
        }
        text = text.substring(0, replaceStart) + newText + text.substring(replaceEnd)
        composingStart = replaceStart
        composingEnd = replaceStart + newText.length
        val caret = cursorFromNewPosition(newCursorPosition, replaceStart, composingEnd)
        selStart = caret
        selEnd = caret
        if (newText.isEmpty()) clearComposingRegionOnly()
        maybeNotify()
    }

    /** Move/replace the composing region markers without changing text (InputConnection.setComposingRegion). */
    fun setComposingRegion(start: Int, end: Int) {
        val lo = minOf(start, end).coerceIn(0, text.length)
        val hi = maxOf(start, end).coerceIn(0, text.length)
        if (lo == hi) {
            clearComposingRegionOnly()
        } else {
            composingStart = lo
            composingEnd = hi
        }
        maybeNotify()
    }

    /** Keep the composed text, drop the composing markers (InputConnection.finishComposingText). */
    fun finishComposingText() {
        if (hasComposing || composingStart != -1) {
            clearComposingRegionOnly()
            maybeNotify()
        }
    }

    // --- Commit / insert ----------------------------------------------------------------------

    /**
     * Commit [newText]: replace the composing region (or selection) and end composing.
     * Mirrors InputConnection.commitText.
     */
    fun commitText(newText: String, newCursorPosition: Int) {
        val replaceStart: Int
        val replaceEnd: Int
        if (hasComposing) {
            replaceStart = composingStart
            replaceEnd = composingEnd
        } else {
            replaceStart = selStart
            replaceEnd = selEnd
        }
        text = text.substring(0, replaceStart) + newText + text.substring(replaceEnd)
        clearComposingRegionOnly()
        val insertedEnd = replaceStart + newText.length
        val caret = cursorFromNewPosition(newCursorPosition, replaceStart, insertedEnd)
        selStart = caret
        selEnd = caret
        maybeNotify()
    }

    /** Plain insertion at the caret (hardware typing / paste), replacing any selection. */
    fun insert(str: String) = commitText(str, 1)

    private fun cursorFromNewPosition(newCursorPosition: Int, insertedStart: Int, insertedEnd: Int): Int {
        val raw = if (newCursorPosition > 0) {
            insertedEnd + (newCursorPosition - 1)
        } else {
            insertedStart + newCursorPosition
        }
        return raw.coerceIn(0, text.length)
    }

    // --- Deletion -----------------------------------------------------------------------------

    /** Delete [before] chars before the selection and [after] chars after it (char units). */
    fun deleteSurroundingText(before: Int, after: Int) {
        val b = before.coerceAtLeast(0)
        val a = after.coerceAtLeast(0)
        val delStart = (selStart - b).coerceAtLeast(0)
        val delEndTail = (selEnd + a).coerceAtMost(text.length)
        // Remove the trailing part first so leading indices stay valid.
        var t = text
        t = t.substring(0, selEnd) + t.substring(delEndTail)
        t = t.substring(0, delStart) + t.substring(selStart)
        text = t
        val removedBefore = selStart - delStart
        selStart -= removedBefore
        selEnd = selStart
        adjustComposingAfterEdit()
        maybeNotify()
    }

    /** Code-point-aware variant (InputConnection.deleteSurroundingTextInCodePoints, API 24+). */
    fun deleteSurroundingTextInCodePoints(before: Int, after: Int) {
        val delStart = offsetByCodePointsSafe(selStart, -before)
        val delEndTail = offsetByCodePointsSafe(selEnd, after)
        var t = text
        t = t.substring(0, selEnd) + t.substring(delEndTail)
        t = t.substring(0, delStart) + t.substring(selStart)
        text = t
        val removedBefore = selStart - delStart
        selStart -= removedBefore
        selEnd = selStart
        adjustComposingAfterEdit()
        maybeNotify()
    }

    /** Backspace: delete the selection if any, else one code point before the caret. */
    fun backspace() {
        if (hasSelection) {
            replaceRange(selStart, selEnd, "")
            return
        }
        if (selStart == 0) return
        val cp = text.codePointBefore(selStart)
        val start = selStart - Character.charCount(cp)
        replaceRange(start, selStart, "")
    }

    /** Forward-delete: delete the selection if any, else one code point after the caret. */
    fun deleteForward() {
        if (hasSelection) {
            replaceRange(selStart, selEnd, "")
            return
        }
        if (selEnd >= text.length) return
        val cp = text.codePointAt(selEnd)
        val end = selEnd + Character.charCount(cp)
        replaceRange(selStart, end, "")
    }

    private fun replaceRange(start: Int, end: Int, str: String) {
        text = text.substring(0, start) + str + text.substring(end)
        val caret = start + str.length
        selStart = caret
        selEnd = caret
        clearComposingRegionOnly()
        maybeNotify()
    }

    private fun adjustComposingAfterEdit() {
        // Simplest correct behaviour for the spike: a surrounding-text delete cancels composing.
        clearComposingRegionOnly()
    }

    // --- Queries (InputConnection getters) ----------------------------------------------------

    fun textBeforeCursor(n: Int): CharSequence {
        val len = n.coerceAtLeast(0).coerceAtMost(selStart)
        return text.substring(selStart - len, selStart)
    }

    fun textAfterCursor(n: Int): CharSequence {
        val avail = text.length - selEnd
        val len = n.coerceAtLeast(0).coerceAtMost(avail)
        return text.substring(selEnd, selEnd + len)
    }

    fun selectedText(): CharSequence? = if (hasSelection) text.substring(selStart, selEnd) else null

    // --- Caret movement (hardware keys) -------------------------------------------------------

    fun moveCaretHorizontally(dir: Int, extend: Boolean) {
        // Arrow without shift over a selection collapses to the edge in the travel direction.
        if (!extend && hasSelection) {
            setCursor(if (dir < 0) selStart else selEnd)
            return
        }
        // The active end is always selEnd for this spike (we don't track backwards selections).
        val target = offsetByCodePointsSafe(selEnd, dir)
        if (extend) setSelection(selStart, target) else setCursor(target)
    }

    private fun offsetByCodePointsSafe(index: Int, cpCount: Int): Int {
        val i = index.coerceIn(0, text.length)
        return try {
            text.offsetByCodePoints(i, cpCount).coerceIn(0, text.length)
        } catch (_: IndexOutOfBoundsException) {
            (if (cpCount < 0) 0 else text.length)
        }
    }
}
