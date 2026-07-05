package top.yukonga.scripta.sandbox.spike

import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest

/**
 * SPIKE InputConnection: the bridge the Android IME talks to.
 *
 * We subclass [BaseInputConnection] (as the plan asks) but back it with our OWN [MiniEditorState]
 * instead of Base's internal `Editable`. Every text-manipulation method is overridden to hit the
 * model; the not-overridden bits (default editor-action handling, etc.) fall through to Base.
 *
 * This is the exact surface the full editor must implement over its line buffer, so getting Chinese
 * composing, batch edits, code-point deletes and the cursor getters right HERE is the point of the
 * spike. `fullEditor = false` keeps Base from creating/consuming its own editable for these ops.
 */
class MiniInputConnection(
    view: View,
    private val state: MiniEditorState,
) : BaseInputConnection(view, /* fullEditor = */ false) {

    override fun beginBatchEdit(): Boolean {
        state.beginBatch()
        return true
    }

    override fun endBatchEdit(): Boolean = state.endBatch()

    override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
        state.setComposingText(text.toString(), newCursorPosition)
        return true
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        state.setComposingRegion(start, end)
        return true
    }

    override fun finishComposingText(): Boolean {
        state.finishComposingText()
        return true
    }

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        state.commitText(text.toString(), newCursorPosition)
        return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        state.deleteSurroundingText(beforeLength, afterLength)
        return true
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        state.deleteSurroundingTextInCodePoints(beforeLength, afterLength)
        return true
    }

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = state.textBeforeCursor(n)

    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = state.textAfterCursor(n)

    override fun getSelectedText(flags: Int): CharSequence? = state.selectedText()

    override fun getCursorCapsMode(reqModes: Int): Int =
        TextUtils.getCapsMode(state.text, state.selStart, reqModes)

    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText {
        // Some IMEs prefer a full snapshot; provide one so they don't fall back to odd behaviour.
        return ExtractedText().apply {
            this.text = state.text
            this.startOffset = 0
            this.selectionStart = state.selStart
            this.selectionEnd = state.selEnd
        }
    }

    /**
     * Soft keyboards usually delete via [deleteSurroundingText], but some send a hardware-style
     * DEL/ENTER/unicode key through here. Handle those against the model; defer everything else.
     */
    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DEL -> {
                    state.backspace(); return true
                }
                KeyEvent.KEYCODE_FORWARD_DEL -> {
                    state.deleteForward(); return true
                }
                KeyEvent.KEYCODE_ENTER -> {
                    state.insert("\n"); return true
                }
                else -> {
                    val ch = event.unicodeChar
                    if (ch != 0) {
                        state.insert(String(Character.toChars(ch))); return true
                    }
                }
            }
        }
        return super.sendKeyEvent(event)
    }
}
