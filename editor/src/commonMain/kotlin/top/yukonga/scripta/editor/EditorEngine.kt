package top.yukonga.scripta.editor

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import top.yukonga.scripta.editor.text.EditKind
import top.yukonga.scripta.editor.text.SelectionState
import top.yukonga.scripta.editor.text.TextBuffer
import top.yukonga.scripta.editor.text.TextEdit
import top.yukonga.scripta.editor.text.TextPosition
import top.yukonga.scripta.editor.text.TextRange
import top.yukonga.scripta.editor.text.UndoHistory
import top.yukonga.scripta.editor.text.UndoStep

/**
 * 编辑引擎：持有 TextBuffer + 归一化选择 + 可选 composing 区间，承载全部编辑/选择/IME 语义
 * （由 spike 的 MiniEditorState 推广到 (行,列) 坐标）。offset 只在 IME 边界经 piece-tree（buffer.offsetAt/positionAt）换算。
 *
 * selection 恒为归一化（start ≤ end）；cursor 即 start == end。composing 为 null 表示无输入法预编辑。
 */
class EditorEngine(initialText: String = "") {

    val buffer = TextBuffer()

    var selection: TextRange by mutableStateOf(TextRange.cursor(TextPosition(0, 0)))
        private set

    // 选区的锚点与活动端（head）：方向键 / 拖拽移动的是 head、anchor 固定；selection 恒为二者的归一化视图，
    // 供渲染 / IME 使用。必须显式存二者——归一化会丢失方向，只靠归一化选区时 Shift+左/上 的第二步会
    // setSelection(head, head) 塌成光标，无法继续朝反方向扩选（B1）。
    private var anchor: TextPosition = TextPosition(0, 0)
    private var head: TextPosition = TextPosition(0, 0)

    /** 选区活动端（head）：光标闪烁 / keep-in-view 应跟随它。空选区时 head==anchor==光标位置。 */
    val caret: TextPosition get() = head

    var composing: TextRange? by mutableStateOf(null)
        private set

    /** 整篇替换（setText / 换文档 / 打开文件）的代次，编辑不改。视图层据此重置「跨文档累积」的量
     *  （如横向滚动范围 widestSeen），避免旧文档的最宽行残留到新文档。 */
    var contentGeneration: Int by mutableStateOf(0)
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

    private val history = UndoHistory()

    // 快照 state 供 UI（菜单置灰 / 工具栏按钮）观测；随每次历史变动同步。
    var canUndo: Boolean by mutableStateOf(false)
        private set
    var canRedo: Boolean by mutableStateOf(false)
        private set

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

    fun selectionOffsets(): Pair<Int, Int> = buffer.offsetAt(selStart) to buffer.offsetAt(selEnd)

    fun composingOffsets(): Pair<Int, Int> {
        val c = composing ?: return -1 to -1
        return buffer.offsetAt(c.start) to buffer.offsetAt(c.end)
    }

    // --- 文本进出 ----------------------------------------------------------------------------

    fun getText(): String = buffer.text()

    fun setText(text: String) {
        buffer.setText(text)
        collapseCaret(TextPosition(0, 0)) // 打开文件光标停在文首
        composing = null
        desiredColumn = null
        contentGeneration++ // 换文档：视图层据此重置横向范围等跨文档累积量
        history.clear() // 整篇替换 = 换文档，旧文档的编辑历史不再适用
        syncHistory()
        maybeNotify()
    }

    // --- 撤销 / 重做 -------------------------------------------------------------------------

    fun undo(): Boolean {
        val step = history.undo() ?: return false
        applyStep(step)
        return true
    }

    fun redo(): Boolean {
        val step = history.redo() ?: return false
        applyStep(step)
        return true
    }

    /** 回放一步历史：按序应用增量，再把 anchor/head 恢复到快照（保留选区方向）。 */
    private fun applyStep(step: UndoStep) {
        composing = null
        desiredColumn = null
        for (e in step.edits) {
            val start = buffer.positionAt(e.offset)
            val end = buffer.positionAt(e.offset + e.removed.length)
            buffer.replace(TextRange(start, end), e.inserted)
        }
        anchor = buffer.positionAt(step.selection.anchor)
        head = buffer.positionAt(step.selection.head)
        selection = TextRange(anchor, head).normalized()
        syncHistory()
        maybeNotify()
    }

    private fun syncHistory() {
        canUndo = history.canUndo
        canRedo = history.canRedo
    }

    /** 当前 anchor/head 的扁平 offset 快照（撤销单元的选区端）。 */
    private fun selectionSnapshot() = SelectionState(buffer.offsetAt(anchor), buffer.offsetAt(head))

    /**
     * 执行替换并收集增量：removed 在替换前取原文，inserted 从缓冲回读——回读到的才是真正入档的文本
     * （buffer.replace 会把 CRLF 规整成 LF，直接记入参会与文档不符、重做长度错位）。
     */
    private fun replaceCollecting(range: TextRange, text: String): Pair<TextEdit, TextPosition> {
        val r = range.normalized()
        val start = buffer.clamp(r.start)
        val startOff = buffer.offsetAt(start)
        val removed = buffer.textInRange(r)
        val newCaret = buffer.replace(r, text)
        val inserted = buffer.textInRange(TextRange(start, newCaret))
        return TextEdit(startOff, removed, inserted) to newCaret
    }

    /** 单码点判定（含代理对）：单字符键入的合并粒度。 */
    private fun isSingleCodePoint(s: String): Boolean =
        s.length == 1 || (s.length == 2 && s[0].isHighSurrogate() && s[1].isLowSurrogate())

    /**
     * 把 [ranges]（扁平 offset 区间 (start, end)，升序且互不重叠）全部替换为 [replacement]，
     * 整个操作为一个撤销单元；返回替换数。降序应用——先替换靠后的区间，前面区间的 offset 不受影响。
     * 光标落在首个（offset 最小）替换段末尾：降序应用后该 offset 已定格。
     */
    fun replaceAllOffsetRanges(ranges: List<Pair<Int, Int>>, replacement: String): Int {
        if (ranges.isEmpty()) return 0
        val selBefore = selectionSnapshot()
        val edits = ArrayList<TextEdit>(ranges.size)
        for ((s, e) in ranges.asReversed()) {
            val (edit, _) = replaceCollecting(TextRange(buffer.positionAt(s), buffer.positionAt(e)), replacement)
            edits.add(edit)
        }
        composing = null
        desiredColumn = null
        collapseCaret(buffer.positionAt(ranges.first().first + edits.last().inserted.length))
        val selAfter = selectionSnapshot()
        history.beginGroup()
        for (edit in edits) history.record(edit, selBefore, selAfter, EditKind.Other)
        history.endGroup()
        syncHistory()
        maybeNotify()
        return ranges.size
    }

    // --- 选择 --------------------------------------------------------------------------------

    fun setSelection(a: TextPosition, b: TextPosition, keepComposing: Boolean = false) {
        anchor = buffer.clamp(a)
        head = buffer.clamp(b)
        selection = TextRange(anchor, head).normalized()
        if (!keepComposing) composing = null
        desiredColumn = null
        history.breakMerge() // 显式定位是语义断点：其后键入不并入此前的输入单元
        maybeNotify()
    }

    fun setCursor(p: TextPosition) = setSelection(p, p)

    /** 保持 anchor 不动、把 head 移到 [to]，选区随之伸缩（Shift+方向键、手柄 / 拖拽扩选、视觉行上下移动）。 */
    fun extendSelectionTo(to: TextPosition) {
        head = buffer.clamp(to)
        selection = TextRange(anchor, head).normalized()
        composing = null
        desiredColumn = null
        history.breakMerge() // 与 setSelection 同理：选区变动后不再并入此前的输入单元
        maybeNotify()
    }

    fun selectAll() = setSelection(TextPosition(0, 0), buffer.endPosition())

    // --- 编辑原语 ----------------------------------------------------------------------------

    private fun replaceRange(range: TextRange, text: String, kind: EditKind = EditKind.Other) {
        val selBefore = selectionSnapshot()
        val (edit, newCaret) = replaceCollecting(range, text)
        composing = null
        desiredColumn = null
        collapseCaret(newCaret)
        history.record(edit, selBefore, selectionSnapshot(), kind)
        syncHistory()
        maybeNotify()
    }

    fun replaceSelection(text: String) = replaceRange(selection, text)

    fun commitText(text: String, newCursorPosition: Int) {
        val target = (composing ?: selection).normalized()
        val wasComposing = composing != null
        val selBefore = selectionSnapshot()
        val (edit, newCaret) = replaceCollecting(target, text)
        composing = null
        desiredColumn = null
        collapseCaret(cursorAfterInsert(target.start, newCursorPosition, newCaret))
        // 预编辑中的提交并入本次会话单元并封口；否则单码点纯插入按键入合并，多字符（粘贴 / 整词提交）自成单元。
        val kind = when {
            wasComposing -> EditKind.Composing
            edit.removed.isEmpty() && isSingleCodePoint(edit.inserted) -> EditKind.Typing
            else -> EditKind.Other
        }
        history.record(edit, selBefore, selectionSnapshot(), kind)
        if (wasComposing) history.breakMerge() // 提交结束会话：下个会话 / 键入不并入
        syncHistory()
        maybeNotify()
    }

    fun insert(text: String) = commitText(text, 1)

    fun backspace() {
        if (!selection.isEmpty) {
            replaceRange(selection, ""); return
        }
        val prev = previousCodePointPosition(selStart) ?: return
        replaceRange(TextRange(prev, selStart), "", EditKind.DeleteBackward)
    }

    fun deleteForward() {
        if (!selection.isEmpty) {
            replaceRange(selection, ""); return
        }
        val next = nextCodePointPosition(selEnd) ?: return
        replaceRange(TextRange(selEnd, next), "", EditKind.DeleteForward)
    }

    // --- IME composing -----------------------------------------------------------------------

    fun setComposingText(text: String, newCursorPosition: Int) {
        val target = (composing ?: selection).normalized()
        val selBefore = selectionSnapshot()
        val (edit, newCaret) = replaceCollecting(target, text)
        composing = if (text.isEmpty()) null else TextRange(target.start, newCaret)
        desiredColumn = null
        collapseCaret(cursorAfterInsert(target.start, newCursorPosition, newCaret))
        history.record(edit, selBefore, selectionSnapshot(), EditKind.Composing)
        syncHistory()
        maybeNotify()
    }

    fun setComposingRegion(startOffset: Int, endOffset: Int) {
        val lo = minOf(startOffset, endOffset).coerceAtLeast(0)
        val hi = maxOf(startOffset, endOffset).coerceAtMost(buffer.totalLength())
        composing = if (lo == hi) null else TextRange(buffer.positionAt(lo), buffer.positionAt(hi))
        maybeNotify()
    }

    fun finishComposing() {
        if (composing != null) {
            composing = null
            history.breakMerge() // 会话就此定格：其后编辑不再并入本次预编辑单元
            maybeNotify()
        }
    }

    // --- surrounding 删除（IME，offset 语义）--------------------------------------------------

    fun deleteSurroundingText(before: Int, after: Int) {
        val (selS, selE) = selectionOffsets()
        val total = buffer.totalLength()
        val delStart = (selS - before.coerceAtLeast(0)).coerceAtLeast(0)
        val delEnd = (selE + after.coerceAtLeast(0)).coerceAtMost(total)
        val selBefore = selectionSnapshot()
        // 先删尾段（不影响其前的 offset），再删头段。piece-tree 即时一致，无需失效索引。
        val (tailEdit, _) = replaceCollecting(TextRange(buffer.positionAt(selE), buffer.positionAt(delEnd)), "")
        val (headEdit, _) = replaceCollecting(TextRange(buffer.positionAt(delStart), buffer.positionAt(selS)), "")
        composing = null
        desiredColumn = null
        collapseCaret(buffer.positionAt(delStart))
        // 两段删除是同一 IME 请求：归入同一撤销单元（撤销逆序回放、重做按原序回放）。
        val selAfter = selectionSnapshot()
        history.beginGroup()
        history.record(tailEdit, selBefore, selAfter, EditKind.Other)
        history.record(headEdit, selBefore, selAfter, EditKind.Other)
        history.endGroup()
        syncHistory()
        maybeNotify()
    }

    fun deleteSurroundingTextInCodePoints(before: Int, after: Int) {
        val beforeChars = charsForCodePoints(textBeforeCursorString(before * 2 + 2), before, fromEnd = true)
        val afterChars = charsForCodePoints(textAfterCursorString(after * 2 + 2), after, fromEnd = false)
        deleteSurroundingText(beforeChars, afterChars)
    }

    private fun textBeforeCursorString(n: Int): String {
        val off = buffer.offsetAt(selStart)
        val start = (off - n).coerceAtLeast(0)
        return buffer.textInRange(TextRange(buffer.positionAt(start), selStart))
    }

    private fun textAfterCursorString(n: Int): String {
        val off = buffer.offsetAt(selEnd)
        val end = (off + n).coerceAtMost(buffer.totalLength())
        return buffer.textInRange(TextRange(selEnd, buffer.positionAt(end)))
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
        val from = head
        val target = if (dir < 0) previousCodePointPosition(from) else nextCodePointPosition(from)
        val to = target ?: from
        if (extend) extendSelectionTo(to) else setCursor(to)
    }

    fun moveCaretVertically(dir: Int, extend: Boolean) {
        val from = head
        val goal = desiredColumn ?: from.column
        val targetLine = (from.line + dir).coerceIn(0, buffer.lineCount - 1)
        val targetCol = goal.coerceAtMost(buffer.lineLength(targetLine))
        val to = TextPosition(targetLine, targetCol)
        if (extend) extendSelectionTo(to) else setCursor(to)
        desiredColumn = goal // setCursor/extendSelectionTo 已清空，这里恢复目标列供连续上下移动
    }

    fun moveCaretToLineStart(extend: Boolean) {
        val to = TextPosition(head.line, 0)
        if (extend) extendSelectionTo(to) else setCursor(to)
    }

    fun moveCaretToLineEnd(extend: Boolean) {
        val to = TextPosition(head.line, buffer.lineLength(head.line))
        if (extend) extendSelectionTo(to) else setCursor(to)
    }

    fun moveCaretToDocStart(extend: Boolean) {
        val to = TextPosition(0, 0)
        if (extend) extendSelectionTo(to) else setCursor(to)
    }

    fun moveCaretToDocEnd(extend: Boolean) {
        val to = buffer.endPosition()
        if (extend) extendSelectionTo(to) else setCursor(to)
    }

    /** 按词移动：从 head 越过一段「同类字符」到其边界（词 / 空白 / 标点各成段，见 [charClass]）；
     *  在行首 / 行末与逐字导航一样跨行。横向移动 → 清目标列（extendSelectionTo/setCursor 已清）。 */
    fun moveCaretByWord(dir: Int, extend: Boolean) {
        val to = wordBoundaryFrom(head, dir)
        if (extend) extendSelectionTo(to) else setCursor(to)
    }

    /** 翻页：按 [dir]*[lines] 行纵向移动，保留 goal column（与 [moveCaretVertically] 同法）。 */
    fun movePage(dir: Int, lines: Int, extend: Boolean) {
        val from = head
        val goal = desiredColumn ?: from.column
        val targetLine = (from.line + dir * lines).coerceIn(0, buffer.lineCount - 1)
        val targetCol = goal.coerceAtMost(buffer.lineLength(targetLine))
        val to = TextPosition(targetLine, targetCol)
        if (extend) extendSelectionTo(to) else setCursor(to)
        desiredColumn = goal // setCursor/extendSelectionTo 已清空，这里恢复目标列供连续翻页
    }

    private fun wordBoundaryFrom(pos: TextPosition, dir: Int): TextPosition {
        val line = buffer.lineText(pos.line)
        return if (dir > 0) {
            if (pos.column >= line.length) {
                if (pos.line < buffer.lineCount - 1) TextPosition(pos.line + 1, 0) else pos
            } else {
                val cls = charClass(line[pos.column])
                var c = pos.column
                while (c < line.length && charClass(line[c]) == cls) c++
                TextPosition(pos.line, c)
            }
        } else {
            if (pos.column <= 0) {
                if (pos.line > 0) TextPosition(pos.line - 1, buffer.lineLength(pos.line - 1)) else pos
            } else {
                val cls = charClass(line[pos.column - 1])
                var c = pos.column
                while (c > 0 && charClass(line[c - 1]) == cls) c--
                TextPosition(pos.line, c)
            }
        }
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

    /**
     * 从锚定词 [anchorWord] 扩展到 [at] 所在词：选区并到两词的最小外包区间，两端吸附词边界。
     * 用于长按选词后的拖拽/边缘自动滚动扩展——即便 [at] 仍落在锚定词内（手指未动）也保留整词、不塌成光标。
     */
    fun selectWordRange(anchorWord: TextRange, at: TextPosition) {
        val cur = wordRangeAt(at)
        setSelection(minOf(anchorWord.start, cur.start), maxOf(anchorWord.end, cur.end))
    }

    /** 字符归类：0=空白，1=词字符（字母数字/_/$），2=标点符号。 */
    private fun charClass(c: Char): Int = when {
        c.isWhitespace() -> 0
        c.isLetterOrDigit() || c == '_' || c == '$' -> 1
        else -> 2
    }

    // --- 内部辅助 ----------------------------------------------------------------------------

    /** 折叠光标到 [p] 并同步 anchor/head：编辑原语把选区收敛为插入点时用（composing/desiredColumn 由调用方清）。 */
    private fun collapseCaret(p: TextPosition) {
        anchor = p
        head = p
        selection = TextRange.cursor(p)
    }

    private fun cursorAfterInsert(insertStart: TextPosition, newCursorPosition: Int, insertedEnd: TextPosition): TextPosition {
        if (newCursorPosition == 1) return insertedEnd
        val startOff = buffer.offsetAt(insertStart)
        val endOff = buffer.offsetAt(insertedEnd)
        val raw = if (newCursorPosition > 0) endOff + (newCursorPosition - 1) else startOff + newCursorPosition
        return buffer.positionAt(raw.coerceIn(0, buffer.totalLength()))
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
