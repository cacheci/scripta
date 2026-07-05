package top.yukonga.scripta.editor.viewport

import kotlin.math.ceil
import kotlin.math.floor

/** 视口虚拟化的唯一职责：把滚动/视口高度换成需要绘制的行区间（含 overscan）。O(1)。 */
object Viewport {
    fun visibleLines(
        scrollY: Float,
        viewportHeight: Float,
        lineHeight: Float,
        lineCount: Int,
        overscan: Int = 2,
    ): IntRange {
        if (lineCount <= 0 || lineHeight <= 0f) return IntRange.EMPTY
        val first = floor(scrollY / lineHeight).toInt() - overscan
        val last = ceil((scrollY + viewportHeight) / lineHeight).toInt() - 1 + overscan
        val f = first.coerceIn(0, lineCount - 1)
        val l = last.coerceIn(0, lineCount - 1)
        return if (f > l) IntRange.EMPTY else f..l
    }
}
