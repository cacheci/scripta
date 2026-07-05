package top.yukonga.scripta.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import top.yukonga.scripta.editor.text.LineOffsetIndex
import top.yukonga.scripta.editor.text.TextBuffer
import top.yukonga.scripta.editor.text.TextPosition
import top.yukonga.scripta.editor.text.TextRange

/**
 * 编辑引擎：持有 TextBuffer + 归一化选择 + 可选 composing 区间，承载全部编辑/选择/IME 语义
 * （由 spike 的 MiniEditorState 推广到 (行,列) 坐标）。offset 只在 IME 边界经 LineOffsetIndex 换算。
 *
 * selection 恒为归一化（start ≤ end）；cursor 即 start == end。composing 为 null 表示无输入法预编辑。
 */
class EditorEngine(initialText: String = "") {

    val buffer = TextBuffer()
    private val index = LineOffsetIndex(buffer)

    var selection: TextRange by mutableStateOf(TextRange.cursor(TextPosition(0, 0)))
        private set

    var composing: TextRange? by mutableStateOf(null)
        private set

    val selStart: TextPosition get() = selection.start
    val selEnd: TextPosition get() = selection.end

    interface ImeListener {
        fun onSelectionChanged(selStart: Int, selEnd: Int, composingStart: Int, composingEnd: Int)
    }

    var imeListener: ImeListener? = null
    var requestShowKeyboard: (() -> Unit)? = null

    private var batchDepth = 0

    init {
        if (initialText.isNotEmpty()) setText(initialText)
    }

    // --- 批处理 / IME 通知 -------------------------------------------------------------------

    fun beginBatch() { batchDepth++ }

    fun endBatch(): Boolean {
        if (batchDepth > 0) batchDepth--
        if (batchDepth == 0) fireIme()
        return batchDepth > 0
    }

    private fun maybeNotify() { if (batchDepth == 0) fireIme() }

    fun fireIme() {
        val l = imeListener ?: return
        val s = selectionOffsets()
        val c = composingOffsets()
        l.onSelectionChanged(s.first, s.second, c.first, c.second)
    }

    fun selectionOffsets(): Pair<Int, Int> = index.offsetOf(selStart) to index.offsetOf(selEnd)

    fun composingOffsets(): Pair<Int, Int> {
        val c = composing ?: return -1 to -1
        return index.offsetOf(c.start) to index.offsetOf(c.end)
    }

    // --- 文本进出 ----------------------------------------------------------------------------

    fun getText(): String = buffer.text()

    fun setText(text: String) {
        buffer.setText(text)
        index.invalidateFrom(0)
        selection = TextRange.cursor(TextPosition(0, 0)) // 打开文件光标停在文首
        composing = null
        maybeNotify()
    }

    // --- 选择 --------------------------------------------------------------------------------

    fun setSelection(a: TextPosition, b: TextPosition, keepComposing: Boolean = false) {
        selection = TextRange(buffer.clamp(a), buffer.clamp(b)).normalized()
        if (!keepComposing) composing = null
        maybeNotify()
    }

    fun setCursor(p: TextPosition) = setSelection(p, p)

    fun selectAll() = setSelection(TextPosition(0, 0), buffer.endPosition())

    // --- 编辑原语 ----------------------------------------------------------------------------

    private fun replaceRange(range: TextRange, text: String) {
        val startLine = range.normalized().start.line
        val caret = buffer.replace(range, text)
        index.invalidateFrom(startLine)
        composing = null
        selection = TextRange.cursor(caret)
        maybeNotify()
    }

    fun replaceSelection(text: String) = replaceRange(selection, text)

    fun commitText(text: String, newCursorPosition: Int) {
        val target = (composing ?: selection).normalized()
        val caret = buffer.replace(target, text)
        index.invalidateFrom(target.start.line)
        composing = null
        selection = TextRange.cursor(cursorAfterInsert(target.start, newCursorPosition, caret))
        maybeNotify()
    }

    fun insert(text: String) = commitText(text, 1)

    fun backspace() {
        if (!selection.isEmpty) { replaceRange(selection, ""); return }
        val prev = previousCodePointPosition(selStart) ?: return
        replaceRange(TextRange(prev, selStart), "")
    }

    fun deleteForward() {
        if (!selection.isEmpty) { replaceRange(selection, ""); return }
        val next = nextCodePointPosition(selEnd) ?: return
        replaceRange(TextRange(selEnd, next), "")
    }

    // --- IME composing -----------------------------------------------------------------------

    fun setComposingText(text: String, newCursorPosition: Int) {
        val target = (composing ?: selection).normalized()
        val caret = buffer.replace(target, text)
        index.invalidateFrom(target.start.line)
        composing = if (text.isEmpty()) null else TextRange(target.start, caret)
        selection = TextRange.cursor(cursorAfterInsert(target.start, newCursorPosition, caret))
        maybeNotify()
    }

    fun setComposingRegion(startOffset: Int, endOffset: Int) {
        val lo = minOf(startOffset, endOffset).coerceAtLeast(0)
        val hi = maxOf(startOffset, endOffset).coerceAtMost(index.totalLength())
        composing = if (lo == hi) null else TextRange(index.positionOf(lo), index.positionOf(hi))
        maybeNotify()
    }

    fun finishComposing() {
        if (composing != null) {
            composing = null
            maybeNotify()
        }
    }

    // --- surrounding 删除（IME，offset 语义）--------------------------------------------------

    fun deleteSurroundingText(before: Int, after: Int) {
        val (selS, selE) = selectionOffsets()
        val total = index.totalLength()
        val delStart = (selS - before.coerceAtLeast(0)).coerceAtLeast(0)
        val delEnd = (selE + after.coerceAtLeast(0)).coerceAtMost(total)
        // 先删尾段（不影响其前的 offset），再删头段。
        buffer.replace(TextRange(index.positionOf(selE), index.positionOf(delEnd)), "")
        index.invalidateFrom(index.positionOf(selE).line)
        buffer.replace(TextRange(index.positionOf(delStart), index.positionOf(selS)), "")
        index.invalidateFrom(index.positionOf(delStart).line)
        composing = null
        selection = TextRange.cursor(index.positionOf(delStart))
        maybeNotify()
    }

    fun deleteSurroundingTextInCodePoints(before: Int, after: Int) {
        val beforeChars = charsForCodePoints(textBeforeCursorString(before * 2 + 2), before, fromEnd = true)
        val afterChars = charsForCodePoints(textAfterCursorString(after * 2 + 2), after, fromEnd = false)
        deleteSurroundingText(beforeChars, afterChars)
    }

    private fun textBeforeCursorString(n: Int): String {
        val off = index.offsetOf(selStart)
        val start = (off - n).coerceAtLeast(0)
        return buffer.textInRange(TextRange(index.positionOf(start), selStart))
    }

    private fun textAfterCursorString(n: Int): String {
        val off = index.offsetOf(selEnd)
        val end = (off + n).coerceAtMost(index.totalLength())
        return buffer.textInRange(TextRange(selEnd, index.positionOf(end)))
    }

    private fun charsForCodePoints(window: String, codePoints: Int, fromEnd: Boolean): Int {
        var chars = 0
        var cps = 0
        while (cps < codePoints) {
            val idx = if (fromEnd) window.length - chars - 1 else chars
            if (idx < 0 || idx >= window.length) break
            val c = window[idx]
            val pair = if (fromEnd) {
                idx - 1 >= 0 && window[idx - 1].isHighSurrogate() && c.isLowSurrogate()
            } else {
                idx + 1 < window.length && c.isHighSurrogate() && window[idx + 1].isLowSurrogate()
            }
            chars += if (pair) 2 else 1
            cps++
        }
        return chars
    }

    // --- 光标导航 ----------------------------------------------------------------------------

    fun moveCaretHorizontally(dir: Int, extend: Boolean) {
        if (!extend && !selection.isEmpty) {
            setCursor(if (dir < 0) selStart else selEnd)
            return
        }
        val from = selEnd
        val target = if (dir < 0) previousCodePointPosition(from) else nextCodePointPosition(from)
        val to = target ?: from
        if (extend) setSelection(selStart, to) else setCursor(to)
    }

    fun moveCaretVertically(dir: Int, extend: Boolean) {
        val from = selEnd
        val targetLine = (from.line + dir).coerceIn(0, buffer.lineCount - 1)
        val targetCol = from.column.coerceAtMost(buffer.lineLength(targetLine))
        val to = TextPosition(targetLine, targetCol)
        if (extend) setSelection(selStart, to) else setCursor(to)
    }

    // --- 选择文本 / IME getter ---------------------------------------------------------------

    fun selectedText(): String? = if (selection.isEmpty) null else buffer.textInRange(selection)

    fun textBeforeCursor(n: Int): CharSequence = textBeforeCursorString(n.coerceAtLeast(0))

    fun textAfterCursor(n: Int): CharSequence = textAfterCursorString(n.coerceAtLeast(0))

    fun wordRangeAt(pos: TextPosition, isWordChar: (Char) -> Boolean = Char::isLetterOrDigit): TextRange {
        val p = buffer.clamp(pos)
        val line = buffer.lineText(p.line)
        var s = p.column
        var e = p.column
        while (s > 0 && isWordChar(line[s - 1])) s--
        while (e < line.length && isWordChar(line[e])) e++
        return TextRange(TextPosition(p.line, s), TextPosition(p.line, e))
    }

    // --- 内部辅助 ----------------------------------------------------------------------------

    private fun cursorAfterInsert(insertStart: TextPosition, newCursorPosition: Int, insertedEnd: TextPosition): TextPosition {
        if (newCursorPosition == 1) return insertedEnd
        val startOff = index.offsetOf(insertStart)
        val endOff = index.offsetOf(insertedEnd)
        val raw = if (newCursorPosition > 0) endOff + (newCursorPosition - 1) else startOff + newCursorPosition
        return index.positionOf(raw.coerceIn(0, index.totalLength()))
    }

    internal fun previousCodePointPosition(pos: TextPosition): TextPosition? {
        if (pos.column > 0) {
            val line = buffer.lineText(pos.line)
            val c = pos.column
            val step = if (c >= 2 && line[c - 1].isLowSurrogate() && line[c - 2].isHighSurrogate()) 2 else 1
            return TextPosition(pos.line, c - step)
        }
        if (pos.line > 0) return TextPosition(pos.line - 1, buffer.lineLength(pos.line - 1))
        return null
    }

    internal fun nextCodePointPosition(pos: TextPosition): TextPosition? {
        val len = buffer.lineLength(pos.line)
        if (pos.column < len) {
            val line = buffer.lineText(pos.line)
            val c = pos.column
            val step = if (c + 1 < len && line[c].isHighSurrogate() && line[c + 1].isLowSurrogate()) 2 else 1
            return TextPosition(pos.line, c + step)
        }
        if (pos.line < buffer.lineCount - 1) return TextPosition(pos.line + 1, 0)
        return null
    }
}
