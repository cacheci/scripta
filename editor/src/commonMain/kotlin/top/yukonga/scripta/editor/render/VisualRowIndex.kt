package top.yukonga.scripta.editor.render

/**
 * 软换行模式下的「文档行 ↔ 视觉行」映射。每个文档行占若干视觉行（换行后的行数），默认 1；
 * 可见行被测量后用 [setRows] 更新其真实行数。基于 Fenwick 树（BIT），前缀和/按视觉行定位均为 O(log n)，
 * 因此即便跳到第几十万行也无需测量全文——未测量的行按 1 行估算，滚动时自然收敛。
 *
 * 不换行模式不需要它（每行恒 1 行，用平凡公式即可）。
 */
class VisualRowIndex(lineCount: Int) {

    private var n = lineCount
    private var rowsArr = IntArray(n) { 1 }
    private var tree = IntArray(n + 1)

    init {
        buildTree()
    }

    val lineCount: Int get() = n

    fun rows(line: Int): Int = rowsArr[line]

    fun setRows(line: Int, rows: Int) {
        if (line < 0 || line >= n) return
        val r = rows.coerceAtLeast(1)
        val delta = r - rowsArr[line]
        if (delta == 0) return
        rowsArr[line] = r
        var i = line + 1
        while (i <= n) {
            tree[i] += delta; i += i and (-i)
        }
    }

    /** 视觉行数：行 [0, line) 的行数之和。 */
    fun rowsBefore(line: Int): Int {
        var s = 0
        var x = line.coerceIn(0, n)
        while (x > 0) {
            s += tree[x]; x -= x and (-x)
        }
        return s
    }

    fun totalRows(): Int = rowsBefore(n)

    /** 包含第 [row] 个视觉行的文档行下标（clamp 到 [0, n-1]）。 */
    fun lineAtRow(row: Int): Int {
        if (n == 0) return 0
        var pos = 0
        var remaining = row.coerceAtLeast(0)
        var k = 1
        while (k shl 1 <= n) k = k shl 1
        while (k > 0) {
            val next = pos + k
            if (next <= n && tree[next] <= remaining) {
                pos = next
                remaining -= tree[next]
            }
            k = k shr 1
        }
        return pos.coerceIn(0, n - 1)
    }

    private fun buildTree() {
        tree = IntArray(n + 1)
        for (i in 1..n) {
            tree[i] += rowsArr[i - 1]
            val parent = i + (i and (-i))
            if (parent <= n) tree[parent] += tree[i]
        }
    }
}
