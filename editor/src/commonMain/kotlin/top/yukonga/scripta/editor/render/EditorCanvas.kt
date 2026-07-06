package top.yukonga.scripta.editor.render

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import top.yukonga.scripta.editor.EditorColors
import top.yukonga.scripta.editor.EditorEngine
import top.yukonga.scripta.editor.LruCache
import top.yukonga.scripta.editor.text.TextPosition

/**
 * 只绘制可见行的画布。从 [firstVisibleLine] 向下按 [lineTopPx] 走，直到超出视口。行高取自各行 layout
 * （换行模式下一行可占多视觉行），因此对换行/不换行统一处理。gutter 固定不随横向滚动。
 *
 * 每行文字/光标/选区/预编辑都按 `refBaselinePx - layout.firstBaseline` 做基线平移，使所有行（中/英/混排）
 * 的基线落在同一水平线，抵消 CJK/拉丁字体度量差导致的整行基线偏移。
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
    scrollX: () -> Float,
    scrollY: () -> Float,
    firstVisibleLine: () -> Int,
    lineTopPx: (Int) -> Float,
    refBaselinePx: Float,
    caretVisible: () -> Boolean,
    caretHandleVisible: () -> Boolean,
    handleRadiusPx: Float,
    layoutFor: (Int) -> TextLayoutResult?,
    modifier: Modifier = Modifier,
) {
    // 行号 layout 缓存：行号只依赖行下标与 numberStyle，draw 里逐帧重测会击穿 TextMeasurer 仅 8 条的
    // 内部缓存（可见行常几十行、必然大量 miss）。numberStyle 变化（字号/配色）时整表失效；有界 LRU
    // 超上限淘汰最久未用，避免整表 clear() 把可见行号一并丢弃。缓存与逐帧重测输出一致，仅省掉重复布局。
    val numberLayoutCache = remember(numberStyle) { LruCache<Int, TextLayoutResult>(4096) }
    Canvas(modifier) {
        // 在 draw 阶段读取滚动量：滚动只触发本画布重绘，不再让上层每滚 1px 重组。
        val sX = scrollX()
        val sY = scrollY()
        drawRect(colors.background, topLeft = Offset.Zero, size = size)
        drawRect(colors.gutterBackground, topLeft = Offset.Zero, size = Size(gutterWidthPx, size.height))

        val sel = engine.selection
        val comp = engine.composing
        val textX = gutterWidthPx + padXPx - sX
        val lineCount = engine.buffer.lineCount

        var line = firstVisibleLine().coerceIn(0, (lineCount - 1).coerceAtLeast(0))
        while (line < lineCount) {
            val top = lineTopPx(line) - sY
            if (top >= size.height) break
            val layout = layoutFor(line)
            if (layout == null) {
                line++; continue
            }
            val h = layout.size.height.toFloat()
            val textTop = top + (refBaselinePx - layout.firstBaseline) // 基线对齐后的绘制顶
            if (top + h > 0f) {
                // 当前行淡色高亮（无选择时）——按槽位（非文本）绘制
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
                        path.translate(Offset(textX, textTop))
                        drawPath(path, colors.selection)
                    }
                    if (line != sel.end.line) {
                        val cr = layout.getCursorRect(lineLen)
                        drawRect(
                            colors.selection,
                            topLeft = Offset(textX + cr.left, textTop + cr.top),
                            size = Size(lineHeightPx * 0.4f, cr.bottom - cr.top)
                        )
                    }
                }
                // 正文（含换行后的多视觉行）
                drawText(layout, color = colors.foreground, topLeft = Offset(textX, textTop))
                // 行号（基线对齐到正文基线）；命中缓存则跳过重测（LRU 自动淘汰，无需手动清空）
                val num = numberLayoutCache.getOrPut(line) { textMeasurer.measure((line + 1).toString(), numberStyle) }
                val numTop = top + (refBaselinePx - num.firstBaseline)
                drawText(num, color = colors.gutterForeground, topLeft = Offset(gutterWidthPx - padXPx - num.size.width, numTop))
            }
            line++
        }

        // 预编辑下划线
        comp?.let { c ->
            val layout = layoutFor(c.start.line)
            if (layout != null) {
                val textTop = lineTopPx(c.start.line) - sY + (refBaselinePx - layout.firstBaseline)
                val len = engine.buffer.lineLength(c.start.line)
                val startRect = layout.getCursorRect(c.start.column.coerceIn(0, len))
                val endRect = layout.getCursorRect(c.end.column.coerceIn(0, len))
                if (startRect.top == endRect.top) { // 同一视觉行
                    val y = textTop + startRect.bottom - 2f
                    drawLine(colors.cursor, Offset(textX + startRect.left, y), Offset(textX + endRect.left, y), strokeWidth = 3f)
                }
            }
        }

        // 光标（无选择、闪烁可见时）。caretVisible 在 draw 里读取 blink，使闪烁只触发本画布重绘、
        // 不再让 CodeEditor 与本可组合每 500ms 整体重组。
        if (sel.isEmpty && caretVisible()) {
            val layout = layoutFor(sel.start.line)
            if (layout != null) {
                val textTop = lineTopPx(sel.start.line) - sY + (refBaselinePx - layout.firstBaseline)
                val cr = layout.getCursorRect(sel.start.column.coerceIn(0, engine.buffer.lineLength(sel.start.line)))
                drawLine(
                    colors.cursor,
                    Offset(textX + cr.left, textTop + cr.top + 1f),
                    Offset(textX + cr.left, textTop + cr.bottom - 1f),
                    strokeWidth = 2.5f
                )
            }
        }

        // 拖动手柄（泪滴）：短柄接光标底、圆点作抓取区。选区两端常驻，光标手柄按 caretHandleVisible 控制。
        fun drawHandle(kind: HandleKind, pos: TextPosition) {
            val layout = layoutFor(pos.line) ?: return
            val cr = layout.getCursorRect(pos.column.coerceIn(0, engine.buffer.lineLength(pos.line)))
            val base = lineTopPx(pos.line) - sY + (refBaselinePx - layout.firstBaseline)
            val caretLeft = textX + cr.left
            val caretBottom = base + cr.bottom
            val g = EditorGeometry.handleGeometry(kind, caretLeft, base + cr.top, caretBottom, handleRadiusPx, 0f)
            drawLine(colors.handle, Offset(caretLeft, caretBottom), Offset(g.centerX, g.centerY - handleRadiusPx), strokeWidth = 2f)
            drawCircle(colors.handle, radius = handleRadiusPx, center = Offset(g.centerX, g.centerY))
        }
        if (!sel.isEmpty) {
            drawHandle(HandleKind.SelectionStart, sel.start)
            drawHandle(HandleKind.SelectionEnd, sel.end)
        } else if (caretHandleVisible()) {
            drawHandle(HandleKind.Caret, sel.start)
        }
    }
}
