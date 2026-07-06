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

    // 上下移动记忆的目标列：穿过较短行时列被临时夹小，但回到长行仍恢复原列。仅纵向移动保持，
    // 横向移动 / 编辑 / 显式设光标都清空（见 setSelection 与各编辑原语）。
    private var desiredColumn: Int? = null

    val selStart: TextPosition get() = selection.start
    val selEnd: TextPosition get() = selection.end

    /** 是否处于「上下移动记忆目标列」状态。视图层据此在纯纵向导航时不做横向随动——目标列夹变会让横向随动
     *  把视口来回 snap，而此时横向意图本就固定在目标列、视口应保持稳定。横向移动/编辑/点按会清空目标列。 */
    val hasGoalColumn: Boolean get() = desiredColumn != null

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

    fun beginBatch() {
        batchDepth++
    }

    fun endBatch(): Boolean {
        if (batchDepth > 0) batchDepth--
        if (batchDepth == 0) fireIme()
        return batchDepth > 0
    }

    private fun maybeNotify() {
        if (batchDepth == 0) fireIme()
    }

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
        desiredColumn = null
        maybeNotify()
    }

    // --- 选择 --------------------------------------------------------------------------------

    fun setSelection(a: TextPosition, b: TextPosition, keepComposing: Boolean = false) {
        selection = TextRange(buffer.clamp(a), buffer.clamp(b)).normalized()
        if (!keepComposing) composing = null
        desiredColumn = null
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
        desiredColumn = null
        selection = TextRange.cursor(caret)
        maybeNotify()
    }

    fun replaceSelection(text: String) = replaceRange(selection, text)

    fun commitText(text: String, newCursorPosition: Int) {
        val target = (composing ?: selection).normalized()
        val caret = buffer.replace(target, text)
        index.invalidateFrom(target.start.line)
        composing = null
        desiredColumn = null
        selection = TextRange.cursor(cursorAfterInsert(target.start, newCursorPosition, caret))
        maybeNotify()
    }

    fun insert(text: String) = commitText(text, 1)

    fun backspace() {
        if (!selection.isEmpty) {
            replaceRange(selection, ""); return
        }
        val prev = previousCodePointPosition(selStart) ?: return
        replaceRange(TextRange(prev, selStart), "")
    }

    fun deleteForward() {
        if (!selection.isEmpty) {
            replaceRange(selection, ""); return
        }
        val next = nextCodePointPosition(selEnd) ?: return
        replaceRange(TextRange(selEnd, next), "")
    }

    // --- IME composing -----------------------------------------------------------------------

    fun setComposingText(text: String, newCursorPosition: Int) {
        val target = (composing ?: selection).normalized()
        val caret = buffer.replace(target, text)
        index.invalidateFrom(target.start.line)
        composing = if (text.isEmpty()) null else TextRange(target.start, caret)
        desiredColumn = null
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
        desiredColumn = null
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
        val goal = desiredColumn ?: from.column
        val targetLine = (from.line + dir).coerceIn(0, buffer.lineCount - 1)
        val targetCol = goal.coerceAtMost(buffer.lineLength(targetLine))
        val to = TextPosition(targetLine, targetCol)
        if (extend) setSelection(selStart, to) else setCursor(to)
        desiredColumn = goal // setSelection 已清空，这里恢复目标列供连续上下移动
    }

    // --- 选择文本 / IME getter ---------------------------------------------------------------

    fun selectedText(): String? = if (selection.isEmpty) null else buffer.textInRange(selection)

    fun textBeforeCursor(n: Int): CharSequence = textBeforeCursorString(n.coerceAtLeast(0))

    fun textAfterCursor(n: Int): CharSequence = textAfterCursorString(n.coerceAtLeast(0))

    /**
     * 双击/长按选中的「词」范围：选中当前位置所在的一段连续同类字符——标识符（含 _ 与 $）、空白、标点
     * 各自成段。此前按 isLetterOrDigit 判定，会把 my_variable/$scope 截断，双击空白或标点还返回空选区。
     */
    fun wordRangeAt(pos: TextPosition): TextRange {
        val p = buffer.clamp(pos)
        val line = buffer.lineText(p.line)
        if (line.isEmpty()) return TextRange.cursor(p)
        // 光标停在行尾时按其左侧字符归类。
        val idx = if (p.column < line.length) p.column else p.column - 1
        val cls = charClass(line[idx])
        var s = idx
        var e = idx + 1
        while (s > 0 && charClass(line[s - 1]) == cls) s--
        while (e < line.length && charClass(line[e]) == cls) e++
        return TextRange(TextPosition(p.line, s), TextPosition(p.line, e))
    }

    /** 字符归类：0=空白，1=词字符（字母数字/_/$），2=标点符号。 */
    private fun charClass(c: Char): Int = when {
        c.isWhitespace() -> 0
        c.isLetterOrDigit() || c == '_' || c == '$' -> 1
        else -> 2
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
