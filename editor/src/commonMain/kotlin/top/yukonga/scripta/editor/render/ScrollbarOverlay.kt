package top.yukonga.scripta.editor.render

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.unit.dp
import top.yukonga.scripta.editor.EditorColors

/**
 * 滚动条叠层：右缘纵向 thumb（可抓；抓取/拖拽由 CodeEditor 的手势层驱动，这里只画）、底缘横向
 * 指示细条（不可抓）、拖拽期行号气泡。独立轻量 Canvas——淡出动画只重绘本层，不整层重录主画布。
 *
 * draw 阶段读活值：alpha 驱动显隐与重绘唤醒（显示状态机写 Animatable），滚动量/上界驱动几何。
 * alpha ≤ 0 早退安全：唤醒不依赖本层订阅滚动量，由状态机的 alpha 写入触发。拖拽期 thumb 位置用
 * [thumbTopOverridePx]（跟手），不从 scrollY 反推——softWrap 下可见测量会抬高 maxScroll，反推会让
 * thumb 从指下溜走。
 */
@Composable
internal fun ScrollbarOverlay(
    colors: EditorColors,
    textMeasurer: TextMeasurer,
    numberStyle: TextStyle,
    alpha: () -> Float,
    dragging: () -> Boolean,
    thumbTopOverridePx: () -> Float,
    bubbleLine: () -> Int,
    scrollY: () -> Float,
    maxScrollY: () -> Float,
    scrollX: () -> Float,
    maxScrollX: () -> Float,
    minThumbPx: Float,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        val a = alpha()
        if (a <= 0f) return@Canvas
        val vh = size.height
        val vw = size.width
        val marginPx = 2.dp.toPx()

        val maxY = maxScrollY()
        val thumbH = ScrollbarMath.thumbHeight(vh, maxY, minThumbPx)
        if (thumbH > 0f) {
            val drag = dragging()
            val override = thumbTopOverridePx()
            val top = if (drag && override >= 0f) override.coerceIn(0f, vh - thumbH)
            else ScrollbarMath.thumbTop(vh, maxY, thumbH, scrollY())
            val w = (if (drag) 6.dp else 3.dp).toPx()
            drawRoundRect(
                color = if (drag) colors.scrollbarThumbActive else colors.scrollbarThumb,
                topLeft = Offset(vw - w - marginPx, top),
                size = Size(w, thumbH),
                cornerRadius = CornerRadius(w / 2f),
                alpha = a,
            )
            val line = bubbleLine()
            if (drag && line >= 0) {
                // 行号气泡：thumb 左侧胶囊。逐帧测量——拖拽期专属、行号短串，开销可忽略。
                // 底/字用前景/背景反色对（不加新色槽：气泡是拖拽瞬态 UI，对比度优先于可配置）。
                val tl = textMeasurer.measure((line + 1).toString(), numberStyle)
                val padH = 10.dp.toPx()
                val padV = 6.dp.toPx()
                val bw = tl.size.width + padH * 2
                val bh = tl.size.height + padV * 2
                val bx = vw - w - marginPx - 12.dp.toPx() - bw
                val by = (top + thumbH / 2f - bh / 2f).coerceIn(0f, (vh - bh).coerceAtLeast(0f))
                drawRoundRect(colors.foreground.copy(alpha = 0.92f), Offset(bx, by), Size(bw, bh), CornerRadius(bh / 2f))
                drawText(tl, color = colors.background, topLeft = Offset(bx + padH, by + padV))
            }
        }

        // 横向指示（不可抓）：底缘细条，位置感用——拖拽价值低且底缘与符号条/系统手势冲突面大。
        val maxX = maxScrollX()
        if (maxX > 0f) {
            val thumbW = ScrollbarMath.thumbHeight(vw, maxX, minThumbPx)
            if (thumbW > 0f) {
                val left = ScrollbarMath.thumbTop(vw, maxX, thumbW, scrollX())
                val h = 3.dp.toPx()
                drawRoundRect(
                    color = colors.scrollbarThumb,
                    topLeft = Offset(left, vh - h - marginPx),
                    size = Size(thumbW, h),
                    cornerRadius = CornerRadius(h / 2f),
                    alpha = a,
                )
            }
        }
    }
}
