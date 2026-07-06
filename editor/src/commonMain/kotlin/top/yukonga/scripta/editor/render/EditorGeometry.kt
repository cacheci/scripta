package top.yukonga.scripta.editor.render

/** 手柄种类：光标 / 选区起点 / 选区终点。 */
enum class HandleKind { Caret, SelectionStart, SelectionEnd }

/**
 * 手柄的绘制中心与命中盒，均为屏幕像素、与 Compose 无关，便于单测。
 * [hitContains] 判定触点是否落在命中盒内。
 */
data class HandleGeometry(
    val centerX: Float,
    val centerY: Float,
    val hitLeft: Float,
    val hitTop: Float,
    val hitRight: Float,
    val hitBottom: Float,
) {
    fun hitContains(x: Float, y: Float): Boolean =
        x >= hitLeft && x <= hitRight && y >= hitTop && y <= hitBottom
}

/** 与渲染相关、但不依赖 Compose 的纯几何，便于单测。 */
object EditorGeometry {
    fun gutterDigits(lineCount: Int): Int {
        var digits = 1
        var n = lineCount
        while (n >= 10) {
            n /= 10; digits++
        }
        return maxOf(2, digits)
    }

    fun lineAtY(y: Float, scrollY: Float, lineHeight: Float, lineCount: Int): Int {
        if (lineHeight <= 0f || lineCount <= 0) return 0
        return ((y + scrollY) / lineHeight).toInt().coerceIn(0, lineCount - 1)
    }

    /**
     * 由光标/端点在屏幕上的矩形（[caretLeft] 以及 [caretTop]/[caretBottom]）推出手柄中心与命中盒。
     * 手柄画在光标正下方（圆心在 [caretBottom] + [radius]）；选区起点手柄略偏左、终点手柄略偏右，
     * 避免手指压住选中文本。命中盒向上延伸到 [caretTop]，让直接抓光标本身也能命中；[slop] 为额外触控外扩。
     */
    fun handleGeometry(
        kind: HandleKind,
        caretLeft: Float,
        caretTop: Float,
        caretBottom: Float,
        radius: Float,
        slop: Float,
    ): HandleGeometry {
        val cx = when (kind) {
            HandleKind.Caret -> caretLeft
            HandleKind.SelectionStart -> caretLeft - radius
            HandleKind.SelectionEnd -> caretLeft + radius
        }
        val cy = caretBottom + radius
        return HandleGeometry(
            centerX = cx,
            centerY = cy,
            hitLeft = cx - (radius + slop),
            hitTop = caretTop,
            hitRight = cx + (radius + slop),
            hitBottom = cy + (radius + slop),
        )
    }
}
