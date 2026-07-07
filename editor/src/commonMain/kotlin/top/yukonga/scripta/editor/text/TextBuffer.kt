package top.yukonga.scripta.editor.text

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 整篇缓存的字符数上限：超过则不常驻缓存，避免大文件多留一份 N 常驻。 */
private const val CACHE_TEXT_LIMIT = 1_000_000

/**
 * 行链表文档模型：一整篇被拆成一行一个 StringBuilder。按行寻址，编辑只动受影响的行，
 * 因此渲染/编辑不随文档大小退化。offset↔(行,列) 的换算不在这里，交给 LineOffsetIndex。
 *
 * 不变量：始终至少一行（空文档 = 一行空串）；行内不含 '\n'。
 */
class TextBuffer(initialText: String = "") {

    private val lines: MutableList<StringBuilder> = splitToLines(initialText)

    /** 每次改动 +1；是 Compose 快照 state，供渲染层观测。 */
    var version: Int by mutableStateOf(0)
        private set

    // 整篇拼接结果缓存：未编辑的小文档复用，省 getText / 相等比较等低频路径的 O(n) 重建。超过 CACHE_TEXT_LIMIT
    // 的大文档不缓存——那只是一份多余的 N 常驻（IME 的 getExtractedText 已窗口化、不再反复拉全篇）。任何改动
    // 都置空，见 replace/setText。
    private var cachedText: String? = null

    val lineCount: Int get() = lines.size

    fun lineText(line: Int): String = lines[line].toString()

    fun lineLength(line: Int): Int = lines[line].length

    fun endPosition(): TextPosition = TextPosition(lines.size - 1, lines.last().length)

    fun clamp(pos: TextPosition): TextPosition {
        val line = pos.line.coerceIn(0, lines.size - 1)
        val col = pos.column.coerceIn(0, lines[line].length)
        return TextPosition(line, col)
    }

    fun textInRange(range: TextRange): String {
        val r = range.normalized()
        val s = clamp(r.start)
        val e = clamp(r.end)
        if (s.line == e.line) return lines[s.line].substring(s.column, e.column)
        val sb = StringBuilder()
        sb.append(lines[s.line].substring(s.column)).append('\n')
        for (i in (s.line + 1) until e.line) sb.append(lines[i]).append('\n')
        sb.append(lines[e.line].substring(0, e.column))
        return sb.toString()
    }

    fun text(): String {
        cachedText?.let { return it }
        val sb = StringBuilder()
        for (i in lines.indices) {
            sb.append(lines[i])
            if (i != lines.size - 1) sb.append('\n')
        }
        val whole = sb.toString()
        if (whole.length <= CACHE_TEXT_LIMIT) cachedText = whole
        return whole
    }

    /** 用 [replacement] 替换 [range]；返回插入文本末尾的光标位置。 */
    fun replace(range: TextRange, replacement: String): TextPosition {
        val r = range.normalized()
        val s = clamp(r.start)
        val e = clamp(r.end)

        val prefix = lines[s.line].substring(0, s.column)
        val suffix = lines[e.line].substring(e.column)
        val inserted = splitToLines(replacement)

        // 删除 [s.line, e.line]，用 prefix + inserted + suffix 重建。subList().clear() 走一次整体
        // 搬移（ArrayList 内部 System.arraycopy），避免逐行 removeAt 的 O(删除行数 × 其后行数)。
        lines.subList(s.line, e.line + 1).clear()
        val rebuilt = ArrayList<StringBuilder>(inserted.size)
        if (inserted.size == 1) {
            rebuilt.add(StringBuilder(prefix).append(inserted[0]).append(suffix))
        } else {
            rebuilt.add(StringBuilder(prefix).append(inserted[0]))
            for (i in 1 until inserted.size - 1) rebuilt.add(inserted[i])
            rebuilt.add(StringBuilder(inserted.last()).append(suffix))
        }
        lines.addAll(s.line, rebuilt)
        cachedText = null
        version++

        val caretLine = s.line + inserted.size - 1
        val caretCol = if (inserted.size == 1) prefix.length + inserted[0].length else inserted.last().length
        return TextPosition(caretLine, caretCol)
    }

    fun setText(text: String) {
        // 无 CR 的常见情况不复制整篇（String.replace 无匹配也会新建，大文件加载时是一份多余的 N 峰值）。
        val normalized = if (text.indexOf('\r') >= 0) text.replace("\r\n", "\n").replace("\r", "\n") else text
        lines.clear()
        lines.addAll(splitToLines(normalized))
        // 规整后的整篇即 text() 的输出：小文档预热缓存（省首次 getText 重建）；大文档不缓存、避免多留一份 N。
        cachedText = if (normalized.length <= CACHE_TEXT_LIMIT) normalized else null
        version++
    }

    private fun splitToLines(raw: String): MutableList<StringBuilder> {
        // 统一换行 CRLF/CR -> LF：让粘贴/IME 提交等所有写入路径都不残留裸 CR（此前只有 setText 规整）。
        // 用 indexOf 兜底：无 CR 的常见情况不额外分配字符串。
        val text = if (raw.indexOf('\r') >= 0) raw.replace("\r\n", "\n").replace("\r", "\n") else raw
        val out = ArrayList<StringBuilder>()
        var start = 0
        var i = 0
        while (i <= text.length) {
            if (i == text.length || text[i] == '\n') {
                out.add(StringBuilder(text.substring(start, i)))
                start = i + 1
            }
            i++
        }
        if (out.isEmpty()) out.add(StringBuilder())
        return out
    }
}
