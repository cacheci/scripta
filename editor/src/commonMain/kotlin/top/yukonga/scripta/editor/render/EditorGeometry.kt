package top.yukonga.scripta.editor.render

/** 与渲染相关、但不依赖 Compose 的纯几何，便于单测。 */
object EditorGeometry {
    fun gutterDigits(lineCount: Int): Int {
        var digits = 1
        var n = lineCount
        while (n >= 10) { n /= 10; digits++ }
        return maxOf(2, digits)
    }

    fun lineAtY(y: Float, scrollY: Float, lineHeight: Float, lineCount: Int): Int {
        if (lineHeight <= 0f || lineCount <= 0) return 0
        return ((y + scrollY) / lineHeight).toInt().coerceIn(0, lineCount - 1)
    }
}
