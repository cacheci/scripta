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

/**
 * 只绘制可见行的画布。从 [firstVisibleLine] 向下按 [lineTopPx] 走，直到超出视口。行高取自各行 layout
 * （换行模式下一行可占多视觉行），因此对换行/不换行统一处理。gutter 固定不随横向滚动。
 * 选择用 [TextLayoutResult.getPathForRange]（跨视觉行正确），光标/预编辑用 [TextLayoutResult.getCursorRect]。
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
    firstVisibleLine: Int,
    lineTopPx: (Int) -> Float,
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
        val lineCount = engine.buffer.lineCount

        var line = firstVisibleLine.coerceIn(0, (lineCount - 1).coerceAtLeast(0))
        while (line < lineCount) {
            val top = lineTopPx(line) - scrollY
            if (top >= size.height) break
            val layout = layoutFor(line)
            if (layout == null) { line++; continue }
            val h = layout.size.height.toFloat()
            if (top + h > 0f) {
                // 当前行淡色高亮（无选择时）
                if (sel.isEmpty && sel.start.line == line) {
                    drawRect(colors.gutterBackground, topLeft = Offset(gutterWidthPx, top), size = Size(size.width - gutterWidthPx, h))
                }
                // 选择覆盖层（跨视觉行的 path）
                if (!sel.isEmpty && line >= sel.start.line && line <= sel.end.line) {
                    val lineLen = engine.buffer.lineLength(line)
                    val cS = if (line == sel.start.line) sel.start.column else 0
                    val cE = if (line == sel.end.line) sel.end.column else lineLen
                    if (cE > cS) {
                        val path = layout.getPathForRange(cS, cE)
                        path.translate(Offset(textX, top))
                        drawPath(path, colors.selection)
                    }
                    if (line != sel.end.line) {
                        // 该行的换行符也在选区内：行尾补一小块提示
                        val cr = layout.getCursorRect(lineLen)
                        drawRect(colors.selection, topLeft = Offset(textX + cr.left, top + cr.top), size = Size(lineHeightPx * 0.4f, cr.bottom - cr.top))
                    }
                }
                // 正文（含换行后的多视觉行）
                drawText(layout, color = colors.foreground, topLeft = Offset(textX, top))
                // 行号（对齐到文档行顶部）
                val num = textMeasurer.measure((line + 1).toString(), numberStyle)
                drawText(num, color = colors.gutterForeground, topLeft = Offset(gutterWidthPx - padXPx - num.size.width, top))
            }
            line++
        }

        // 预编辑下划线
        comp?.let { c ->
            val layout = layoutFor(c.start.line)
            if (layout != null) {
                val top = lineTopPx(c.start.line) - scrollY
                val startRect = layout.getCursorRect(c.start.column.coerceIn(0, engine.buffer.lineLength(c.start.line)))
                val endRect = layout.getCursorRect(c.end.column.coerceIn(0, engine.buffer.lineLength(c.start.line)))
                if (startRect.top == endRect.top) { // 同一视觉行
                    val y = top + startRect.bottom - 2f
                    drawLine(colors.cursor, Offset(textX + startRect.left, y), Offset(textX + endRect.left, y), strokeWidth = 3f)
                }
            }
        }

        // 光标（无选择、闪烁可见时）
        if (sel.isEmpty && caretVisible) {
            val layout = layoutFor(sel.start.line)
            if (layout != null) {
                val top = lineTopPx(sel.start.line) - scrollY
                val cr = layout.getCursorRect(sel.start.column.coerceIn(0, engine.buffer.lineLength(sel.start.line)))
                drawLine(colors.cursor, Offset(textX + cr.left, top + cr.top + 1f), Offset(textX + cr.left, top + cr.bottom - 1f), strokeWidth = 2.5f)
            }
        }
    }
}
