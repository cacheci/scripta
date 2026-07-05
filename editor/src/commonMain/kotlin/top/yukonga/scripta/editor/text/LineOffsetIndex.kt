package top.yukonga.scripta.editor.text

/**
 * (行,列) ↔ 扁平 UTF-16 offset 的换算，只在 IME 与 getText 边界用。
 * `starts[i]` = 第 i 行在 join('\n') 文本中的起始 offset，starts[0] == 0。
 * 增量：编辑行 L 后调 [invalidateFrom]，只让 starts[>L] 失效，查询时惰性向后补算。
 */
class LineOffsetIndex(private val buffer: TextBuffer) {

    private var starts = IntArray(buffer.lineCount + 1)
    private var validUpTo = 0 // starts[0..validUpTo] 有效

    /** 第 [changedLine] 行内容改过：它自己的行首不变，但其后所有行首失效。 */
    fun invalidateFrom(changedLine: Int) {
        validUpTo = minOf(validUpTo, changedLine.coerceAtLeast(0))
    }

    private fun ensure(line: Int) {
        val target = line.coerceIn(0, buffer.lineCount)
        if (starts.size < buffer.lineCount + 1) {
            starts = starts.copyOf(buffer.lineCount + 1)
        }
        validUpTo = minOf(validUpTo, buffer.lineCount) // 行数缩小后收敛
        var i = validUpTo
        while (i < target) {
            starts[i + 1] = starts[i] + buffer.lineLength(i) + 1 // +1 = '\n'
            i++
        }
        validUpTo = maxOf(validUpTo, target)
    }

    fun offsetOf(pos: TextPosition): Int {
        val p = buffer.clamp(pos)
        ensure(p.line)
        return starts[p.line] + p.column
    }

    fun positionOf(offset: Int): TextPosition {
        val total = totalLength()
        val off = offset.coerceIn(0, total)
        ensure(buffer.lineCount - 1)
        // 找最大的 starts[i] <= off，i ∈ [0, lineCount-1]
        var lo = 0
        var hi = buffer.lineCount - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= off) lo = mid else hi = mid - 1
        }
        val col = (off - starts[lo]).coerceAtMost(buffer.lineLength(lo))
        return TextPosition(lo, col)
    }

    fun totalLength(): Int {
        ensure(buffer.lineCount - 1)
        val last = buffer.lineCount - 1
        return starts[last] + buffer.lineLength(last)
    }
}
