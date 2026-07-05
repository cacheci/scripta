package top.yukonga.scripta.editor.render

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import top.yukonga.scripta.editor.EditorColors
import top.yukonga.scripta.editor.EditorEngine
import top.yukonga.scripta.editor.text.TextPosition

/**
 * 只绘制可见行的画布。gutter 固定不随横向滚动；正文按 (scrollX, scrollY) 偏移。
 * 选择/光标/composing 从 [engine] 的快照 state 读取（draw 阶段订阅），改动即重绘。
 */
@Composable
fun EditorCanvas(
    engine: EditorEngine,
    colors: EditorColors,
    textMeasurer: TextMeasurer,
    textStyle: TextStyle,
    numberStyle: TextStyle,
    lineHeightPx: Float,
    gutterWidthPx: Float,
    padXPx: Float,
    scrollX: Float,
    scrollY: Float,
    visibleRange: IntRange,
    caretVisible: Boolean,
    layoutFor: (Int) -> TextLayoutResult?,
    modifier: Modifier = Modifier,
) {
    Canvas(modifier) {
        drawRect(colors.background, topLeft = Offset.Zero, size = size)
        drawRect(colors.gutterBackground, topLeft = Offset.Zero, size = Size(gutterWidthPx, size.height))

        val sel = engine.selection
        val comp = engine.composing
        val textX = gutterWidthPx + padXPx - scrollX

        // 当前行淡色高亮（无选择时）
        if (sel.isEmpty && sel.start.line in visibleRange) {
            val y = sel.start.line * lineHeightPx - scrollY
            drawRect(colors.gutterBackground, topLeft = Offset(gutterWidthPx, y), size = Size(size.width - gutterWidthPx, lineHeightPx))
        }

        // 1) 选择覆盖层
        if (!sel.isEmpty) {
            val from = maxOf(visibleRange.first, sel.start.line)
            val to = minOf(visibleRange.last, sel.end.line)
            for (ln in from..to) {
                val layout = layoutFor(ln) ?: continue
                val lineLen = engine.buffer.lineLength(ln)
                val colStart = if (ln == sel.start.line) sel.start.column else 0
                val colEnd = if (ln == sel.end.line) sel.end.column else lineLen
                val x0 = textX + layout.getHorizontalPosition(colStart, true)
                var x1 = textX + layout.getHorizontalPosition(colEnd, true)
                if (ln != sel.end.line) x1 += lineHeightPx * 0.4f // 提示被选中的 '\n'
                val y = ln * lineHeightPx - scrollY
                drawRect(colors.selection, topLeft = Offset(x0, y), size = Size((x1 - x0).coerceAtLeast(2f), lineHeightPx))
            }
        }

        // 2) 正文 + 行号
        for (ln in visibleRange) {
            val layout = layoutFor(ln) ?: continue
            val y = ln * lineHeightPx - scrollY
            drawText(layout, color = colors.foreground, topLeft = Offset(textX, y))
            val num = textMeasurer.measure((ln + 1).toString(), numberStyle)
            drawText(num, color = colors.gutterForeground, topLeft = Offset(gutterWidthPx - padXPx - num.size.width, y))
        }

        // 3) composing 下划线
        comp?.let { c ->
            if (c.start.line in visibleRange) {
                val layout = layoutFor(c.start.line)
                if (layout != null) {
                    val x0 = textX + layout.getHorizontalPosition(c.start.column, true)
                    val x1 = textX + layout.getHorizontalPosition(c.end.column, true)
                    val y = c.start.line * lineHeightPx - scrollY + lineHeightPx - 3f
                    drawLine(colors.cursor, Offset(x0, y), Offset(x1, y), strokeWidth = 3f)
                }
            }
        }

        // 4) 光标（无选择、闪烁可见时）
        if (sel.isEmpty && caretVisible && sel.start.line in visibleRange) {
            val layout = layoutFor(sel.start.line)
            if (layout != null) {
                val x = textX + layout.getHorizontalPosition(sel.start.column, true)
                val y = sel.start.line * lineHeightPx - scrollY
                drawLine(colors.cursor, Offset(x, y + 2f), Offset(x, y + lineHeightPx - 2f), strokeWidth = 2.5f)
            }
        }
    }
}

/** 便捷：把光标位置换成绘制坐标（供自动滚入视野用）。 */
fun caretTopLeft(pos: TextPosition, layout: TextLayoutResult?, gutterWidthPx: Float, padXPx: Float, lineHeightPx: Float): Offset {
    val x = gutterWidthPx + padXPx + (layout?.getHorizontalPosition(pos.column, true) ?: 0f)
    return Offset(x, pos.line * lineHeightPx)
}
