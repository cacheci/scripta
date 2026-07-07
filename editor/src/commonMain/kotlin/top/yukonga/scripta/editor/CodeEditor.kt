package top.yukonga.scripta.editor

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.overscroll
import androidx.compose.foundation.rememberOverscrollEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import top.yukonga.scripta.editor.input.editorTextInput
import top.yukonga.scripta.editor.input.plainText
import top.yukonga.scripta.editor.input.plainTextClipEntry
import top.yukonga.scripta.editor.render.CursorOverlay
import top.yukonga.scripta.editor.render.EditorCanvas
import top.yukonga.scripta.editor.render.EditorGeometry
import top.yukonga.scripta.editor.render.HandleKind
import top.yukonga.scripta.editor.render.VisualRowIndex
import top.yukonga.scripta.editor.text.TextPosition
import top.yukonga.scripta.editor.text.TextRange
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

/** 超过此字符数的行走「网格」快路：不整行 shaping，只按可见列窗口等宽算术定位/绘制（M2，仅不换行）。 */
private const val LONG_LINE_THRESHOLD = 2000

/** 双指缩放字号量化档距（sp）：连续缩放落到此档才改字号，把逐事件重排压到过档才一次、消抖动（P3）。 */
private const val ZOOM_STEP_SP = 0.5f

/**
 * layoutFor 逐行缓存条目。[validatedVersion] 记「上次确认与文档一致时的 buffer.version」：命中且版本相等即
 * 说明此后无任何编辑、本行文本必与 [content] 相同，可零分配零比较直接复用 [layout]（稳态滚动/闪烁/拖选帧全
 * 走这里）；版本变了才回退按 [content] 比对——编辑他行不改本行文本，仍命中并刷新版本戳，唯内容不符才重测。
 */
private class CachedLayout(var validatedVersion: Int, val content: String, val layout: TextLayoutResult)

/**
 * 虚拟化代码编辑器入口。自绘 + 视口虚拟化 + 自管 IME（Android），不使用 BasicTextField。
 *
 * 宿主须为编辑器容器消费 IME insets（如 `Modifier.imePadding()`），使可用视口高度反映键盘弹出后的
 * 可见高度——否则光标随动会把光标滚到键盘下面。
 */
@OptIn(ExperimentalFoundationApi::class) // scrollable2D / rememberScrollable2DState 仍为实验 API
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

    // 双指缩放调整的字号（sp）。行高、gutter、layout 随之联动重算。缩放手势把连续比例量化到 ZOOM_STEP_SP 一档，
    // 只在过档时改一次（见下方缩放 pointerInput）——避免逐事件全链 remember 重建 + 整屏重 shaping 的连续抖动（P3）。
    var fontSizeSp by remember { mutableFloatStateOf(14f) }
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
            platformStyle = platformTextStyle
        )
    }
    val numberStyle = remember(colors, fontSizeSp) {
        TextStyle(
            color = colors.gutterForeground,
            fontFamily = FontFamily.Monospace,
            fontSize = (fontSizeSp - 1f).coerceAtLeast(6f).sp,
            lineHeight = lineHeightSp.sp,
            lineHeightStyle = lineHeightStyle,
            platformStyle = platformTextStyle
        )
    }
    val lineHeightPx = with(density) { lineHeightSp.sp.toPx() }
    val padXPx = with(density) { 8.dp.toPx() }
    // 拖动手柄半径与命中外扩（细光标线太小，靠 slop 放大可抓区域）。
    val handleRadiusPx = with(density) { 8.dp.toPx() }
    val handleSlopPx = with(density) { 10.dp.toPx() }
    // 统一基线：以拉丁参考行的 firstBaseline 为目标，各行绘制时按差值平移，抵消 CJK/拉丁字体度量差
    // 导致的整行基线偏移（含中文的行里英文向下偏移）。
    val refBaselinePx = remember(textStyle) { measurer.measure("Ag", textStyle).firstBaseline }

    // M2：等宽字符宽 + 超长「网格行」判定。超阈值的行不整行 shaping，只按可见列窗口做等宽算术定位/绘制
    // （仅不换行；softWrap 下仍走整行折行测量）。charW = 参考字符 "0" 的 advance；垂直度量复用其 cursorRect。
    val charWpx = remember(textStyle) { measurer.measure("0", textStyle).size.width.toFloat() }
    val gridRef = remember(textStyle) { measurer.measure("0", textStyle) }
    val gridRefBaseline = gridRef.firstBaseline
    val gridRefCursor = remember(gridRef) { gridRef.getCursorRect(0) }
    fun isGridLine(line: Int): Boolean =
        !softWrap && charWpx > 0f && engine.buffer.lineLength(line) > LONG_LINE_THRESHOLD

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
    // 横向范围以「已测量过的最宽行」为准、只增不减（字号/宽度/换行模式变化时随 layout 缓存一起重置）。
    // 若只按当前可见窗口最宽行算，长行纵向滚出可见区后 maxScrollX 会骤缩、把 scrollX 夹回左侧，视口瞬移；
    // 只增不减则保持稳定横向范围（同 Monaco 维护 longest line 的效果）。非 state，写读均在组合内、不触发重组。
    // 加 engine.contentGeneration 作 key：换文档时重置横向范围，避免旧文档最宽行（如超长行）残留、
    // 让新文档也能右滚特别多。同文档编辑不改 generation，仍保持「只增不减」（M7）。
    val widestSeen = remember(softWrap, widthBucket, fontSizeSp, engine.contentGeneration) { floatArrayOf(0f) }
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
    val maxScrollY = (contentHeight - viewportHeight).coerceAtLeast(0f)
    val maxScrollX = if (softWrap) 0f else (gutterWidthPx + padXPx * 2 + widestSeen[0] - viewportWidth).coerceAtLeast(0f)

    // 视口变大 / 内容变短使 maxScroll 缩小时，把滚动量夹回范围内。否则在文档底部弹出输入法（视口被 imePadding
    // 压小、scrollY 被 keep-in-view 推大）后收起输入法，视口复原、maxScrollY 骤减，而 scrollY 残留旧大值：
    // firstVisibleLine 读未夹的 scrollY 会指到更靠下的行，绘制偏移却读已夹的 scrollY，二者错位 → 上方留空白。
    LaunchedEffect(maxScrollY, maxScrollX) {
        scrollY = scrollY.coerceIn(0f, maxScrollY)
        scrollX = scrollX.coerceIn(0f, maxScrollX)
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
    var caretHandleToken by remember { mutableStateOf(0) }
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

    // keep-in-view：光标随选区/视口变化滚动露出。用 snapshotFlow 观察 (selection, viewport)，不把 selection
    // 当组合期 effect key（P1）；Triple 在选区或视口任一变化时 emit（覆盖原 key 集），emit 频率为「每次变化」
    // 而非逐帧，IME 动画期也只是逐帧 emit、无协程 cancel/relaunch。
    LaunchedEffect(Unit) {
        snapshotFlow { Triple(engine.selection, viewportHeight, viewportWidth) }.collect {
            if (viewportHeight <= 0f) return@collect
            if (selectionDragActive) return@collect // 选区拖拽时由边缘自动滚动接管
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
            // 横向随动：不换行时若光标越过左/右缘，滚动露出光标并留一小段余量（换行下 maxScrollX=0，跳过）。
            // 纯纵向导航（目标列生效）时跳过——否则目标列在短/长行间夹变会让视口横向来回 snap，很突兀。
            if (!softWrap && viewportWidth > 0f && !engine.hasGoalColumn) {
                val col = caret.column.coerceAtMost(engine.buffer.lineLength(line))
                val caretX = if (isGridLine(line)) col * charWpx else layoutFor(line)?.getCursorRect(col)?.left
                if (caretX != null) {
                    val textAreaW = (viewportWidth - gutterWidthPx - padXPx * 2).coerceAtLeast(1f)
                    val margin = padXPx * 3
                    if (caretX < scrollX + margin) scrollX = (caretX - margin).coerceIn(0f, maxScrollX)
                    else if (caretX > scrollX + textAreaW - margin) scrollX = (caretX - textAreaW + margin).coerceIn(0f, maxScrollX)
                }
            }
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
            val stepX = edgeAutoScrollSpeed(pos.x, viewportWidth, lineHeightPx)
            val newY = if (stepY != 0f && maxY > 0f) (scrollY + stepY).coerceIn(0f, maxY) else scrollY
            val newX = if (stepX != 0f && maxX > 0f) (scrollX + stepX).coerceIn(0f, maxX) else scrollX
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

    Box(
        modifier
            .background(colors.background)
            .clipToBounds()
            .overscroll(overscroll)
            .onSizeChanged { viewportWidth = it.width.toFloat(); viewportHeight = it.height.toFloat() }
            .scrollable2D(scroll2D, overscrollEffect = overscroll, interactionSource = scrollInteraction)
            .pointerInput(Unit) {
                // 双指缩放调字号：仅在 ≥2 指时消费，单指留给滚动/选择。以双指焦点为锚——焦点下的文档位置在缩放后
                // 仍停在焦点处。为消抖动，把连续缩放「量化到 [ZOOM_STEP_SP] 一档」：手势期间累积连续目标字号 accum，
                // 仅当跨过档位使量化字号真变时才改 fontSizeSp（触发一次重排 + 焦点校正）——把每秒几十次全链重建/重
                // shaping 压到「过档才一次」，档间零重排、无逐帧亚像素抖动。渲染模型不变，行号仍钉屏幕、无缩放伪影。
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    var accum = fontSizeSp // 连续目标字号（不量化）；跨档才落到 fontSizeSp
                    var started = false
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } >= 2) {
                            if (!started) { started = true; accum = fontSizeSp } // 缩放起始：从当前字号起累积
                            val zoom = event.calculateZoom()
                            if (zoom != 1f) {
                                accum = (accum * zoom).coerceIn(8f, 40f)
                                val old = fontSizeSp
                                val next = (accum / ZOOM_STEP_SP).roundToInt() * ZOOM_STEP_SP
                                if (next != old) {
                                    val k = next / old
                                    val c = event.calculateCentroid(useCurrent = true)
                                    // 锚基取「当前显示的钳制值」：下游只在 draw/命中处按 maxScroll 钳制、不回写 state，
                                    // 直接以原始 scrollY/X 为锚会让其溢出上界并累积、反向缩放黏边。用 liveMaxScrollY/X 先钳回显示值。
                                    val baseY = scrollY.coerceIn(0f, liveMaxScrollY.value)
                                    val baseX = scrollX.coerceIn(0f, liveMaxScrollX.value)
                                    fontSizeSp = next
                                    if (c != Offset.Unspecified) {
                                        // 令焦点处内容缩放后不动：newScroll = (base + focal) * k - focal。纵向无 gutter 项、恒精确。
                                        scrollY = ((baseY + c.y) * k - c.y).coerceAtLeast(0f)
                                        // 横向：焦点相对正文起点 = c.x - (gutter + padX)。gutter 宽随字号变（行号字号 = 字号-1），
                                        // 故 base+focal 项用「旧宽 fxOld」、末项用「新宽 fxNew」，否则字号大变时横向漂移。新宽按行号字号
                                        // 比例从 live 旧宽线性推得（等宽 advance ∝ 字号，亚像素误差可忽略），只用 live 值、无需重测。
                                        val gutterOld = hitGutterWidth.value
                                        val numFontOld = (old - 1f).coerceAtLeast(6f)
                                        val numFontNew = (next - 1f).coerceAtLeast(6f)
                                        val gutterNew = (gutterOld - padXPx * 2) * (numFontNew / numFontOld) + padXPx * 2
                                        val fxOld = c.x - (gutterOld + padXPx)
                                        val fxNew = c.x - (gutterNew + padXPx)
                                        scrollX = ((baseX + fxOld) * k - fxNew).coerceAtLeast(0f)
                                    } else {
                                        scrollY = baseY * k
                                    }
                                }
                                event.changes.forEach { it.consume() } // 消费缩放事件，防止落到滚动
                            }
                        }
                    } while (event.changes.any { it.pressed })
                }
            }
            .editorTextInput(engine, enabled = !readOnly)
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

                        else -> false
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
                        drag(down.id) { change -> engine.setCursor(mapped(change.position)); change.consume() }
                        pingCaretHandle() // 抬手后重置 4s 计时
                    } else {
                        val a = anchor!!
                        selectionAnchor = a
                        selectionWordAnchor = null // 端点拖拽走字符级锚
                        selectionDragPos = p
                        selectionDragActive = true
                        engine.setSelection(a, mapped(p))
                        drag(down.id) { change ->
                            selectionDragPos = change.position
                            engine.setSelection(a, mapped(change.position)); change.consume()
                        }
                        selectionDragPos = null
                        selectionDragActive = false
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
            firstVisibleLine = { (lineAtPx(scrollY.coerceIn(0f, maxScrollY)) - 3).coerceAtLeast(0) },
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
            modifier = Modifier.fillMaxSize(),
        )
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
