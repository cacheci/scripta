package top.yukonga.scripta.editor.render

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.graphicsLayer
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
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** 网格行可见列切片缓存的键：行 + 量化后的列窗口 [start,end) + 文档版本（编辑即失效）。 */
private data class GridSliceKey(val line: Int, val start: Int, val end: Int, val version: Int)

/** 网格行切片列窗口量化粒度：窗口左右对齐到 32 列倍数，横滚每 32 列才换一次 key、其余帧命中缓存。 */
private const val GRID_SLICE_QUANTUM = 32

/**
 * 泪滴手柄路径：尖端 ([tipX],[tipY]) 贴光标底，body 圆（半径 [r]）沿单位向量 ([ux],[uy]) 从尖端向外下方悬挂，
 * 尖端到圆心距 [l]（须 > r，越大越尖）。两侧为「尖端到圆的切线」、外缘为圆的大弧，合成有尖角的水滴形。
 * 光标手柄尖朝正上、选区起点尖朝右上（body 左下）、终点尖朝左上（body 右下），即成熟编辑器的镜像对。
 */
private fun buildTeardropPath(tipX: Float, tipY: Float, ux: Float, uy: Float, r: Float, l: Float): Path {
    val cx = tipX + l * ux
    val cy = tipY + l * uy
    val gamma = acos((r / l).toDouble().coerceIn(-1.0, 1.0)) // 圆心处的半张角（切线与圆心-尖端连线夹角）
    val phi = atan2((tipY - cy).toDouble(), (tipX - cx).toDouble()) // 圆心 → 尖端 方向（rad）
    val t1 = phi + gamma // 一侧切点的圆心角
    val t1x = (cx + r * cos(t1)).toFloat()
    val t1y = (cy + r * sin(t1)).toFloat()
    val rad2deg = 180.0 / PI
    val bounds = Rect(cx - r, cy - r, cx + r, cy + r)
    return Path().apply {
        moveTo(tipX, tipY)
        lineTo(t1x, t1y)
        // 从 t1 切点起，扫过「背向尖端」的大弧 (2π − 2γ) 到另一切点，close 收回尖端 → 实心水滴。
        arcTo(bounds, (t1 * rad2deg).toFloat(), ((2 * PI - 2 * gamma) * rad2deg).toFloat(), forceMoveTo = false)
        close()
    }
}

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
 *
 * 双指缩放预览（[previewScale] != 1f）：draw 阶段做「真·焦点缩放」——**正文**绕双指焦点 ([previewFocalX],[previewFocalY])
 * 两轴缩放（焦点左侧正文左移滑到 gutter 后/移出、上方内容上移、焦点处停在指下）；**gutter/行号**横向钉左（只随字号缩放、
 * 不横移）、纵向与正文一致。字号未变、layout 未重排，Skia 按缩放后度量重栅格化 → 文字清晰、零重排；缩小时可见带外扩、
 * [firstVisibleLine] 传入外扩量补齐露出的行。**四方向就地钳制**（[contentHeightPx]/[bottomPaddingPx] 纵向、
 * [contentWidthPx]/[rightPaddingPx] 横向），与松手 commitZoom 的 re-clamp 同一连续式 ⇒ 预览即最终态、松手不跳（图片缩放模型）。
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
    firstVisibleLine: (Float) -> Int,
    lineTopPx: (Int) -> Float,
    refBaselinePx: Float,
    caretHandleVisible: () -> Boolean,
    handleRadiusPx: Float,
    layoutFor: (Int) -> TextLayoutResult?,
    charW: Float,
    isGridLine: (Int) -> Boolean,
    gridRefBaseline: Float,
    gridRefCursorTop: Float,
    gridRefCursorBottom: Float,
    previewScale: () -> Float = { 1f },
    previewFocalX: () -> Float = { 0f },
    previewFocalY: () -> Float = { 0f },
    contentHeightPx: () -> Float = { 0f },
    bottomPaddingPx: Float = 0f,
    contentWidthPx: () -> Float = { 0f },
    rightPaddingPx: Float = 0f,
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
        // 缩放预览也在 draw 阶段读：previewScale 每事件变、仅使本画布重绘（零重组/零重排）。scale==1 时下面全式退化为原状。
        val s = previewScale()
        val fy = previewFocalY()
        // 纵向变换：绕焦点缩放、上下都钳到 [0, s·内容高 − 视口高 + 底部留白]（与 maxScrollY 同式）。放大时焦点上方内容自由
        // 上移不锁顶、到底钳住不越界；预览即最终态 → 松手不跳。
        val vt = ZoomMath.previewVerticalTransform(sY, fy, s, size.height, contentHeightPx(), bottomPaddingPx)
        val vTranslate = vt[0] // 纵向平移（屏幕 px）：screenY = s·py + vTranslate
        val preTop = vt[1] // 可见带上界（预缩放屏幕 y）：缩小时 < 0，用作循环起点与可见判定基准
        val preBottom = vt[2] // 可见带下界：缩小时 > size.height，用作循环终止边界（over-draw）
        // 正文横向变换：绕焦点缩放、左右都钳到 [0, s·内容宽 − 视口宽 + 右侧留白]（与 maxScrollX 同式）。焦点左侧正文左移滑到
        // gutter 后/移出、到右缘钳住；换行由上层传 focalX=0 → 钉左。预览即最终态 → 松手不跳。
        val hTranslateText = ZoomMath.previewHorizontalTranslate(sX, previewFocalX(), s, size.width, contentWidthPx(), rightPaddingPx)
        // 当前行整行高亮的预缩放矩形：经横向变换 screenX=s·px+hTranslateText 后仍铺满视口宽 [0, size.width]——否则缩小(s<1)时
        // 直接用 size.width 会被 s 缩窄、右侧铺不满（「高亮不到底」）。s==1 时 hlLeft=0、hlWidth=size.width，与原状一致。
        val hlLeft = -hTranslateText / s
        val hlWidth = size.width / s
        val bufVersion = engine.buffer.version // 网格切片缓存键用；编辑变更即让旧切片失效
        val pinnedToScreen = lineNumberMode == LineNumberMode.PinnedToScreen
        // gutter 与行号的横向偏移：固定于屏幕钉在左侧（0）；固定于行随内容横移（sX），gutter 底色条连同行号
        // 一起向左滑出。两种模式都有区别于正文区的 gutter 底色（[colors.gutterBackground]）。
        val gutterScroll = if (pinnedToScreen) 0f else sX

        // 背景铺满整视口，**不参与缩放**——否则缩小预览时缩过的背景盖不满视口、露出上一帧/黑边。
        drawRect(colors.background, topLeft = Offset.Zero, size = size)

        // 逐行收集的行号（行, 预缩放屏幕顶 y）：正文块填充、gutter 块绘制（两块横向变换不同，故提到块外共享）。
        val deferredNumbers = ArrayList<Pair<Int, Float>>()

        // 正文/选区/预编辑/手柄：横向绕焦点（hTranslateText）+ 纵向绕焦点（vTranslate）→ screenX=s·px+hTranslateText、
        // screenY=s·py+vTranslate。放大时焦点左侧正文左移滑到 gutter 后、上方内容上移、焦点处停在指下。scale==1 且两平移=0 时恒等。
        withTransform({ translate(hTranslateText, vTranslate); scale(s, s, Offset.Zero) }) {
            val sel = engine.selection
            val comp = engine.composing
            val textX = gutterWidthPx + padXPx - sX
            val lineCount = engine.buffer.lineCount

            fun drawLineNumber(line: Int, top: Float) {
                deferredNumbers.add(line to top)
            }

            var line = firstVisibleLine(preTop).coerceIn(0, (lineCount - 1).coerceAtLeast(0))
            while (line < lineCount) {
                val top = lineTopPx(line) - sY
                if (top >= preBottom) break

                if (isGridLine(line)) {
                    // 超长网格行：只测量/绘制可见列窗口，几何用等宽算术（单视觉行，行高 = lineHeightPx）。
                    val h = lineHeightPx
                    if (top + h > preTop) {
                        val lineLen = engine.buffer.lineLength(line)
                        val textTop = top + (refBaselinePx - gridRefBaseline)
                        if (sel.isEmpty && sel.start.line == line) {
                            drawRect(colors.gutterBackground, topLeft = Offset(hlLeft, top), size = Size(hlWidth, h))
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
                        // 可见列窗口按预览缩放补偿：缩小(s<1)时屏上可见内容宽 = size.width/s（更宽），左缘内容 x 随焦点缩放偏移
                        // hTranslateText/s；否则切片覆盖不到缩小后新露出的右侧列 → 长行右段缺失。s==1 退化为原状（仅切文本区）。
                        val cols = if (s == 1f) {
                            EditorGeometry.gridVisibleColumns(sX, (size.width - gutterWidthPx - padXPx * 2).coerceAtLeast(1f), charW, lineLen)
                        } else {
                            EditorGeometry.gridVisibleColumns(sX - hTranslateText / s, size.width / s, charW, lineLen)
                        }
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
                if (top + h > preTop) {
                    // 当前行淡色高亮（无选择时）：铺满整行宽（含 gutter 区）。固定模式下左段会被文末 gutter 条盖住、
                    // 视觉不变；跟随模式下 gutter 条随内容滚走，整行仍均匀高亮、左侧不留缺口。
                    if (sel.isEmpty && sel.start.line == line) {
                        drawRect(colors.gutterBackground, topLeft = Offset(hlLeft, top), size = Size(hlWidth, h))
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

            // 光标不在此画：拆到独立的 [CursorOverlay] 图层，闪烁只切该层 alpha、不重放整块正文画布（见 P10）。

            // 拖动手柄（泪滴）：尖端贴光标底、body 圆朝外下方悬挂作抓取区。选区两端常驻，光标手柄按 caretHandleVisible 控制。
            // 网格行用等宽算术定光标矩形，其余走该行 layout。缩放预览期（s != 1f）隐藏——避免手柄随内容缩放显得突兀，
            // 松手提交后按新布局重新出现，位置正确。命中盒仍由 EditorGeometry.handleGeometry（上层命中侧）给出、覆盖此泪滴。
            fun drawHandle(kind: HandleKind, pos: TextPosition) {
                val caretLeft: Float
                val crBottomY: Float
                if (isGridLine(pos.line)) {
                    val col = pos.column.coerceIn(0, engine.buffer.lineLength(pos.line))
                    val base = lineTopPx(pos.line) - sY + (refBaselinePx - gridRefBaseline)
                    caretLeft = textX + col * charW
                    crBottomY = base + gridRefCursorBottom
                } else {
                    val layout = layoutFor(pos.line) ?: return
                    val cr = layout.getCursorRect(pos.column.coerceIn(0, engine.buffer.lineLength(pos.line)))
                    val base = lineTopPx(pos.line) - sY + (refBaselinePx - layout.firstBaseline)
                    caretLeft = textX + cr.left
                    crBottomY = base + cr.bottom
                }
                // 尖端在光标底 (caretLeft, crBottomY)；body 圆按 kind 朝外下方悬挂：光标↓、选区起点↙、选区终点↘。
                val diag = 0.70710677f
                val (ux, uy) = when (kind) {
                    HandleKind.Caret -> 0f to 1f
                    HandleKind.SelectionStart -> -diag to diag
                    HandleKind.SelectionEnd -> diag to diag
                }
                drawPath(buildTeardropPath(caretLeft, crBottomY, ux, uy, handleRadiusPx, handleRadiusPx * 1.4f), colors.handle)
            }
            if (s == 1f) {
                if (!sel.isEmpty) {
                    drawHandle(HandleKind.SelectionStart, sel.start)
                    drawHandle(HandleKind.SelectionEnd, sel.end)
                } else if (caretHandleVisible()) {
                    drawHandle(HandleKind.Caret, sel.start)
                }
            }
        }

        // gutter 不透明条 + 行号：横向**钉左**（hTranslate=0，不随焦点横移；正文/光标/手柄画完后铺，盖住横向滚进来的正文）、
        // 纵向与正文一致（vTranslate）→ 数字与各行对齐、任意横滚下清晰，gutter 底色区别于正文区。缩放预览下条高覆盖整条
        // 可见带 [preTop, preBottom]（缩放后恰为视口全高），行号在本块内随字号缩放、与正文行对齐。
        withTransform({ translate(0f, vTranslate); scale(s, s, Offset.Zero) }) {
            drawRect(colors.gutterBackground, topLeft = Offset(-gutterScroll, preTop), size = Size(gutterWidthPx, preBottom - preTop))
            for ((line, top) in deferredNumbers) {
                val num = numberLayoutCache.getOrPut(line) { textMeasurer.measure((line + 1).toString(), numberStyle) }
                val numTop = top + (refBaselinePx - num.firstBaseline)
                drawText(num, color = colors.gutterForeground, topLeft = Offset(gutterWidthPx - padXPx - num.size.width - gutterScroll, numTop))
            }
        }
    }
}

/**
 * 只画光标线的独立图层，叠在 [EditorCanvas] 之上。光标位置随滚动 / 光标移动在 draw 阶段实时计算（仅这根细线
 * 重绘）；闪烁 [caretVisible]（= blink）在 graphicsLayer 的 alpha 里读取——每 500ms 翻转只更新该层 alpha
 * （layer 属性 / 合成），不重放正文画布整块的 drawText / gutter / 行号。无选择时才画（有选择时画布画选区、不画光标）。
 * 坐标算法与移除前 EditorCanvas 内的光标段完全一致。
 *
 * 双指缩放预览（[previewScale] != 1f）：本层不参与正文的缩放变换，若照旧画光标会与缩放中的正文脱节，故预览期
 * 直接不画；松手提交后按新布局重新出现，位置正确。
 */
@Composable
fun CursorOverlay(
    engine: EditorEngine,
    colors: EditorColors,
    caretVisible: () -> Boolean,
    scrollX: () -> Float,
    scrollY: () -> Float,
    lineTopPx: (Int) -> Float,
    refBaselinePx: Float,
    layoutFor: (Int) -> TextLayoutResult?,
    charW: Float,
    isGridLine: (Int) -> Boolean,
    gridRefBaseline: Float,
    gridRefCursorTop: Float,
    gridRefCursorBottom: Float,
    gutterWidthPx: Float,
    padXPx: Float,
    previewScale: () -> Float = { 1f },
    modifier: Modifier = Modifier,
) {
    Canvas(modifier.graphicsLayer { alpha = if (caretVisible()) 1f else 0f }) {
        if (previewScale() != 1f) return@Canvas // 缩放预览期不画光标：正文在缩放、光标会脱节
        val sel = engine.selection
        if (!sel.isEmpty) return@Canvas // 有选择时不画光标：画布已画选区覆盖层
        val sX = scrollX()
        val sY = scrollY()
        val textX = gutterWidthPx + padXPx - sX
        val cLine = sel.start.line
        val col = sel.start.column.coerceIn(0, engine.buffer.lineLength(cLine))
        if (isGridLine(cLine)) {
            val textTop = lineTopPx(cLine) - sY + (refBaselinePx - gridRefBaseline)
            val x = textX + col * charW
            drawLine(colors.cursor, Offset(x, textTop + gridRefCursorTop + 1f), Offset(x, textTop + gridRefCursorBottom - 1f), strokeWidth = 2.5f)
        } else {
            val layout = layoutFor(cLine) ?: return@Canvas
            val textTop = lineTopPx(cLine) - sY + (refBaselinePx - layout.firstBaseline)
            val cr = layout.getCursorRect(col)
            drawLine(colors.cursor, Offset(textX + cr.left, textTop + cr.top + 1f), Offset(textX + cr.left, textTop + cr.bottom - 1f), strokeWidth = 2.5f)
        }
    }
}
