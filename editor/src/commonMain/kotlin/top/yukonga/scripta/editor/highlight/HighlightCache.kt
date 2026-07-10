package top.yukonga.scripta.editor.highlight

/**
 * 行状态链缓存：懒推进「行退出状态」数组，供虚拟化渲染按需取任意行的着色段——请求第 N 行会把
 * 0..N-1 的状态链补齐（每行只分词一次），编辑后经 [invalidateFrom] 把可信前缀截到最早受影响行，
 * 其上游状态不动、下游按需重算。分词本身便宜（纯行内扫描），不再缓存 span 结果；渲染层的
 * layout 缓存以「内容 + spans 相等」判断复用，稳态滚动/闪烁帧根本不会走到这里。
 */
class HighlightCache(private val highlighter: SyntaxHighlighter) {

    private val exitStates = ArrayList<LineState?>()
    private var valid = 0 // exitStates[0, valid) 可信

    /** 编辑触及 [line]（及其后）：截断可信前缀。行号取下界即可（多失效只多算、不出错）。 */
    fun invalidateFrom(line: Int) {
        val v = line.coerceAtLeast(0)
        if (v < valid) valid = v
    }

    /** 第 [line] 行的着色段；[getLine] 供状态链补齐时取行文本。 */
    fun spansForLine(line: Int, getLine: (Int) -> String): List<HighlightSpan> {
        while (valid < line) advance(getLine)
        val h = highlighter.highlightLine(getLine(line), entryFor(line))
        if (line == valid) {
            setExit(line, h.exitState)
            valid = line + 1
        }
        return h.spans
    }

    private fun entryFor(line: Int): LineState? =
        if (line == 0) highlighter.initialState else exitStates[line - 1]

    private fun advance(getLine: (Int) -> String) {
        val h = highlighter.highlightLine(getLine(valid), entryFor(valid))
        setExit(valid, h.exitState)
        valid++
    }

    private fun setExit(line: Int, state: LineState?) {
        while (exitStates.size <= line) exitStates.add(null)
        exitStates[line] = state
    }
}
