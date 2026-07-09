package top.yukonga.scripta.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.gestures.rememberScrollable2DState
import androidx.compose.foundation.gestures.scrollable2D
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.overscroll
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextMotion
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import top.yukonga.scripta.editor.input.editorTextInput
import top.yukonga.scripta.editor.input.insertTypedCharacter
import top.yukonga.scripta.editor.input.plainText
import top.yukonga.scripta.editor.input.plainTextClipEntry
import top.yukonga.scripta.editor.render.CursorOverlay
import top.yukonga.scripta.editor.render.EditorCanvas
import top.yukonga.scripta.editor.render.EditorGeometry
import top.yukonga.scripta.editor.render.HandleKind
import top.yukonga.scripta.editor.render.MagnifierOverlay
import top.yukonga.scripta.editor.render.VisualRowIndex
import top.yukonga.scripta.editor.render.ZoomMath
import top.yukonga.scripta.editor.text.TextPosition
import top.yukonga.scripta.editor.text.TextRange
import kotlin.math.abs
import kotlin.time.Duration.Companion.milliseconds

/** 超过此字符数的行走「网格」快路：不整行 shaping，只按可见列窗口等宽算术定位/绘制（M2，仅不换行）。 */
private const val LONG_LINE_THRESHOLD = 2000

/**
 * 双指缩放提交的最小字号变化阈值（sp）：净变化小于此值视作未真正缩放、不重排。只是滤掉双指微动噪声（避免无谓重排
 * 与非整字号漂移）；不吸附提交值——提交字号恒为精确的 `起始字号 × 连续系数`，与预览末帧完全连续、无提交跳变。
 * （旧版曾量化到 0.5sp 档以压制「手势中逐档重排」；现只在松手重排一次、无此性能压力，量化已无必要且会引入提交吸附跳变。）
 */
private const val ZOOM_MIN_CHANGE_SP = 0.1f

/** 双指缩放字号下限 / 上限（sp）。手势中连续缩放系数被夹到使字号落在此区间，提交后字号亦钳于此。 */
private const val ZOOM_MIN_SP = 8f
private const val ZOOM_MAX_SP = 40f

/**
 * 布局基准字号与派生量比例：行号字号 = 正文 × [NUMBER_FONT_SCALE]（默认 14sp→13sp）；横向内边距
 * padX = 正文 / [LAYOUT_BASE_FONT_SP] × [PAD_X_BASE_DP]（默认 14sp→8dp）。**关键**：用「比例」而非「常量偏移 / 固定 dp」，
 * 使 gutter 宽、内边距随字号线性缩放，整套布局在缩放下「自相似」——双指缩放预览（整体等比缩放）与松手重排（按新字号
 * 重新布局）产生一致几何，行号/正文在提交时不跳变、无重绘；不换行提交横向亦简化为精确的 sX'=k·sX。
 */
private const val LAYOUT_BASE_FONT_SP = 14f
private const val NUMBER_FONT_SCALE = 13f / 14f
private const val PAD_X_BASE_DP = 8f

/**
 * 底部可滚过文末的留白（视口高比例）：允许主动滚动把末行上移、下方留空，不把末行钉死底边。
 * 双上界避免历史坑：留白只并进「主动滚动/渲染/命中」用的 maxScrollY；所有**自动 settle**（IME 收键盘 re-clamp、
 * keep-in-view、缩放提交的 re-clamp）走不含留白的 maxScrollYSettle（自然底、末行贴底）。否则底部弹键盘把 scrollY
 * 推大、收键盘后 re-clamp 夹到含留白上界，会在末行下方残留一块空白。0.2 = 末行最多上移到距底 1/5 视口。
 */
private const val BOTTOM_SCROLL_PAD_FRACTION = 0.2f

/**
 * layoutFor 逐行缓存条目。[validatedVersion] 记「上次确认与文档一致时的 buffer.version」：命中且版本相等即
 * 说明此后无任何编辑、本行文本必与 [content] 相同，可零分配零比较直接复用 [layout]（稳态滚动/闪烁/拖选帧全
 * 走这里）；版本变了才回退按 [content] 比对——编辑他行不改本行文本，仍命中并刷新版本戳，唯内容不符才重测。
 */
private class CachedLayout(var validatedVersion: Int, val content: String, val layout: TextLayoutResult)

/**
 * isNarrowLine 逐行缓存条目（与 [CachedLayout] 同法失效，但只存「长度+hash 指纹」而非整行内容——网格路专治
 * 超长行，把整串钉在缓存里会为单条巨行长期占用兆字节）。[narrow] = 该行是否全为可打印 ASCII（等宽半角），
 * 即能否走网格快路；含 CJK/全角/emoji 等非等宽字形时为 false，令该长行退回整行 shaping。
 */
private class NarrowFlag(var validatedVersion: Int, val length: Int, val hash: Int, val narrow: Boolean)

/** 网格快路要求的「窄行」判定：全部为可打印 ASCII（U+0020..U+007E，等宽半角）。含任何 CJK/全角/emoji/制表符
 *  等宽度≠charW 或竖直度量异于 ASCII 的字符即返回 false——保守但安全，误判只是让该长行退回 shaping、不影响正确性。 */
private fun isNarrowGridText(s: String): Boolean = s.all { it.code in 0x20..0x7E }

/**
 * 虚拟化代码编辑器入口。自绘 + 视口虚拟化 + 自管 IME（Android），不使用 BasicTextField。
 *
 * **底部系统栏（导航栏 / 键盘）由编辑器内部消费**，宿主不要再对编辑器加 `imePadding()`/导航栏 padding
 * （否则重复让位、且底色无法铺进小白条区）——宿主只需给它 `weight`/尺寸即可。内部据是否显示符号条决定谁
 * 让开系统栏：显示时符号条背景铺到屏幕边缘、键抬到栏上；隐藏时文本区让开系统栏、编辑器底色铺到边缘。
 * 这样可用视口高度始终反映键盘弹出后的可见高度，光标随动不会滚到键盘下面。
 *
 * [symbols] 非空且非只读时在底部常驻一条横向可滚的符号快捷条，文本视口相应缩小、文本浮于其上。
 * 传 `emptyList()` 关闭；只读时不显示。
 */
@Composable
fun CodeEditor(
    controller: CodeEditorController,
    initialText: String,
    modifier: Modifier = Modifier,
    language: EditorLanguage = EditorLanguage.PlainText,
    colors: EditorColors = EditorColors.Default,
    readOnly: Boolean = false,
    softWrap: Boolean = false,
    lineNumberMode: LineNumberMode = LineNumberMode.PinnedToScreen,
    symbols: List<EditorSymbol> = DefaultEditorSymbols,
) {
    val engine = controller.engine
    LaunchedEffect(initialText) { controller.setText(initialText) }

    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val focusRequester = remember { FocusRequester() }
    val interaction = remember { MutableInteractionSource() }
    // 新 Clipboard 的收发是 suspend，需一个组合级作用域来跑复制/剪切/粘贴。
    val clipboard = LocalClipboard.current
    val clipboardScope = rememberCoroutineScope()
    val haptic = LocalHapticFeedback.current
    // 触边拉伸/回弹反馈（平台不支持时返回 null，自然退化为无）。
    val overscroll = rememberOverscrollEffect()

    // 双指缩放调整的字号（sp）。行高、gutter、layout 随之联动重算。整个缩放手势里字号**不变**，松手才由
    // commitZoom 换一次字号（见下方缩放 pointerInput）——手势期只动下面的 previewScale，零重排。
    var fontSizeSp by remember { mutableFloatStateOf(14f) }
    // 手势期连续缩放系数（相对手势起始字号），**仅在 draw 阶段读**（传给 EditorCanvas/CursorOverlay 的 provider）：
    // 每指针事件写它只使画布重绘、不触发重组/重排。恒 1f 表示非缩放态；松手 commitZoom 后归 1、换成真实字号。
    var previewScale by remember { mutableFloatStateOf(1f) }
    // 缩放预览的 draw 阶段平移（屏幕 px），与 previewScale 组成运行态仿射：screenX = s·px + previewTx、screenY = s·py + previewTy
    // （px/py 为相对当前 scroll 的内容坐标）。手势逐帧按「绕当前双指中点缩放 + 中点位移平移」增量累积（T' = ez·T + (c − ez·prevC)）
    // → 四向跟手；恒 (1,0,0) 表非缩放态。松手 commitZoom 把 (s, tx, ty) 折进真实 scroll（不换行自相似精确、换行走顶部锚）。换行下
    // 横向钉左（previewTx 恒 0）。**仅 draw 阶段读**，写它只重绘不重组/重排。
    var previewTx by remember { mutableFloatStateOf(0f) }
    var previewTy by remember { mutableFloatStateOf(0f) }
    // 换行提交的顶部锚字符：换行重排非自相似、不能靠 tx/ty 折算，改锚「预览末帧屏幕顶处」的文档字符，由 LaunchedEffect(fontSizeSp) 重排后放回顶。
    var zoomWrapAnchorPos by remember { mutableStateOf<TextPosition?>(null) }
    val lineHeightSp = fontSizeSp * 1.5f
    // trim = None 让 lineHeight 对「单行」也生效，否则默认 Trim.Both 会让单行退回字体自然高度，
    // 中文回退字体度量更大 -> 含中文的行更高、错位。Center 让内容在统一行高内居中。
    val lineHeightStyle = remember {
        LineHeightStyle(alignment = LineHeightStyle.Alignment.Center, trim = LineHeightStyle.Trim.None)
    }
    // Android 关闭 includeFontPadding，消除 CJK 回退字体导致的行内英文基线下偏。
    val platformTextStyle = remember { editorNoFontPaddingStyle() }
    val textStyle = remember(colors, fontSizeSp) {
        TextStyle(
            color = colors.foreground,
            fontFamily = FontFamily.Monospace,
            fontSize = fontSizeSp.sp,
            lineHeight = lineHeightSp.sp,
            lineHeightStyle = lineHeightStyle,
            // 线性字体度量 + 亚像素定位（去 hinting/网格拟合）：字形在不同字号间严格等比 → 双指缩放「缩放旧字形(预览)」与
            // 「原生新字形(提交)」逐像素一致，消除松手时的亚像素左右吸附。代价：静态小字号略软（高 DPI 上可接受）。
            textMotion = TextMotion.Animated,
            platformStyle = platformTextStyle
        )
    }
    val numberStyle = remember(colors, fontSizeSp) {
        TextStyle(
            color = colors.gutterForeground,
            fontFamily = FontFamily.Monospace,
            // 行号字号取正文的固定比例（而非 -1 常量偏移）：使 gutter 宽随字号线性缩放、布局自相似，缩放提交时行号不跳。
            fontSize = (fontSizeSp * NUMBER_FONT_SCALE).coerceAtLeast(6f).sp,
            lineHeight = lineHeightSp.sp,
            lineHeightStyle = lineHeightStyle,
            textMotion = TextMotion.Animated, // 同正文：线性度量 + 亚像素，行号缩放时也不抖
            platformStyle = platformTextStyle
        )
    }
    val lineHeightPx = with(density) { lineHeightSp.sp.toPx() }
    // padX 随字号成比例（默认 14sp→8dp），使内边距与 gutter 宽在缩放下自相似——预览等比缩放与松手重排几何一致、行号不跳。
    val padXPx = with(density) { (PAD_X_BASE_DP * fontSizeSp / LAYOUT_BASE_FONT_SP).dp.toPx() }
    // 拖动手柄半径与命中外扩（细光标线太小，靠 slop 放大可抓区域）。
    val handleRadiusPx = with(density) { 8.dp.toPx() }
    val handleSlopPx = with(density) { 10.dp.toPx() }
    // 统一基线：各行把字母基线钉到「含 CJK 参考行」的 firstBaseline（参考串须含 CJK）。等于每行都预留 CJK 竖直度量：
    // 英数位置恒定（加删中文不跳）、CJK 落在自然偏下位置不上飘。绘制顶 = 行顶 + (refBaselinePx − 本行 firstBaseline)。
    val refBaselinePx = remember(textStyle) { measurer.measure("Ag中", textStyle).firstBaseline }

    // M2：超长「网格行」判定 + 等宽字符宽。超阈值的行不整行 shaping，只按可见列窗口做等宽算术定位/绘制
    // （仅不换行；softWrap 下仍走整行折行测量）。gridRef = 参考字符 "0" 的 layout：垂直度量复用其 cursorRect(0)。
    // charW 取其**精确子像素 advance**（末端光标 x = cursorRect(1).left），不用 size.width——那是整数取整的盒宽。
    // 网格行用 col*charW 定位光标/命中/切片原点，而切片文本按真实 advance 排布；用取整宽会逐列累积漂移
    // （每字差零点几像素），长行右段光标与字形明显错位、点选/编辑落到隔壁列。取真实 advance 后三者与字形同尺。
    val gridRef = remember(textStyle) { measurer.measure("0", textStyle) }
    val charWpx = remember(gridRef) { gridRef.getCursorRect(1).left }
    val gridRefBaseline = gridRef.firstBaseline
    val gridRefCursor = remember(gridRef) { gridRef.getCursorRect(0) }
    // 网格快路仅适用「窄行」（全为可打印 ASCII、等宽半角）：其等宽算术 col*charW 与 ASCII 基线 gridRefBaseline
    // 只有在此前提下才成立。含 CJK/全角/emoji 的长行——字形非等宽（横向漂移）、度量非 ASCII（竖直错位）——据此
    // 退回整行 shaping（layoutFor），走与普通行相同的精确 layout（光标/命中/CJK 基线对齐都对）。是否窄行按行缓存、
    // version 未变即命中；编辑后按「长度+hash 指纹」重校（不把整行内容留在缓存里）。含 CJK 的超长行退回 shaping 会整行重测，属罕见代价。
    val narrowCache = remember { LruCache<Int, NarrowFlag>(256) }
    fun isNarrowLine(line: Int): Boolean {
        val version = engine.buffer.version
        val cached = narrowCache[line]
        if (cached != null && cached.validatedVersion == version) return cached.narrow
        val content = engine.buffer.lineText(line)
        // 指纹（长度+hash）相符即视作未变、刷新版本戳复用结果：省下把整行内容长期钉在缓存里。同长且同 hash 的
        // 不同内容概率可忽略，即便撞上也只是本行一次错判、下次真实编辑（指纹必变）即纠正。
        if (cached != null && cached.length == content.length && cached.hash == content.hashCode()) {
            cached.validatedVersion = version
            return cached.narrow
        }
        val narrow = isNarrowGridText(content)
        narrowCache[line] = NarrowFlag(version, content.length, content.hashCode(), narrow)
        return narrow
    }

    // 长度先短路：短行不进 isNarrowLine、不触发整行读取，只有超阈值的长行才判窄。
    fun isGridLine(line: Int): Boolean =
        !softWrap && charWpx > 0f && engine.buffer.lineLength(line) > LONG_LINE_THRESHOLD && isNarrowLine(line)

    // 读取 buffer.version（快照 state）订阅编辑：内容/行数变化后整体重组，gutter 宽、内容高、可见窗口随之
    // 刷新。此读取本身即订阅，勿删。逐行 layout 缓存不再以它失效（否则每键整表重测），失效见 layoutFor。
    engine.buffer.version
    val lineCount = engine.buffer.lineCount

    val gutterDigits = EditorGeometry.gutterDigits(lineCount)
    val gutterWidthPx = remember(gutterDigits, numberStyle) {
        measurer.measure("0".repeat(gutterDigits), numberStyle).size.width + padXPx * 2
    }

    // 每帧高频写读的量用 primitive FloatState，避免装箱 Float（滚动/拖拽每帧几个装箱分配纯属浪费）。
    var scrollY by remember { mutableFloatStateOf(0f) }
    var scrollX by remember { mutableFloatStateOf(0f) }
    var viewportWidth by remember { mutableFloatStateOf(0f) }
    var viewportHeight by remember { mutableFloatStateOf(0f) }
    // 编辑器内容区顶边在窗口中的 y（放大镜 Popup 用它把胶囊上浮到工具栏/状态栏空间、不越出窗口顶）。IME/旋转会变，实时更新。
    var contentTopInWindow by remember { mutableFloatStateOf(0f) }

    // 换行模式下正文可用宽度（测量宽度约束）。
    val textAreaWidthPx = (viewportWidth - gutterWidthPx - padXPx * 2).coerceAtLeast(1f)
    val widthBucket = if (softWrap) textAreaWidthPx.toInt() else 0

    // 视觉行索引：仅换行模式使用。不换行模式 lineTopPx/lineAtPx 走平凡公式、从不读它，故固定大小 1、不随行数重建。
    // 换行模式按 (行数/宽度) 重建，但**不以字号为 key**——否则双指缩放每过一档就 O(n) 重建 IntArray(n)+buildTree，
    // 超大 softWrap 文件缩放会剧烈掉帧。字号变时保留本索引：可见行经 layoutFor 在新字号下重测并 setRows 增量更新
    // （O(log n)/行、有界），视口外行暂留旧字号行数作估算、滚动时自然收敛——即「换行下缩放不逐帧全量重折、只增量」。
    // 行数变化（回车）仍整表重建，待引擎暴露编辑 delta 后做增量 splice（M5）。
    val rowIndexSize = if (softWrap) lineCount else 1
    val rowIndex = remember(softWrap, widthBucket, rowIndexSize) { VisualRowIndex(rowIndexSize) }

    // 逐行 layout 缓存：只在宽度/模式/字号变化时整表失效，不再以 version 失效——否则每敲一个字符整表丢弃、
    // 可见行全部重测。失效改由下方按行内容比对精确处理（内容变了才重测；插入/删除行的下标平移也会因内容
    // 不符自然重测）。有界 LRU：超上限淘汰最久未用，而非整表 clear() 把可见行一并丢弃。
    val layoutCache = remember(softWrap, widthBucket, fontSizeSp) { LruCache<Int, CachedLayout>(4096) }
    fun layoutFor(line: Int): TextLayoutResult? {
        if (line < 0 || line >= engine.buffer.lineCount) return null
        if (isGridLine(line)) return null // 网格行不整行测量：绘制走可见列切片（EditorCanvas），几何走等宽算术
        val version = engine.buffer.version
        val cached = layoutCache[line]
        // 快路：version 未变说明自上次校验以来无编辑 → 行文本必然未变，跳过 lineText 物化 + O(len) 比较，零分配。
        if (cached != null && cached.validatedVersion == version) {
            // 命中也回填视觉行数：rowIndex 仍以 lineCount 为 key、行数变化时会重建为 1 行估算，而命中
            // 分支不经下面的 setRows，需在此补上，否则软换行的行高/定位会退回估算值。
            if (softWrap) rowIndex.setRows(line, cached.layout.lineCount)
            return cached.layout
        }
        // version 变了（发生过编辑）：按内容精确校验。lineText 至多取一次。编辑他行不改本行文本 → 内容仍相符，
        // 刷新版本戳复用 layout；内容不符（本行被编辑，或插入/删除行的下标平移到别的行）才真正重测。
        val content = engine.buffer.lineText(line)
        if (cached != null && cached.content == content) {
            cached.validatedVersion = version
            if (softWrap) rowIndex.setRows(line, cached.layout.lineCount)
            return cached.layout
        }
        val measured = if (softWrap) {
            measurer.measure(
                content,
                textStyle,
                softWrap = true,
                constraints = Constraints(maxWidth = textAreaWidthPx.toInt().coerceAtLeast(1))
            )
        } else {
            measurer.measure(content, textStyle, softWrap = false)
        }
        layoutCache[line] = CachedLayout(version, content, measured)
        if (softWrap) rowIndex.setRows(line, measured.lineCount)
        return measured
    }

    // 行 -> 顶部像素 / 像素 -> 行（换行走视觉行索引，不换行走平凡公式，行为不变）。
    fun lineTopPx(line: Int): Float = if (softWrap) rowIndex.rowsBefore(line) * lineHeightPx else line * lineHeightPx
    fun lineAtPx(y: Float): Int {
        val row = (y / lineHeightPx).toInt().coerceAtLeast(0)
        return if (softWrap) rowIndex.lineAtRow(row) else row.coerceIn(0, (lineCount - 1).coerceAtLeast(0))
    }

    // 量化滚动：组合只订阅「首个可见行」，跨行才重组；同一行内的滚动仅由 EditorCanvas 在 draw 阶段读
    // 像素级 scrollY/scrollX 平滑重绘，不再每滚 1px 全量重组。derivedStateOf 仅在整数结果变化时通知读者。
    // lineCount 必须作 key：derivedStateOf 的 lineAtPx 闭包捕获 lineCount 做 coerceIn 上界，行数变化（尤其
    // 初始 setText 把 1→N）时须重建闭包，否则捕获旧上界（如 1）会把 scrollLine 永远钳到 0、预测量窗口不前移，
    // 靠下的长/网格行不计入 widestSeen、横向滚动范围被卡死（不换行模式下 rowIndex 恒定、不再连带触发重建）。
    val scrollLine by remember(rowIndex, lineHeightPx, softWrap, lineCount) { derivedStateOf { lineAtPx(scrollY) } }

    // 预测量可见窗口（组合阶段填充缓存 + 行索引），并求不换行下最宽行以定横向滚动范围。
    val firstVisibleLine = (scrollLine - 3).coerceAtLeast(0)
    val approxRows = (viewportHeight / lineHeightPx).toInt() + 8
    val measureEnd = (firstVisibleLine + approxRows).coerceAtMost((lineCount - 1).coerceAtLeast(0))
    // 横向范围以「已测量过的最宽行」为准、只增不减（长行纵向滚出可见区后 maxScrollX 不骤缩、不把 scrollX 夹回，视口不瞬移；
    // 同 Monaco 维护 longest line）。非 state，写读均在组合内、不触发重组。engine.contentGeneration 作 key：换文档时重置。
    // **不以 fontSizeSp 为 key**：否则双指缩放换字号会 remember 重置、只从「当前可见行」重填——若最宽行（超长网格行）此刻已滚出
    // 视口，widestSeen 骤缩为可见短行、maxScrollX 崩塌 → 缩放松手时 scrollX 被夹到可见行右缘、横向大跳。改为跨字号保留：
    // widestSeen[1] 记上次测量字号，字号变时把宽度 [0] 按比例缩放（等宽 advance / layout 宽 ∝ 字号），随后可见行只增补。
    val widestSeen = remember(softWrap, widthBucket, engine.contentGeneration) { floatArrayOf(0f, fontSizeSp) }
    if (widestSeen[1] != fontSizeSp) {
        if (widestSeen[1] > 0f) widestSeen[0] *= fontSizeSp / widestSeen[1]
        widestSeen[1] = fontSizeSp
    }
    for (ln in firstVisibleLine..measureEnd) {
        if (isGridLine(ln)) {
            val w = engine.buffer.lineLength(ln) * charWpx // 网格行宽度算术得出，不整行测量
            if (w > widestSeen[0]) widestSeen[0] = w
        } else {
            val l = layoutFor(ln)
            if (!softWrap) {
                val w = l?.size?.width?.toFloat() ?: 0f
                if (w > widestSeen[0]) widestSeen[0] = w
            }
        }
    }

    val contentHeight = (if (softWrap) rowIndex.totalRows() else lineCount) * lineHeightPx
    // 两个纵向上界（见 [BOTTOM_SCROLL_PAD_FRACTION]）：maxScrollY 含底部留白，供**主动滚动/渲染/命中/缩放预览**
    // （可滚过文末、末行上移下方留空）；maxScrollYSettle 是不含留白的自然底（末行贴底），仅供**视口变大时的 settle**
    // （收键盘/转屏变高）夹回贴底、不残留留白。留白用连续式 `(内容高−视口+留白).coerceAtLeast(0)`（非「溢出才加」的分段）：
    // 临界字号平滑、焦点滚动量落钳制内不被夹回。缩放预览的纵向上界同样含留白（与提交 re-clamp 同式 ⇒ 松手不跳、起手不 snap）。
    val bottomScrollPadPx = viewportHeight * BOTTOM_SCROLL_PAD_FRACTION
    val maxScrollY = (contentHeight - viewportHeight + bottomScrollPadPx).coerceAtLeast(0f)
    val maxScrollYSettle = (contentHeight - viewportHeight).coerceAtLeast(0f)
    val maxScrollX = if (softWrap) 0f else (gutterWidthPx + padXPx * 2 + widestSeen[0] - viewportWidth).coerceAtLeast(0f)

    // 视口变大 / 内容变短使 maxScroll 缩小时，把滚动量夹回范围内。否则在文档底部弹出输入法（视口被 imePadding
    // 压小、scrollY 被 keep-in-view 推大）后收起输入法，视口复原、maxScrollY 骤减，而 scrollY 残留旧大值：
    // firstVisibleLine 读未夹的 scrollY 会指到更靠下的行，绘制偏移却读已夹的 scrollY，二者错位 → 上方留空白。
    // 视口「变大」（收键盘 / 转屏变高）→ 夹到自然底 maxScrollYSettle（末行贴底、不残留留白，修 IME 收键盘留白）；
    // 其余（缩放/编辑改内容高、弹键盘缩小视口）→ 夹到含留白的 maxScrollY，**缩放/编辑不夹掉主动滚入的底部留白、不跳**。
    // 按「上一帧视口高」判定「变大」，确定性、不依赖 effect 执行顺序。主动滚动不改这些 key、不触发本效应，留白得以保持。
    val prevViewportH = remember { floatArrayOf(0f) }
    LaunchedEffect(maxScrollY, maxScrollX, viewportHeight) {
        val grew = viewportHeight > prevViewportH[0]
        prevViewportH[0] = viewportHeight
        scrollY = scrollY.coerceIn(0f, if (grew) maxScrollYSettle else maxScrollY)
        scrollX = scrollX.coerceIn(0f, maxScrollX)
    }

    // 换行下缩放的顶部锚定精确校正：字号现每手势只在 commitZoom 变一次 → 本效应每手势只触发一次。commitZoom 已同步置好近似
    // scrollY（提交帧≈预览末帧）；本效应在可见行按新字号重排后，用新布局把「锚字符所在折行」精确放回视口顶 (y=0)——顶部稳定、
    // 下方重排（比锚焦点字符可预测，焦点在长折行里的新位置难料、易 settle）。k 公式对换行非均匀折行不成立，故换行走「顶部字符
    // 锚定 + post-layout 校正」。声明在 re-clamp 效应之后 → 同帧内 scrollY 以本效应为准。不换行 scrollY 已由 k 公式精确设好、不进本效应。
    LaunchedEffect(fontSizeSp) {
        if (!softWrap) return@LaunchedEffect
        val ap = zoomWrapAnchorPos ?: return@LaunchedEffect
        val layout = layoutFor(ap.line) ?: return@LaunchedEffect
        val col = ap.column.coerceIn(0, engine.buffer.lineLength(ap.line))
        val vr = layout.getLineForOffset(col) // 锚字符所在视觉行（新字号 layout）
        val anchorRowTopY = lineTopPx(ap.line) + layout.getLineTop(vr) // 该折行顶在内容坐标里的 y
        scrollY = anchorRowTopY.coerceIn(0f, maxScrollY) // 回到视口顶 (screenY=0)；上界含留白，缩放不夹掉主动滚入的留白
    }

    // 二维自由平移：跟手拖动时横纵可斜向同时滚（而非被锁在单轴——两个正交的单轴 scrollable 会在拖动起始按
    // 方向锁定其一）；但松手 fling 惯性时锁到主导轴（垂直或水平），避免斜向漂移。用独立 interactionSource 追踪
    // 「是否正在拖动」：DragInteraction 期间为跟手，其后由 fling 驱动的回调即惯性阶段。holder 用普通数组（非
    // State）避免每帧重组——滚动回调与 collect 都在 UI 线程、无并发。
    val scrollInteraction = remember { MutableInteractionSource() }
    val draggingHolder = remember { booleanArrayOf(false) }
    val flingAxis = remember { intArrayOf(0) } // 0=未定, 1=纵, 2=横；每次新拖动清零，fling 首帧按速度方向锁定
    LaunchedEffect(scrollInteraction) {
        scrollInteraction.interactions.collect { i ->
            draggingHolder[0] = when (i) {
                is DragInteraction.Start -> true
                is DragInteraction.Stop, is DragInteraction.Cancel -> false
                else -> draggingHolder[0]
            }
        }
    }
    val scroll2D = rememberScrollable2DState { delta ->
        if (draggingHolder[0]) {
            // 跟手：横纵自由平移。每帧清 flingAxis，松手后由 fling 首帧重新按速度方向锁轴。
            flingAxis[0] = 0
            val cx = (scrollX - delta.x).coerceIn(0f, maxScrollX)
            val cy = (scrollY - delta.y).coerceIn(0f, maxScrollY)
            val consumed = Offset(scrollX - cx, scrollY - cy)
            scrollX = cx; scrollY = cy
            consumed
        } else {
            // fling：首帧 delta 方向≈松手速度方向，据 |dx| vs |dy| 锁主轴、之后固定；次轴 delta 声称消费但不移动，
            // 既不斜向漂移、也不触发次轴 overscroll 发光。主轴撞边时未消费部分交 overscroll，正常显示到底反馈。
            if (flingAxis[0] == 0) flingAxis[0] = if (abs(delta.y) >= abs(delta.x)) 1 else 2
            if (flingAxis[0] == 1) {
                val cy = (scrollY - delta.y).coerceIn(0f, maxScrollY)
                val consumedY = scrollY - cy
                scrollY = cy
                Offset(delta.x, consumedY)
            } else {
                val cx = (scrollX - delta.x).coerceIn(0f, maxScrollX)
                val consumedX = scrollX - cx
                scrollX = cx
                Offset(consumedX, delta.y)
            }
        }
    }

    // 选区拖拽状态（提升到组合级，供边缘自动滚动 effect 读取）。
    // selectionAnchor：字符级锚（手柄端点拖拽）；selectionWordAnchor：词级锚（长按选词后按词扩展）。
    var selectionAnchor by remember { mutableStateOf<TextPosition?>(null) }
    var selectionWordAnchor by remember { mutableStateOf<TextRange?>(null) }
    // 逐帧写入的拖拽坐标：只被 effect 协程体轮询，组合/绘制均不读——故写它不触发任何重组。
    var selectionDragPos by remember { mutableStateOf<Offset?>(null) }
    // 只在拖拽「起/止」翻转的布尔，专作边缘自动滚动 effect 的 key。不能用 `selectionDragPos != null` 作 key：
    // 那会在组合期读逐帧写入的 selectionDragPos，使拖选的每次移动都整体重组（P1）。
    var selectionDragActive by remember { mutableStateOf(false) }
    // 光标手柄拖拽的边缘自动滚动状态（与选区同机制，但落的是光标而非选区）：手柄拖到视口边缘热区时持续滚动、
    // 让光标能拖到当前不可见的内容处（否则光标被 positionAt 钳在可见带内、拖不过边）。caretDragPos 逐帧写、只被
    // effect 轮询（组合/绘制不读，写它不重组），已含手柄纵向抓取偏移 grabDy。caretDragActive 仅起/止翻转、作 effect key。
    var caretDragPos by remember { mutableStateOf<Offset?>(null) }
    var caretDragActive by remember { mutableStateOf(false) }
    // 放大镜显示条件：仅「光标手柄 / 选区端点手柄」拖拽时（下方最内层 pointerInput 的两支）为真——不含长按选择拖拽
    // （那支只置 selectionDragActive）。逐帧被放大镜层在 draw 读、只起/止翻转 → 不重组。
    var handleDragActive by remember { mutableStateOf(false) }

    var blink by remember { mutableStateOf(true) }
    // 选区变化即重置闪烁相位（光标一移立即可见）。用 snapshotFlow 在快照观察者里读 selection，而非把它当组合期
    // effect key——否则拖选每次移动都会整体重组本可组合（含预测量循环）。collectLatest 在新选区到达时取消上一
    // 轮闪烁循环，等价于「每次 selection 变化重启」。draw 里的 caretVisible 仍逐帧读 blink（重绘而非重组）。
    LaunchedEffect(readOnly) {
        if (readOnly) return@LaunchedEffect
        snapshotFlow { engine.selection }.collectLatest {
            blink = true
            while (true) {
                delay(500.milliseconds); blink = !blink
            }
        }
    }

    // 光标泪滴手柄：点按落光标 / 拖动后短暂显示，约 4s 无操作自动隐藏；打字（buffer.version 变化）立即收起。
    // 选区两端手柄不走这里——它们随选区常驻，由画布按 !selection.isEmpty 直接判定。
    var caretHandleVisible by remember { mutableStateOf(false) }
    var caretHandleToken by remember { mutableIntStateOf(0) }
    fun pingCaretHandle() {
        caretHandleVisible = true; caretHandleToken++
    }
    // 4s 无操作自动隐藏。用 snapshotFlow 观察 token，不把它当组合期 effect key——否则每次点按落光标/抓放手柄
    // （pingCaretHandle 自增 token）都多一次整体重组。collectLatest 在新 ping 到达时取消上一轮 delay = 重置计时。
    LaunchedEffect(Unit) {
        snapshotFlow { caretHandleToken }.collectLatest { token ->
            if (token == 0) return@collectLatest
            delay(4000.milliseconds); caretHandleVisible = false
        }
    }
    LaunchedEffect(engine.buffer.version) {
        // 任何编辑都收起光标手柄；首帧亦触发，但此时本就不可见、无副作用。
        caretHandleVisible = false
    }

    // 光标/端点在其行内的内容 x（网格行等宽算术、其余走该行 layout）；无 layout 返回 null。keep-in-view 与两个边缘自动滚动
    // effect 共用，做「横向 clip-gate 露出」。
    fun caretContentXOf(line: Int, column: Int): Float? {
        val col = column.coerceAtMost(engine.buffer.lineLength(line))
        return if (isGridLine(line)) col * charWpx else layoutFor(line)?.getCursorRect(col)?.left
    }

    // clip-gate：把 [curScrollX] 夹到「让 [caretCX] 落在可见带 [x, x+textAreaW] 内、留 margin 前瞻」；已在带内则原样返回
    //（可见即不动，不做「近边即滚」——避免点按落在可见靠边处被推滚）。仅用于不换行（换行 maxScrollX=0）。
    fun revealScrollXFor(caretCX: Float, curScrollX: Float): Float {
        val textAreaW = (viewportWidth - gutterWidthPx - padXPx * 2).coerceAtLeast(1f)
        val margin = padXPx * 3
        return when {
            caretCX < curScrollX -> (caretCX - margin).coerceIn(0f, maxScrollX)
            caretCX > curScrollX + textAreaW -> (caretCX - textAreaW + margin).coerceIn(0f, maxScrollX)
            else -> curScrollX
        }
    }

    // keep-in-view 的露出逻辑。几何量（lineHeightPx/lineTopPx/layoutFor/caretContentXOf/revealScrollXFor/softWrap）随字号每帧
    // 重算，但下方 LaunchedEffect(Unit) 的协程只在挂载时启动一次，会**永久捕获挂载帧（14sp）的几何**（stale-capture）：缩放后
    // 它按旧 14sp 几何算 caretTop、却比实时（新字号尺度）scrollY，两坐标系差「挂载/当前 字号比」→ 纯点击把已可见光标误判越界、
    // 滚一大段（跳位 ∝ 字号比；默认 14sp 时比=1、无跳）。故用 rememberUpdatedState 把整段提到「当前帧」，collect 里调 .value()
    // 用最新字号几何——与 positionAtLive/caretRectLive/liveMaxScroll 同法（本项目到处规避此坑，唯独这里漏了）。
    val revealCaretIntoViewLive = rememberUpdatedState {
        // 跟随「活动端」(head) 而非归一化的 selEnd：Shift+上/左 反向扩选时 head 在选区顶端，视口须随之上滚。
        val caret = engine.caret
        val line = caret.line
        // softWrap 下露出光标所在的那一「视觉行」，而非整条可能高过视口的折行——否则长行会把光标顶出视口。
        val curRow = if (softWrap) {
            layoutFor(line)?.getLineForOffset(caret.column.coerceIn(0, engine.buffer.lineLength(line))) ?: 0
        } else 0
        val caretTop = lineTopPx(line) + curRow * lineHeightPx
        val caretBottom = caretTop + lineHeightPx
        if (caretTop < scrollY) scrollY = caretTop
        else if (caretBottom > scrollY + viewportHeight) scrollY = caretBottom - viewportHeight
        // 横向随动：不换行时若光标越出可见带则滚动露出（clip-gate：可见即不动、留 margin 前瞻）。纯纵向导航（目标列生效）
        // 时跳过——否则目标列在短/长行间夹变会让视口横向来回 snap，很突兀。
        if (!softWrap && viewportWidth > 0f && !engine.hasGoalColumn) {
            caretContentXOf(line, caret.column)?.let { scrollX = revealScrollXFor(it, scrollX) }
        }
    }

    // keep-in-view：光标随选区/视口变化滚动露出。用 snapshotFlow 观察 (selection, viewport)、不把 selection 当组合期 key（P1）。
    // 观测集并入「是否正在手柄拖拽」：拖拽期标志为真、下方直接 return（滚动由边缘自动滚动接管）；拖拽结束标志 true→false 令
    // snapshotFlow 再 emit 一次、门禁解除后复位一次（修「拖到短行后滚出屏、松手不复位」）。露出走上面的 rememberUpdatedState
    // （当前帧几何）；effect 仍 key=Unit → 不随缩放重启、不会缩放后强行把视口滚到光标。
    LaunchedEffect(Unit) {
        snapshotFlow { Triple(engine.selection, viewportHeight, viewportWidth) to (selectionDragActive || caretDragActive) }.collect {
            if (viewportHeight <= 0f) return@collect
            if (selectionDragActive || caretDragActive) return@collect // 选区/光标手柄拖拽时由边缘自动滚动接管
            revealCaretIntoViewLive.value()
        }
    }

    // 手势闭包由 pointerInput(engine) 固定、不随滚动重启，会按值捕获几何量。gutter 宽用 rememberUpdatedState
    // 提到当前帧；滚动量则在 positionAt 里直接读活的 scrollY/scrollX——既让命中测试始终对齐当前滚动，
    // 又不因组合阶段读取滚动量而每滚 1px 重组。
    val hitGutterWidth = rememberUpdatedState(gutterWidthPx)

    fun positionAtWithScroll(offset: Offset, sY: Float, sX: Float): TextPosition {
        val ln = lineAtPx(offset.y + sY)
        val localX = (offset.x - hitGutterWidth.value - padXPx + sX).coerceAtLeast(0f)
        if (isGridLine(ln)) {
            return TextPosition(ln, EditorGeometry.gridXToColumn(localX, charWpx, engine.buffer.lineLength(ln)))
        }
        val layout = layoutFor(ln)
        // 撤销绘制时的基线平移，换回该行 layout 自身坐标再命中。
        val shift = refBaselinePx - (layout?.firstBaseline ?: refBaselinePx)
        val layoutY = (offset.y + sY) - lineTopPx(ln) - shift
        val col = layout?.getOffsetForPosition(Offset(localX, layoutY)) ?: 0
        return TextPosition(ln, col.coerceAtMost(engine.buffer.lineLength(ln)))
    }

    fun positionAt(offset: Offset): TextPosition =
        positionAtWithScroll(offset, scrollY.coerceIn(0f, maxScrollY), scrollX.coerceIn(0f, maxScrollX))

    // 手势闭包由 pointerInput(engine) 固定，不随字号/换行/行高变化重启。用 rememberUpdatedState 让它
    // 始终调「当前帧」的 positionAt（内含最新的 lineHeightPx / 视觉行索引 / softWrap / layout）。
    val positionAtLive = rememberUpdatedState<(Offset) -> TextPosition> { positionAt(it) }

    // 只读态提升为「当前帧」值，供固定 key 的手势闭包读取，避免 readOnly 切换后闭包按旧值捕获。
    val readOnlyLive = rememberUpdatedState(readOnly)

    // 光标/端点在屏幕上的矩形 [left, top, bottom]，供手柄命中测试。读活的 scroll/layout；用 rememberUpdatedState
    // 让固定 key 的手势闭包取到当前帧几何（与 positionAtLive 同理）。与画布 drawHandle 的坐标算法一致。
    fun caretScreenColumnRect(pos: TextPosition): FloatArray? {
        val col = pos.column.coerceIn(0, engine.buffer.lineLength(pos.line))
        val sY = scrollY.coerceIn(0f, maxScrollY)
        val sX = scrollX.coerceIn(0f, maxScrollX)
        if (isGridLine(pos.line)) {
            val base = lineTopPx(pos.line) - sY + (refBaselinePx - gridRefBaseline)
            val x = gutterWidthPx + padXPx - sX + col * charWpx
            return floatArrayOf(x, base + gridRefCursor.top, base + gridRefCursor.bottom)
        }
        val layout = layoutFor(pos.line) ?: return null
        val cr = layout.getCursorRect(col)
        val base = lineTopPx(pos.line) - sY + (refBaselinePx - layout.firstBaseline)
        return floatArrayOf(gutterWidthPx + padXPx - sX + cr.left, base + cr.top, base + cr.bottom)
    }

    val caretRectLive = rememberUpdatedState<(TextPosition) -> FloatArray?> { caretScreenColumnRect(it) }

    // 边缘自动滚动 effect 的 key 是 Boolean、不随重组刷新，循环体会按值捕获滚动上限。软换行下自动滚动
    // 驶入未测量区域时真实 maxScrollY 会随测量增大，用 rememberUpdatedState 让循环读到当前帧上限，
    // 否则会停在旧估算的「假底部」、选不到文档末尾（与 hitScrollY 同类修复）。
    val liveMaxScrollY = rememberUpdatedState(maxScrollY)
    val liveMaxScrollX = rememberUpdatedState(maxScrollX)
    // 缩放手势期做边界阻尼需按「预览缩放 s」算 maxScroll = s·内容尺寸 − 视口；内容尺寸经 rememberUpdatedState 提到当前帧，
    // 避开缩放手势 pointerInput(Unit) 闭包的 stale-capture（手势期无重排、内容尺寸恒定，故手势内取值稳定）。
    val liveContentHeight = rememberUpdatedState(contentHeight)
    val liveContentWidth = rememberUpdatedState(gutterWidthPx + padXPx * 2 + widestSeen[0])

    // 松手提交缩放：把连续 previewScale 落成精确的真实字号，一次性设好 scroll 并触发唯一一次重排。k = newFont/fontStart（== s，s 已夹字号范围）。
    // 不换行：自相似 + TextMotion ⇒ 新字号内容 == 旧内容×s 逐像素，把预览末帧的等效滚动 effScroll=s·baseScroll−t 折成新 scroll；手势期已把 effScroll
    // 硬钳在界内 ⇒ 必落界内、提交无跳。换行：横向钉左、纵向重排非自相似 → 锚「预览末帧屏幕顶」字符、由 LaunchedEffect(fontSizeSp) 重排后放回顶。
    fun commitZoom(fontStart: Float, s: Float, tx: Float, ty: Float) {
        val newFont = ZoomMath.commitFontSize(fontStart, s, ZOOM_MIN_SP, ZOOM_MAX_SP)
        // 字号与平移都极小（双指微动 / 纯噪声）：视作未动、不重排。
        if (abs(newFont - fontStart) < ZOOM_MIN_CHANGE_SP && abs(tx) < 0.5f && abs(ty) < 0.5f) return
        val k = newFont / fontStart // == s
        if (softWrap) {
            // 预览屏幕顶 y=0 对应旧内容 y：s·py+ty=0 → py=−ty/s → 旧内容 y = scrollY − ty/s（scrollY 手势期未变 = baseScrollY）。
            val topContentYOld = (scrollY - ty / s).coerceAtLeast(0f)
            zoomWrapAnchorPos = positionAtWithScroll(Offset(0f, 0f), topContentYOld, 0f)
            scrollY = ZoomMath.provisionalScrollYWrap(topContentYOld, k, 0f) // 提交帧≈预览末帧，效应再精确置顶
            scrollX = 0f
        } else {
            // 预览 screenY = s·(cy−scrollY)+ty，提交 screenY = s·cy − newScrollY ⇒ newScrollY = s·scrollY − ty（横向同理）。手势期已硬钳在界内，
            // coerceAtLeast(0) 仅防浮点/边界，上界由 re-clamp 在新字号下收。scrollX/scrollY 手势期未变 = baseScroll。
            scrollX = (s * scrollX - tx).coerceAtLeast(0f)
            scrollY = (s * scrollY - ty).coerceAtLeast(0f)
        }
        fontSizeSp = newFont // 唯一一次重排在此
        previewScale = 1f; previewTx = 0f; previewTy = 0f // 变换已折进 scroll，预览归位
    }
    // 手势闭包 key=Unit、不随重组重启，会按值捕获闭包。用 rememberUpdatedState 让它每次调「当前帧」的 commitZoom
    // （内含最新 softWrap / lineTopPx / layoutFor / liveMaxScroll），避开 stale-capture（与 positionAtLive 同理）。
    val commitZoomLive =
        rememberUpdatedState<(Float, Float, Float, Float) -> Unit> { fontStart, s, tx, ty -> commitZoom(fontStart, s, tx, ty) }

    // 选区拖拽到视口上/下/左/右热区时，按帧持续纵横滚动并同步延伸选区；速度随进入热区的深度线性增大。
    // 词锚（长按选词）按词粒度扩展、字符锚（手柄端点）按字符扩展。
    LaunchedEffect(selectionDragActive) {
        if (!selectionDragActive) return@LaunchedEffect
        while (true) {
            val pos = selectionDragPos ?: break
            val wordAnchor = selectionWordAnchor
            val charAnchor = selectionAnchor
            if (wordAnchor == null && charAnchor == null) break
            val maxY = liveMaxScrollY.value
            val maxX = liveMaxScrollX.value
            val stepY = edgeAutoScrollSpeed(pos.y, viewportHeight, lineHeightPx)
            var stepX = edgeAutoScrollSpeed(pos.x, viewportWidth, lineHeightPx)
            // 横向自动滚只在延伸端还能沿**当前行**推进时才进行：到行尾（右）/行首（左）即停，不把视口滚进该行文本外的空白
            // （当前行短而上下有更长行时，全局 maxScrollX>0 会误让其滚进空白、延伸端却被钳在行尾不动）。与光标手柄版同一道闸。
            // 并 gate maxX>0：maxX==0（softWrap 或无横向余量）时归零 stepX 也改不动 newX，跳过、免每帧 positionAtWithScroll/layoutFor。
            if (stepX != 0f && maxX > 0f) {
                val cur = positionAtWithScroll(pos, scrollY, scrollX)
                val lineLen = engine.buffer.lineLength(cur.line)
                if ((stepX > 0f && cur.column >= lineLen) || (stepX < 0f && cur.column <= 0)) stepX = 0f
            }
            val newY = if (stepY != 0f && maxY > 0f) (scrollY + stepY).coerceIn(0f, maxY) else scrollY
            var newX = if (stepX != 0f && maxX > 0f) (scrollX + stepX).coerceIn(0f, maxX) else scrollX
            // 拖拽期即时露出：延伸端被短行钳到当前不可见处时，立刻横滚把它拉回可见带（不等松手）。在 edge-step 之后再夹，
            // 故与之不打架——长行推边时端点就在指下、在带内、revealScrollXFor 原样返回；仅短行钳出屏外才真正横滚。
            if (!softWrap && maxX > 0f) {
                val c = positionAtWithScroll(pos, newY, newX)
                caretContentXOf(c.line, c.column)?.let { newX = revealScrollXFor(it, newX) }
            }
            if (newY != scrollY || newX != scrollX) {
                scrollY = newY
                scrollX = newX
                val caret = positionAtWithScroll(pos, newY, newX)
                if (wordAnchor != null) engine.selectWordRange(wordAnchor, caret)
                else charAnchor?.let { engine.setSelection(it, caret) }
            }
            withFrameNanos { }
        }
    }

    // 光标手柄拖拽的边缘自动滚动（与上方选区版同机制，但每帧落的是光标 setCursor 而非延伸选区）：手柄拖进边缘热区、
    // 或按住不动停在热区时，逐帧滚动并把光标落到滚动后指下的内容处——于是能把光标一路拖到当前视口外的内容。
    // 非热区时 step=0、不滚，光标由手势回调 setCursor 落定；二者同置光标、值一致、无冲突（与选区版对称）。
    LaunchedEffect(caretDragActive) {
        if (!caretDragActive) return@LaunchedEffect
        while (true) {
            val pos = caretDragPos ?: break
            val maxY = liveMaxScrollY.value
            val maxX = liveMaxScrollX.value
            val stepY = edgeAutoScrollSpeed(pos.y, viewportHeight, lineHeightPx)
            var stepX = edgeAutoScrollSpeed(pos.x, viewportWidth, lineHeightPx)
            // 横向自动滚只在光标还能沿**当前行**推进时才进行：到行尾（右）/行首（左）即停，不把视口滚进该行文本右侧的空白。
            // maxScrollX 是按最宽行算的全局上界；若当前行短（如 50 字符）而上下有更长行，全局 maxScrollX>0 会让 effect
            // 误以为「右边还能滚」，于是滚进空白、光标却被 positionAt 钳在行尾不动。按当前行长度就地闸停。
            // 并 gate maxX>0：maxX==0（softWrap 或无横向余量）时归零 stepX 也改不动 newX，跳过、免每帧 positionAtWithScroll/layoutFor。
            if (stepX != 0f && maxX > 0f) {
                val cur = positionAtWithScroll(pos, scrollY, scrollX)
                val lineLen = engine.buffer.lineLength(cur.line)
                if ((stepX > 0f && cur.column >= lineLen) || (stepX < 0f && cur.column <= 0)) stepX = 0f
            }
            val newY = if (stepY != 0f && maxY > 0f) (scrollY + stepY).coerceIn(0f, maxY) else scrollY
            var newX = if (stepX != 0f && maxX > 0f) (scrollX + stepX).coerceIn(0f, maxX) else scrollX
            // 拖拽期即时露出：光标被短行钳到当前不可见处时，立刻横滚把它拉回可见带（不等松手）。在 edge-step 之后再夹，与之
            // 不打架——长行推边时光标就在指下、在带内、revealScrollXFor 原样返回；仅短行钳出屏外才真正横滚。
            if (!softWrap && maxX > 0f) {
                val c = positionAtWithScroll(pos, newY, newX)
                caretContentXOf(c.line, c.column)?.let { newX = revealScrollXFor(it, newX) }
            }
            if (newY != scrollY || newX != scrollX) {
                scrollY = newY
                scrollX = newX
                engine.setCursor(positionAtWithScroll(pos, newY, newX))
            }
            withFrameNanos { }
        }
    }

    // softWrap 下按「视觉行」上下移动：一条文档行可能折成多视觉行，直接 line±1 会跨过整条长行、和用户看到的
    // 「下一行」不符。用当前帧 layout 求光标所在视觉行与像素 x，落到目标视觉行同 x 处的列；跨视觉行时进上一/
    // 下一文档行的末/首视觉行。desiredX 在连续上下移动间记忆（穿过更短的视觉行不丢）：仅当上次落点仍等于
    // 当前 head 时复用，否则按当前光标重新取 x（横向移动/编辑/点按都会让 head 偏离，从而自然重置）。
    var vGoalX by remember { mutableStateOf<Float?>(null) }
    var vGoalCaret by remember { mutableStateOf<TextPosition?>(null) }
    fun moveCaretVisual(dir: Int, extend: Boolean) {
        val from = engine.caret
        val layout = layoutFor(from.line) ?: run {
            engine.moveCaretVertically(dir, extend); return // 无 layout 时退回按文档行移动
        }
        val col = from.column.coerceIn(0, engine.buffer.lineLength(from.line))
        val curRow = layout.getLineForOffset(col)
        val goalX = if (vGoalX != null && vGoalCaret == from) vGoalX!! else layout.getCursorRect(col).left
        val step = EditorGeometry.visualVerticalTarget(from.line, curRow, dir, lineCount) { l -> layoutFor(l)?.lineCount ?: 1 }
            ?: return // 文档端，不动
        val targetLayout = if (step.line == from.line) layout else (layoutFor(step.line) ?: return)
        val yMid = (targetLayout.getLineTop(step.row) + targetLayout.getLineBottom(step.row)) / 2f
        val newCol = targetLayout.getOffsetForPosition(Offset(goalX, yMid))
            .coerceIn(0, engine.buffer.lineLength(step.line))
        val to = TextPosition(step.line, newCol)
        if (extend) engine.extendSelectionTo(to) else engine.setCursor(to)
        vGoalX = goalX
        vGoalCaret = to
    }

    // 底部安全区 = 导航栏 / captionBar / 键盘 三者较大者（union 取各边最大）。编辑器自管它：背景铺到屏幕边缘、
    // 内容抬到栏上，故宿主无需再加 imePadding/导航栏 padding。键盘收起=导航栏高，弹出=键盘高（已含导航栏区）。
    val bottomBarInsets = WindowInsets.navigationBars
        .union(WindowInsets.captionBar)
        .union(WindowInsets.ime)
        .only(WindowInsetsSides.Bottom)
    val showSymbolBar = !readOnly && symbols.isNotEmpty()

    // 根为 Column：文本区（weight 1f）在上、符号条常驻在下。Column 底色铺满整列（含系统栏区）。
    Column(modifier.background(colors.background)) {
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                // 无符号条时文本区自行让开底部系统栏（末行不落在小白条/键盘下）；有符号条时不让位——由符号条随键盘
                // 增高把文本区顶上去（文本区底 = 符号条顶，始终在系统栏之上），避免双重让位把文本区多压一截。
                .then(if (showSymbolBar) Modifier else Modifier.windowInsetsPadding(bottomBarInsets))
                .clipToBounds()
                .overscroll(overscroll)
                .onSizeChanged { viewportWidth = it.width.toFloat(); viewportHeight = it.height.toFloat() }
                .onGloballyPositioned { contentTopInWindow = it.positionInWindow().y }
                .scrollable2D(scroll2D, overscrollEffect = overscroll, interactionSource = scrollInteraction)
                .pointerInput(Unit) {
                    // 双指缩放 + 缩放后单指跟手平移（一个连续手势内完成，避免与 scrollable 交接跳变）。≥2 指时累积连续 previewScale
                    // （仅 draw 阶段读 → 零重组/零重排/零重测）并消费；松手一次 commitZoom 换字号 + 重排。提交后若仍剩一指，**本处理器**
                    // 继续按其位移 1:1 平移「已重排」的内容（并消费，scrollable 不再插手）——平移直接作用在提交后真实内容上、跟手、无跳。
                    // 平移不提供 fling（缩放后微调足矣）；抬手结束。纯单指（从未双指）不进任何分支、不消费 → 照常交 scrollable 滚动/惯性。
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var active = false // 已进入双指缩放
                        var committed = false // 本轮已提交（其后剩余单指走跟手平移）
                        var fontStart = fontSizeSp // 手势起始字号（commitZoom 折算基准；scrollX/Y 手势期不变，直接读实时值即 base）
                        var prevCentroid = Offset.Unspecified // 上一帧双指中点（增量变换用）
                        var freeTx = 0f // 未受阻尼的自由平移（增量累积于此）；显示时经 rubber-band 映射写入 previewTx/Ty
                        var freeTy = 0f
                        var panLast = Offset.Unspecified // 提交后剩余单指的上一位置（跟手平移基准）
                        var panActive = false // 跟手平移是否已越过 touchSlop（抬指残留微移 / 触屏 liftoff 抖动不算平移）
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.count { it.pressed }
                            if (pressed >= 2) {
                                panLast = Offset.Unspecified; panActive = false // 回到双指：清除单指平移基准与激活态
                                // calculateCentroid 只计入「本帧且上帧都按下」的指针：第二指刚落那帧新指 previousPressed=false 不计入 →
                                // 返回的是旧那根手指位置、非真中点。故新指落下帧不拿它当基准，等下一帧两指都稳定按下再确立中点。
                                val c = event.calculateCentroid(useCurrent = true)
                                if (!active) {
                                    // 缩放起始：起始态归零、捕获起始字号。prevCentroid 置未定，等首个稳定双指中点帧再确立。
                                    active = true; committed = false
                                    fontStart = fontSizeSp
                                    previewScale = 1f; previewTx = 0f; previewTy = 0f
                                    freeTx = 0f; freeTy = 0f
                                    prevCentroid = Offset.Unspecified
                                } else if (c != Offset.Unspecified) {
                                    if (prevCentroid == Offset.Unspecified) {
                                        prevCentroid = c // 首个稳定双指中点：仅确立增量基准，本帧不动变换（避免「新落指帧中点=单指」的首帧大跳）
                                    } else {
                                        // 逐帧增量累积到**自由**平移 freeTx/Ty：绕当前中点缩放 ez（撞字号界 ez→1、仍可平移）+ 中点位移 → T'=ez·T+(c−ez·prevC)。
                                        val newScale = ZoomMath.clampScaleToFontRange(
                                            previewScale * event.calculateZoom(),
                                            fontStart,
                                            ZOOM_MIN_SP,
                                            ZOOM_MAX_SP
                                        )
                                        val ez = if (previewScale > 0f) newScale / previewScale else 1f
                                        if (!softWrap) freeTx = ez * freeTx + (c.x - ez * prevCentroid.x) // 换行钉左：freeTx 恒 0
                                        freeTy = ez * freeTy + (c.y - ez * prevCentroid.y)
                                        previewScale = newScale
                                        prevCentroid = c
                                        // 显示 = 把自由平移的等效滚动 eff = s·base − free **硬钳到界内** [0, max@s]：四向跟手、到边硬停、绝不越界
                                        // （放得下的轴 max=0 → 钉死）。自由层 freeTx/Ty 仍纯累积 → 边缘无漂移、回拉即跟。越界量恒 0 ⇒ 提交无越界、松手弹簧不触发。
                                        val vw = viewportWidth
                                        val vh = viewportHeight
                                        if (!softWrap) {
                                            val maxSX = (newScale * liveContentWidth.value - vw).coerceAtLeast(0f)
                                            previewTx = newScale * scrollX - (newScale * scrollX - freeTx).coerceIn(0f, maxSX)
                                        }
                                        // 纵向上界含底部留白（vh 实时读、不 stale）：预览可停在留白里，与提交 re-clamp 的 maxScrollY 同式 ⇒ 起手不 snap、松手不跳。
                                        val maxSY =
                                            (newScale * liveContentHeight.value - vh + vh * BOTTOM_SCROLL_PAD_FRACTION).coerceAtLeast(0f)
                                        previewTy = newScale * scrollY - (newScale * scrollY - freeTy).coerceIn(0f, maxSY)
                                    }
                                }
                                // 双指期**一律消费**（含持距漂移帧）：否则未消费帧落到 scrollable2D、攒 fling，非同时松手时甩出 → 大跳。
                                event.changes.forEach { it.consume() }
                            } else if (active && !committed) {
                                // 掉到 <2 指：立即提交缩放（内部把变换折进 scroll + 预览归位），并以剩余单指为基准接管跟手平移。
                                commitZoomLive.value(fontStart, previewScale, previewTx, previewTy)
                                committed = true; active = false
                                val p = event.changes.firstOrNull { it.pressed }
                                panLast = p?.position ?: Offset.Unspecified
                                // 连同「刚抬起」的那根手指一并消费：否则 scrollable2D 可能在其 up 上结束拖动并 fling → 松手大跳。
                                event.changes.forEach { it.consume() }
                            } else if (committed && pressed >= 1) {
                                // 缩放后剩余单指继续滑动：越过 touchSlop 才算「跟手平移」，据位移 1:1 平移已重排内容（跟手、基于手指下真实内容）。
                                // 未越 slop 前不动：滤掉「本想同时松手却一根先抬」时残留手指的微移 / 触屏 liftoff 抖动，避免误滚动跳变。全程消费防落 scrollable。
                                val p = event.changes.firstOrNull { it.pressed }
                                if (p != null) {
                                    if (panLast != Offset.Unspecified) {
                                        val d = p.position - panLast
                                        if (!panActive) {
                                            // 距 commit 基准累积（未越 slop 前不更新 panLast）；越档后从当前位置起算、丢弃 slop 内位移。
                                            if (d.getDistance() > viewConfiguration.touchSlop) {
                                                panActive = true; panLast = p.position
                                            }
                                        } else {
                                            scrollX = (scrollX - d.x).coerceIn(0f, liveMaxScrollX.value)
                                            scrollY = (scrollY - d.y).coerceIn(0f, liveMaxScrollY.value)
                                            panLast = p.position
                                        }
                                    } else panLast = p.position
                                    p.consume()
                                }
                            }
                        } while (event.changes.any { it.pressed })
                        // 兜底（双指同时抬起 / 手势取消，未经上面的 <2 指分支）：仍未提交则提交一次（内部折算进 scroll + 预览归位）。
                        if (active && !committed) commitZoomLive.value(fontStart, previewScale, previewTx, previewTy)
                    }
                }
                // caretRectInEditor：光标在内容本地坐标的矩形（复用 caretRectLive 的当前帧几何，含滚动/gutter），
                // 桌面据此把 IME 候选窗定位到光标处；Android 忽略。r = [x, top, bottom]。
                .editorTextInput(
                    engine,
                    enabled = !readOnly,
                    caretRectInEditor = {
                        caretRectLive.value(engine.caret)?.let { r -> Rect(r[0], r[1], r[0] + 1f, r[2]) }
                    },
                )
                .onKeyEvent { ev ->
                    if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                    if (ev.isCtrlPressed) {
                        when (ev.key) {
                            // 全选/复制在只读模式下依然可用（只读恰恰最需要可选可复制）。
                            Key.A -> {
                                engine.selectAll(); true
                            }

                            Key.C -> {
                                engine.selectedText()?.let { txt ->
                                    clipboardScope.launch { clipboard.setClipEntry(plainTextClipEntry(txt)) }
                                }; true
                            }
                            // 剪切/粘贴会改动文档，只读时消费事件但不执行。
                            Key.X -> {
                                if (!readOnly) engine.selectedText()?.let { txt ->
                                    clipboardScope.launch { clipboard.setClipEntry(plainTextClipEntry(txt)) }
                                    engine.replaceSelection("")
                                }; true
                            }

                            Key.V -> {
                                if (!readOnly) clipboardScope.launch {
                                    clipboard.getClipEntry()?.plainText()?.let { engine.insert(it) }
                                }; true
                            }

                            else -> false
                        }
                    } else {
                        val shift = ev.isShiftPressed
                        // composing（预编辑）进行中，方向/回车/退格等键由输入法消费、不回落到此处，故这些处理器
                        // 无需按 composing 设闸；可打印字符路径（insertTypedCharacter）则显式设了 composing 闸。
                        when (ev.key) {
                            Key.DirectionLeft -> {
                                engine.moveCaretHorizontally(-1, shift); true
                            }

                            Key.DirectionRight -> {
                                engine.moveCaretHorizontally(1, shift); true
                            }

                            Key.DirectionUp -> {
                                if (softWrap) moveCaretVisual(-1, shift) else engine.moveCaretVertically(-1, shift); true
                            }

                            Key.DirectionDown -> {
                                if (softWrap) moveCaretVisual(1, shift) else engine.moveCaretVertically(1, shift); true
                            }

                            Key.Backspace -> {
                                if (!readOnly) engine.backspace(); true
                            }

                            Key.Delete -> {
                                if (!readOnly) engine.deleteForward(); true
                            }

                            Key.Enter, Key.NumPadEnter -> {
                                if (!readOnly) engine.insert("\n"); true
                            }
                            // 可编辑时 Tab 插入缩进并消费，防止事件回落到 Compose 默认焦点遍历、把焦点带走
                            // （代码/YAML 编辑器里 Tab 跳焦点是致命的意外行为）。只读时放行，让 Tab 正常切换焦点。
                            Key.Tab -> if (readOnly) false else {
                                engine.insert("    "); true
                            }
                            // 可打印字符回退：桌面上无 IME 参与的普通字符经 KeyEvent 到达（IME 提交走输入
                            // 会话的 editText、不到这里），在此插入；Android 返回 false（字符由 InputConnection
                            // 处理，避免双插）。
                            else -> insertTypedCharacter(engine, ev, readOnly)
                        }
                    }
                }
                .focusRequester(focusRequester)
                .focusable(interactionSource = interaction)
                // 点按手势（只读时同样挂载：仅不弹软键盘、不接受编辑）。自定义而非 detectTapGestures：
                // 后者一旦提供 onDoubleTap，就要等双击超时(~300ms)确认才回调 onTap，点击落光标发闷。这里
                // 第一击抬手「立即」落光标 + 弹键盘；双击窗口内若来第二击，升级为选词（桌面双击、移动端双击皆可）。
                // 长按拖拽由下方 block 接管——指针被其消费/取消时 waitForUpOrCancellation 返回 null，本 block 让位。
                .pointerInput(engine) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        // 等抬手，但包一层长按超时：若超时前未抬手，说明这次已升级为长按（由下方 block 选词），
                        // 本 block 直接让位、不落光标——否则纯长按选词后「不拖动直接抬手」会把刚选好的词塌成光标
                        // （长按 block 不移动时不消费任何 change，waitForUpOrCancellation 拿到未消费的 up 会误触发）。
                        val up = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                            waitForUpOrCancellation()
                        } ?: return@awaitEachGesture
                        engine.setCursor(positionAtLive.value(up.position))
                        focusRequester.requestFocus()
                        if (!readOnlyLive.value) {
                            engine.requestShowKeyboard?.invoke()
                            pingCaretHandle() // 落光标后显示泪滴手柄（4s 后自动隐藏）
                        }
                        val second = withTimeoutOrNull(viewConfiguration.doubleTapTimeoutMillis) {
                            awaitFirstDown(requireUnconsumed = false)
                        }
                        val up2 = if (second != null) waitForUpOrCancellation() else null
                        if (up2 != null) {
                            val w = engine.wordRangeAt(positionAtLive.value(up2.position))
                            engine.setSelection(w.start, w.end)
                            focusRequester.requestFocus()
                        }
                    }
                }
                .pointerInput(engine) {
                    // 长按才进入选择：长按处先选中该词，随后拖拽扩展选区。普通拖拽不在此消费，
                    // 于是落到上面的 scrollable 去滚动页面（移动端标准行为）。拖到上下边缘由
                    // selectionDragPos 驱动的边缘自动滚动 effect 接管。只读模式同样可用（纯选择、不改文档）。
                    detectDragGesturesAfterLongPress(
                        onDragStart = { p ->
                            haptic.performHapticFeedback(HapticFeedbackType.LongPress) // 长按进入选择时轻震确认
                            val w = engine.wordRangeAt(positionAtLive.value(p))
                            engine.setSelection(w.start, w.end)
                            // 锚定整个初选词、按词粒度扩展（不用按下点作字符锚）——否则边缘自动滚动或首次拖拽会把
                            // 刚选好的词塌成字符级光标（长按靠近视口边缘时尤其明显：一选中就瞬间消失）。
                            selectionWordAnchor = w
                            selectionAnchor = null
                            focusRequester.requestFocus()
                            selectionDragPos = p
                            selectionDragActive = true
                        },
                        onDrag = { change, _ ->
                            selectionDragPos = change.position
                            selectionWordAnchor?.let { engine.selectWordRange(it, positionAtLive.value(change.position)) }
                        },
                        onDragEnd = { selectionDragPos = null; selectionDragActive = false; selectionWordAnchor = null },
                        onDragCancel = { selectionDragPos = null; selectionDragActive = false; selectionWordAnchor = null },
                    )
                }
                // 手柄拖拽：抓到光标/选区端点手柄即接管——重定位光标或调整选区端点；未抓到则不消费，让位给
                // 滚动/点按/长按。本块是最内层 pointerInput，Main 阶段最先处理，故能先于 scrollable 抢占手柄拖拽；
                // 命中盒经 caretRectLive 取当前帧几何，避开 stale-capture 坑。
                .pointerInput(engine) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val p = down.position
                        val sel = engine.selection
                        fun hit(kind: HandleKind, at: TextPosition): Boolean {
                            val r = caretRectLive.value(at) ?: return false
                            return EditorGeometry.handleGeometry(kind, r[0], r[1], r[2], handleRadiusPx, handleSlopPx)
                                .hitContains(p.x, p.y)
                        }
                        // 有选区时只看两端手柄（终点优先）；无选区且光标手柄可见、非只读时看光标手柄。
                        var kind: HandleKind? = null
                        var anchor: TextPosition? = null
                        var grabbed: TextPosition? = null
                        if (!sel.isEmpty) {
                            when {
                                hit(HandleKind.SelectionEnd, sel.end) -> {
                                    kind = HandleKind.SelectionEnd; anchor = sel.start; grabbed = sel.end
                                }

                                hit(HandleKind.SelectionStart, sel.start) -> {
                                    kind = HandleKind.SelectionStart; anchor = sel.end; grabbed = sel.start
                                }
                            }
                        } else if (caretHandleVisible && !readOnlyLive.value && hit(HandleKind.Caret, sel.start)) {
                            kind = HandleKind.Caret; grabbed = sel.start
                        }
                        if (kind == null) return@awaitEachGesture // 未抓到手柄，让位给滚动/点按/长按

                        down.consume()
                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove) // 抓住手柄时轻震确认
                        // 纵向抓取偏移：目标点落在光标中部而非手指处（手指压在泪滴上、光标在其上方）。
                        val gr = grabbed?.let { caretRectLive.value(it) }
                        val grabDy = if (gr != null) (gr[1] + gr[2]) / 2f - p.y else 0f
                        fun mapped(o: Offset): TextPosition = positionAtLive.value(Offset(o.x, o.y + grabDy))

                        if (kind == HandleKind.Caret) {
                            engine.setCursor(mapped(p)); pingCaretHandle()
                            // 先等真正拖动（越过 touchSlop）才进入重定位 + 边缘自动滚动；纯点按（按下即抬起、位移不过 slop）
                            // 只落光标、绝不激活自动滚动——否则光标本就靠边时，一按到边缘热区就被 caretDrag effect 当成「停在边缘」
                            // 带着滚。以位移阈值区分：点=只落光标永不滚，拖=才滚，且自动滚是「拖拽进行时」的属性。
                            // caretDragPos 存已含 grabDy 的目标点（与 mapped 同源），供 effect 判热区并逐帧落光标、可拖到视口外内容。
                            val slop = awaitTouchSlopOrCancellation(down.id) { c, _ -> c.consume() }
                            if (slop != null) {
                                // 拖拽中每落到「新字符/行」补一次轻震（按位置去抖、非逐帧——一帧多次
                                // 位移都在同一字符时不重复震）。比较落定后的 engine.caret（已 clamp）而非原始映射点：拖过
                                // 短行行尾时 caret 停在行尾、原始列还在涨，比 caret 才不会空震。lastHaptic 初值为抓取帧的光标。
                                var lastHaptic = engine.caret
                                caretDragPos = Offset(slop.position.x, slop.position.y + grabDy)
                                engine.setCursor(mapped(slop.position))
                                if (engine.caret != lastHaptic) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); lastHaptic = engine.caret
                                }
                                caretDragActive = true
                                handleDragActive = true
                                drag(down.id) { change ->
                                    caretDragPos = Offset(change.position.x, change.position.y + grabDy)
                                    engine.setCursor(mapped(change.position))
                                    if (engine.caret != lastHaptic) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); lastHaptic = engine.caret
                                    }
                                    change.consume()
                                }
                                caretDragActive = false
                                handleDragActive = false
                                caretDragPos = null
                            }
                            pingCaretHandle() // 抬手后重置 4s 计时
                        } else {
                            val a = anchor!!
                            // 与光标手柄同理：越过 touchSlop 才算拖拽端点、激活延伸 + 边缘自动滚动；纯点按端点手柄（不过 slop）
                            // 保持选区原样、不滚动——否则端点手柄本就靠边时，一按到边缘热区就被选区 effect 当成「停在边缘」带着滚。
                            val slop = awaitTouchSlopOrCancellation(down.id) { c, _ -> c.consume() }
                            if (slop != null) {
                                selectionAnchor = a
                                selectionWordAnchor = null // 端点拖拽走字符级锚
                                // selectionDragPos 存已含 grabDy 的目标点（与 mapped/setSelection 同源）：否则边缘自动滚动 effect 会按
                                // 手柄泪滴（在光标下方约 1.5 行）所在行做「按行闸停」，落到下方短行 → 误判行尾、横向不滚。与光标手柄一致。
                                selectionDragPos = Offset(slop.position.x, slop.position.y + grabDy)
                                selectionDragActive = true
                                handleDragActive = true
                                // 端点每落到「新字符/行」补一次轻震，与光标手柄一致；比较落定后的活动端 engine.caret
                                // （setSelection 的 head，已 clamp）。lastHaptic 初值为抓取的端点位置。
                                var lastHaptic = grabbed
                                engine.setSelection(a, mapped(slop.position))
                                if (engine.caret != lastHaptic) {
                                    haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); lastHaptic = engine.caret
                                }
                                drag(down.id) { change ->
                                    selectionDragPos = Offset(change.position.x, change.position.y + grabDy)
                                    engine.setSelection(a, mapped(change.position))
                                    if (engine.caret != lastHaptic) {
                                        haptic.performHapticFeedback(HapticFeedbackType.TextHandleMove); lastHaptic = engine.caret
                                    }
                                    change.consume()
                                }
                                selectionDragPos = null
                                selectionDragActive = false
                                handleDragActive = false
                            }
                        }
                    }
                }
        ) {
            EditorCanvas(
                engine = engine,
                colors = colors,
                textMeasurer = measurer,
                textStyle = textStyle,
                numberStyle = numberStyle,
                lineHeightPx = lineHeightPx,
                gutterWidthPx = gutterWidthPx,
                padXPx = padXPx,
                lineNumberMode = lineNumberMode,
                scrollX = { scrollX.coerceIn(0f, maxScrollX) },
                scrollY = { scrollY.coerceIn(0f, maxScrollY) },
                // 与 scrollY/scrollX 传参同源钳制：maxScroll 骤减那一帧（底部捏合放大 / 收起 IME），行锚与像素
                // 偏移都读钳制后的 scrollY，二者恒定锚同一窗口，不再顶部留一帧空白（re-clamp effect 下一帧才生效）。
                // 入参 extraTopPx 为缩放预览的可见带上界（预缩放屏幕 y）：非缩放时为 0（等同原状），缩小时为负、
                // 令起始行前移、补齐外扩露出的行。
                firstVisibleLine = { extraTopPx -> (lineAtPx(scrollY.coerceIn(0f, maxScrollY) + extraTopPx) - 3).coerceAtLeast(0) },
                lineTopPx = ::lineTopPx,
                refBaselinePx = refBaselinePx,
                caretHandleVisible = { caretHandleVisible && !readOnly },
                handleRadiusPx = handleRadiusPx,
                layoutFor = ::layoutFor,
                charW = charWpx,
                isGridLine = ::isGridLine,
                gridRefBaseline = gridRefBaseline,
                gridRefCursorTop = gridRefCursor.top,
                gridRefCursorBottom = gridRefCursor.bottom,
                // 缩放预览的运行态仿射（draw 阶段读）：previewScale 连续缩放系数、previewTx/previewTy 两轴平移（屏幕 px）。手势逐帧按
                // 「绕当前双指中点缩放 + 中点位移平移」增量累积 → 四向自由跟手；换行下 previewTx 恒 0（正文/gutter 钉左）。恒 (1,0,0) 逐像素等价原状。
                previewScale = { previewScale },
                previewTx = { previewTx },
                previewTy = { previewTy },
                modifier = Modifier.fillMaxSize(),
            )
            // 光标独立图层：叠在正文之上，blink 只切本层 alpha、不重放正文画布（P10）。
            CursorOverlay(
                engine = engine,
                colors = colors,
                caretVisible = { !readOnly && blink },
                scrollX = { scrollX.coerceIn(0f, maxScrollX) },
                scrollY = { scrollY.coerceIn(0f, maxScrollY) },
                lineTopPx = ::lineTopPx,
                refBaselinePx = refBaselinePx,
                layoutFor = ::layoutFor,
                charW = charWpx,
                isGridLine = ::isGridLine,
                gridRefBaseline = gridRefBaseline,
                gridRefCursorTop = gridRefCursor.top,
                gridRefCursorBottom = gridRefCursor.bottom,
                gutterWidthPx = gutterWidthPx,
                padXPx = padXPx,
                previewScale = { previewScale }, // 缩放预览期不画光标（正文在缩放、光标会脱节）
                modifier = Modifier.fillMaxSize(),
            )
            // 放大镜：宿主在窗口级 Popup（可浮到编辑器上方的工具栏/状态栏空间），仅光标/选区端点手柄拖拽时（handleDragActive）出现。
            MagnifierOverlay(
                engine = engine,
                colors = colors,
                active = { handleDragActive },
                // 连续手指位置（逐帧写）：光标手柄用 caretDragPos、选区端点用 selectionDragPos；胶囊据其 x 平滑跟手不跳。
                dragPos = { caretDragPos ?: selectionDragPos },
                caretVisible = { !readOnly && blink }, // 镜内光标随主编辑器同一 blink 闪烁
                viewportWidth = { viewportWidth },     // 水平钳制到视口
                contentTopInWindow = { contentTopInWindow }, // 允许胶囊上浮到窗口顶附近
                scrollX = { scrollX.coerceIn(0f, maxScrollX) },
                scrollY = { scrollY.coerceIn(0f, maxScrollY) },
                lineTopPx = ::lineTopPx,
                lineHeightPx = lineHeightPx,
                refBaselinePx = refBaselinePx,
                layoutFor = ::layoutFor,
                textMeasurer = measurer,
                textStyle = textStyle,
                numberStyle = numberStyle,
                charW = charWpx,
                isGridLine = ::isGridLine,
                gridRefBaseline = gridRefBaseline,
                gridRefCursorTop = gridRefCursor.top,
                gridRefCursorBottom = gridRefCursor.bottom,
                gutterWidthPx = gutterWidthPx,
                padXPx = padXPx,
            )
        }
        // 底部符号快捷条：只读不显示。点键只把 value 交 engine 插到光标处，**不主动聚焦/弹键盘**：
        // 键用 detectTapGestures 不抢焦点——键盘开着时点键焦点不丢、IME 会话不销毁、键盘照旧不收；
        // 键盘没开时点键也就不会意外唤起输入法（仅把符号落到光标）。insets 交给它自管底部沉浸。
        if (showSymbolBar) {
            SymbolBar(
                symbols = symbols,
                colors = colors,
                onSymbol = { symbol -> engine.insert(symbol.value) },
                windowInsets = bottomBarInsets,
            )
        }
    }
}

/**
 * 选区拖拽的边缘自动滚动（单维度，纵横通用）：拖拽点 [pos] 进入近端/远端「热区」时返回每帧滚动步长
 * （近端为负、远端为正），步长随进入热区的深度（含越过边缘）线性放大，上限 3 倍。不在热区返回 0。
 * [extent] 为该维度视口尺寸，[unit] 为步长基准像素（用行高即可）。
 */
private fun edgeAutoScrollSpeed(pos: Float, extent: Float, unit: Float): Float {
    if (extent <= 0f) return 0f
    val hot = unit * 2.5f
    val maxStep = unit * 0.6f
    return when {
        pos < hot -> -maxStep * ((hot - pos) / hot).coerceIn(0f, 3f)
        pos > extent - hot -> maxStep * ((pos - (extent - hot)) / hot).coerceIn(0f, 3f)
        else -> 0f
    }
}
