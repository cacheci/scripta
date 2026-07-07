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
import top.yukonga.scripta.editor.LineNumberMode
import top.yukonga.scripta.editor.LruCache
import top.yukonga.scripta.editor.text.TextPosition
import top.yukonga.scripta.editor.text.TextRange

/** 网格行可见列切片缓存的键：行 + 量化后的列窗口 [start,end) + 文档版本（编辑即失效）。 */
private data class GridSliceKey(val line: Int, val start: Int, val end: Int, val version: Int)

/** 网格行切片列窗口量化粒度：窗口左右对齐到 32 列倍数，横滚每 32 列才换一次 key、其余帧命中缓存。 */
private const val GRID_SLICE_QUANTUM = 32

/**
 * 只绘制可见行的画布。从 [firstVisibleLine] 向下按 [lineTopPx] 走，直到超出视口。行高取自各行 layout
 * （换行模式下一行可占多视觉行），因此对换行/不换行统一处理。
 *
 * 行号横向锚定见 [lineNumberMode]：正文/光标/手柄画完后统一铺不透明 gutter 条 + 行号，滚进来的正文透不进
 * 行号区。[LineNumberMode.PinnedToScreen] 的 gutter/行号钉屏幕左侧；[LineNumberMode.PinnedToLine] 的 gutter/
 * 行号随内容横移、滚到右侧一并从左边滑出。
 *
 * 每行文字/光标/选区/预编辑都按 `refBaselinePx - layout.firstBaseline` 做基线平移，使所有行（中/英/混排）
 * 的基线落在同一水平线，抵消 CJK/拉丁字体度量差导致的整行基线偏移。
 *
 * 超长「网格行」（[isGridLine] 为真）不整行 shaping：只测量/绘制落在视口内的可见列窗口切片，几何（光标/
 * 选区/手柄）用等宽算术（[charW] + 参考字符垂直度量 [gridRefBaseline]/[gridRefCursorTop]/[gridRefCursorBottom]）。
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
    lineNumberMode: LineNumberMode,
    scrollX: () -> Float,
    scrollY: () -> Float,
    firstVisibleLine: () -> Int,
    lineTopPx: (Int) -> Float,
    refBaselinePx: Float,
    caretVisible: () -> Boolean,
    caretHandleVisible: () -> Boolean,
    handleRadiusPx: Float,
    layoutFor: (Int) -> TextLayoutResult?,
    charW: Float,
    isGridLine: (Int) -> Boolean,
    gridRefBaseline: Float,
    gridRefCursorTop: Float,
    gridRefCursorBottom: Float,
    modifier: Modifier = Modifier,
) {
    // 行号 layout 缓存：行号只依赖行下标与 numberStyle，draw 里逐帧重测会击穿 TextMeasurer 仅 8 条的
    // 内部缓存（可见行常几十行、必然大量 miss）。numberStyle 变化（字号/配色）时整表失效；有界 LRU
    // 超上限淘汰最久未用，避免整表 clear() 把可见行号一并丢弃。缓存与逐帧重测输出一致，仅省掉重复布局。
    val numberLayoutCache = remember(numberStyle) { LruCache<Int, TextLayoutResult>(4096) }
    // 网格行可见列切片缓存：横滚/纵向 fling 时避免每帧 textInRange 新建切片 String + measure 重 shaping。
    // key 含文档版本（编辑即失效），列窗口量化到 32 列 → 窗口内滚动/闪烁/拖选帧全命中，横滚每 32 列才重测一次。
    // textStyle 变化（字号/配色）整表失效。有界 LRU：网格行同屏通常个位数，256 足够。
    val gridSliceCache = remember(textStyle) { LruCache<GridSliceKey, TextLayoutResult>(256) }
    Canvas(modifier) {
        // 在 draw 阶段读取滚动量：滚动只触发本画布重绘，不再让上层每滚 1px 重组。
        val sX = scrollX()
        val sY = scrollY()
        val bufVersion = engine.buffer.version // 网格切片缓存键用；编辑变更即让旧切片失效
        val pinnedToScreen = lineNumberMode == LineNumberMode.PinnedToScreen
        // gutter 与行号的横向偏移：固定于屏幕钉在左侧（0）；固定于行随内容横移（sX），gutter 底色条连同行号
        // 一起向左滑出。两种模式都有区别于正文区的 gutter 底色（[colors.gutterBackground]）。
        val gutterScroll = if (pinnedToScreen) 0f else sX
        drawRect(colors.background, topLeft = Offset.Zero, size = size)
        // gutter 不透明条 + 行号统一延后到正文/光标/手柄之后重绘（见文末），盖住横向滚进来的正文、保证数字清晰。

        val sel = engine.selection
        val comp = engine.composing
        val textX = gutterWidthPx + padXPx - sX
        val lineCount = engine.buffer.lineCount

        // 逐行只收集可见行/顶，待正文画完后统一盖 gutter 条 + 行号（否则滚进来的正文会糊住数字）。
        val deferredNumbers = ArrayList<Pair<Int, Float>>()

        fun drawLineNumber(line: Int, top: Float) {
            deferredNumbers.add(line to top)
        }

        var line = firstVisibleLine().coerceIn(0, (lineCount - 1).coerceAtLeast(0))
        while (line < lineCount) {
            val top = lineTopPx(line) - sY
            if (top >= size.height) break

            if (isGridLine(line)) {
                // 超长网格行：只测量/绘制可见列窗口，几何用等宽算术（单视觉行，行高 = lineHeightPx）。
                val h = lineHeightPx
                if (top + h > 0f) {
                    val lineLen = engine.buffer.lineLength(line)
                    val textTop = top + (refBaselinePx - gridRefBaseline)
                    if (sel.isEmpty && sel.start.line == line) {
                        drawRect(colors.gutterBackground, topLeft = Offset(0f, top), size = Size(size.width, h))
                    }
                    if (!sel.isEmpty && line >= sel.start.line && line <= sel.end.line) {
                        val cS = if (line == sel.start.line) sel.start.column else 0
                        val cE = if (line == sel.end.line) sel.end.column else lineLen
                        if (cE > cS) {
                            drawRect(colors.selection, topLeft = Offset(textX + cS * charW, top), size = Size((cE - cS) * charW, h))
                        }
                        if (line != sel.end.line) {
                            drawRect(colors.selection, topLeft = Offset(textX + lineLen * charW, top), size = Size(lineHeightPx * 0.4f, h))
                        }
                    }
                    // 正文：只切落在视口内的列窗口测量，避免 shaping 整行。列窗口量化到 32 列倍数（左取整、右上取整
                    // 钳到行长），使小幅横滚落在同一量化窗口 → 命中 gridSliceCache，仅每跨 32 列才重测；多切的 ≤64
                    // 个字符落在视口外、由 clipToBounds 裁掉，视觉无差。
                    val textAreaWidth = (size.width - gutterWidthPx - padXPx * 2).coerceAtLeast(1f)
                    val cols = EditorGeometry.gridVisibleColumns(sX, textAreaWidth, charW, lineLen)
                    if (cols.last > cols.first) {
                        val q = GRID_SLICE_QUANTUM
                        val qStart = (cols.first / q) * q
                        val qEnd = (((cols.last + q - 1) / q) * q).coerceAtMost(lineLen)
                        val sliceLayout = gridSliceCache.getOrPut(GridSliceKey(line, qStart, qEnd, bufVersion)) {
                            val slice = engine.buffer.textInRange(TextRange(TextPosition(line, qStart), TextPosition(line, qEnd)))
                            textMeasurer.measure(slice, textStyle, softWrap = false)
                        }
                        drawText(sliceLayout, color = colors.foreground, topLeft = Offset(textX + qStart * charW, textTop))
                    }
                    drawLineNumber(line, top)
                }
                line++
                continue
            }

            val layout = layoutFor(line)
            if (layout == null) {
                line++; continue
            }
            val h = layout.size.height.toFloat()
            val textTop = top + (refBaselinePx - layout.firstBaseline) // 基线对齐后的绘制顶
            if (top + h > 0f) {
                // 当前行淡色高亮（无选择时）：铺满整行宽（含 gutter 区）。固定模式下左段会被文末 gutter 条盖住、
                // 视觉不变；跟随模式下 gutter 条随内容滚走，整行仍均匀高亮、左侧不留缺口。
                if (sel.isEmpty && sel.start.line == line) {
                    drawRect(colors.gutterBackground, topLeft = Offset(0f, top), size = Size(size.width, h))
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
                drawLineNumber(line, top)
            }
            line++
        }

        // 预编辑下划线
        comp?.let { c ->
            val cLine = c.start.line
            if (isGridLine(cLine)) {
                val textTop = lineTopPx(cLine) - sY + (refBaselinePx - gridRefBaseline)
                val len = engine.buffer.lineLength(cLine)
                val xS = textX + c.start.column.coerceIn(0, len) * charW
                val xE = textX + c.end.column.coerceIn(0, len) * charW
                val y = textTop + gridRefCursorBottom - 2f
                drawLine(colors.cursor, Offset(xS, y), Offset(xE, y), strokeWidth = 3f)
            } else {
                val layout = layoutFor(cLine)
                if (layout != null) {
                    val textTop = lineTopPx(cLine) - sY + (refBaselinePx - layout.firstBaseline)
                    val len = engine.buffer.lineLength(cLine)
                    val startRect = layout.getCursorRect(c.start.column.coerceIn(0, len))
                    val endRect = layout.getCursorRect(c.end.column.coerceIn(0, len))
                    if (startRect.top == endRect.top) { // 同一视觉行
                        val y = textTop + startRect.bottom - 2f
                        drawLine(colors.cursor, Offset(textX + startRect.left, y), Offset(textX + endRect.left, y), strokeWidth = 3f)
                    }
                }
            }
        }

        // 光标（无选择、闪烁可见时）。caretVisible 在 draw 里读取 blink，使闪烁只触发本画布重绘、
        // 不再让 CodeEditor 与本可组合每 500ms 整体重组。
        if (sel.isEmpty && caretVisible()) {
            val cLine = sel.start.line
            if (isGridLine(cLine)) {
                val textTop = lineTopPx(cLine) - sY + (refBaselinePx - gridRefBaseline)
                val x = textX + sel.start.column.coerceIn(0, engine.buffer.lineLength(cLine)) * charW
                drawLine(colors.cursor, Offset(x, textTop + gridRefCursorTop + 1f), Offset(x, textTop + gridRefCursorBottom - 1f), strokeWidth = 2.5f)
            } else {
                val layout = layoutFor(cLine)
                if (layout != null) {
                    val textTop = lineTopPx(cLine) - sY + (refBaselinePx - layout.firstBaseline)
                    val cr = layout.getCursorRect(sel.start.column.coerceIn(0, engine.buffer.lineLength(cLine)))
                    drawLine(
                        colors.cursor,
                        Offset(textX + cr.left, textTop + cr.top + 1f),
                        Offset(textX + cr.left, textTop + cr.bottom - 1f),
                        strokeWidth = 2.5f
                    )
                }
            }
        }

        // 拖动手柄（泪滴）：短柄接光标底、圆点作抓取区。选区两端常驻，光标手柄按 caretHandleVisible 控制。
        // 网格行用等宽算术定光标矩形，其余走该行 layout。
        fun drawHandle(kind: HandleKind, pos: TextPosition) {
            val caretLeft: Float
            val crTopY: Float
            val crBottomY: Float
            if (isGridLine(pos.line)) {
                val col = pos.column.coerceIn(0, engine.buffer.lineLength(pos.line))
                val base = lineTopPx(pos.line) - sY + (refBaselinePx - gridRefBaseline)
                caretLeft = textX + col * charW
                crTopY = base + gridRefCursorTop
                crBottomY = base + gridRefCursorBottom
            } else {
                val layout = layoutFor(pos.line) ?: return
                val cr = layout.getCursorRect(pos.column.coerceIn(0, engine.buffer.lineLength(pos.line)))
                val base = lineTopPx(pos.line) - sY + (refBaselinePx - layout.firstBaseline)
                caretLeft = textX + cr.left
                crTopY = base + cr.top
                crBottomY = base + cr.bottom
            }
            val g = EditorGeometry.handleGeometry(kind, caretLeft, crTopY, crBottomY, handleRadiusPx, 0f)
            drawLine(colors.handle, Offset(caretLeft, crBottomY), Offset(g.centerX, g.centerY - handleRadiusPx), strokeWidth = 2f)
            drawCircle(colors.handle, radius = handleRadiusPx, center = Offset(g.centerX, g.centerY))
        }
        if (!sel.isEmpty) {
            drawHandle(HandleKind.SelectionStart, sel.start)
            drawHandle(HandleKind.SelectionEnd, sel.end)
        } else if (caretHandleVisible()) {
            drawHandle(HandleKind.Caret, sel.start)
        }

        // 正文/光标/手柄全部画完后，铺一条不透明 gutter（固定模式钉屏幕左侧、跟随模式随 sX 左移）盖住横向滚
        // 进来的正文，再在其上画行号——保证任意横向滚动下数字清晰、且 gutter 底色始终区别于正文区（这也是
        // 「降低透明度」要解决的可读性问题）。
        drawRect(colors.gutterBackground, topLeft = Offset(-gutterScroll, 0f), size = Size(gutterWidthPx, size.height))
        for ((line, top) in deferredNumbers) {
            val num = numberLayoutCache.getOrPut(line) { textMeasurer.measure((line + 1).toString(), numberStyle) }
            val numTop = top + (refBaselinePx - num.firstBaseline)
            drawText(num, color = colors.gutterForeground, topLeft = Offset(gutterWidthPx - padXPx - num.size.width - gutterScroll, numTop))
        }
    }
}
