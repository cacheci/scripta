package top.yukonga.scripta.editor.text

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

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
        val sb = StringBuilder()
        for (i in lines.indices) {
            sb.append(lines[i])
            if (i != lines.size - 1) sb.append('\n')
        }
        return sb.toString()
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
        version++

        val caretLine = s.line + inserted.size - 1
        val caretCol = if (inserted.size == 1) prefix.length + inserted[0].length else inserted.last().length
        return TextPosition(caretLine, caretCol)
    }

    fun setText(text: String) {
        lines.clear()
        lines.addAll(splitToLines(text.replace("\r\n", "\n").replace("\r", "\n")))
        version++
    }

    private fun splitToLines(text: String): MutableList<StringBuilder> {
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
