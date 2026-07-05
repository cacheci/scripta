package top.yukonga.scripta.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateCentroid
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import top.yukonga.scripta.editor.input.editorTextInput
import top.yukonga.scripta.editor.render.EditorCanvas
import top.yukonga.scripta.editor.render.EditorGeometry
import top.yukonga.scripta.editor.render.VisualRowIndex
import top.yukonga.scripta.editor.text.TextPosition
import kotlin.time.Duration.Companion.milliseconds

/**
 * 虚拟化代码编辑器入口。自绘 + 视口虚拟化 + 自管 IME（Android），不使用 BasicTextField。
 *
 * 宿主须为编辑器容器消费 IME insets（如 `Modifier.imePadding()`），使可用视口高度反映键盘弹出后的
 * 可见高度——否则光标随动会把光标滚到键盘下面。
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
) {
    val engine = controller.engine
    LaunchedEffect(initialText) { controller.setText(initialText) }

    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val focusRequester = remember { FocusRequester() }
    val interaction = remember { MutableInteractionSource() }
    val clipboard = LocalClipboardManager.current

    // 双指缩放调整的字号（sp）。行高、gutter、layout 随之联动重算。
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
    // 统一基线：以拉丁参考行的 firstBaseline 为目标，各行绘制时按差值平移，抵消 CJK/拉丁字体度量差
    // 导致的整行基线偏移（含中文的行里英文向下偏移）。
    val refBaselinePx = remember(textStyle) { measurer.measure("Ag", textStyle).firstBaseline }

    // 读取 buffer.version（快照 state）订阅编辑：内容/行数变化后整体重组，gutter 宽、内容高、可见窗口随之
    // 刷新。此读取本身即订阅，勿删。逐行 layout 缓存不再以它失效（否则每键整表重测），失效见 layoutFor。
    engine.buffer.version
    val lineCount = engine.buffer.lineCount

    val gutterDigits = EditorGeometry.gutterDigits(lineCount)
    val gutterWidthPx = remember(gutterDigits, numberStyle) {
        measurer.measure("0".repeat(gutterDigits), numberStyle).size.width + padXPx * 2
    }

    var scrollY by remember { mutableStateOf(0f) }
    var scrollX by remember { mutableStateOf(0f) }
    var viewportWidth by remember { mutableStateOf(0f) }
    var viewportHeight by remember { mutableStateOf(0f) }

    // 换行模式下正文可用宽度（测量宽度约束）。
    val textAreaWidthPx = (viewportWidth - gutterWidthPx - padXPx * 2).coerceAtLeast(1f)
    val widthBucket = if (softWrap) textAreaWidthPx.toInt() else 0

    // 视觉行索引：仅换行模式使用；行数/宽度/模式/字号变化即重建（未测量的行按 1 行估算）。
    val rowIndex = remember(softWrap, widthBucket, lineCount, fontSizeSp) { VisualRowIndex(lineCount) }

    // 逐行 layout 缓存：只在宽度/模式/字号变化时整表失效，不再以 version 失效——否则每敲一个字符整表丢弃、
    // 可见行全部重测。失效改由下方按行内容比对精确处理（内容变了才重测；插入/删除行的下标平移也会因内容
    // 不符自然重测）。超上限即清空，防止长时间滚动大文件无界增长。
    val layoutCache = remember(softWrap, widthBucket, fontSizeSp) { HashMap<Int, Pair<String, TextLayoutResult>>() }
    fun layoutFor(line: Int): TextLayoutResult? {
        if (line < 0 || line >= engine.buffer.lineCount) return null
        val content = engine.buffer.lineText(line)
        layoutCache[line]?.let {
            if (it.first == content) {
                // 命中也回填视觉行数：rowIndex 仍以 lineCount 为 key、行数变化时会重建为 1 行估算，而命中
                // 分支不经下面的 setRows，需在此补上，否则软换行的行高/定位会退回估算值。
                if (softWrap) rowIndex.setRows(line, it.second.lineCount)
                return it.second
            }
        }
        if (layoutCache.size > 4096) layoutCache.clear()
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
        layoutCache[line] = content to measured
        if (softWrap) rowIndex.setRows(line, measured.lineCount)
        return measured
    }

    // 行 -> 顶部像素 / 像素 -> 行（换行走视觉行索引，不换行走平凡公式，行为不变）。
    fun lineTopPx(line: Int): Float = if (softWrap) rowIndex.rowsBefore(line) * lineHeightPx else line * lineHeightPx
    fun rowsOf(line: Int): Int = if (softWrap) rowIndex.rows(line) else 1
    fun lineAtPx(y: Float): Int {
        val row = (y / lineHeightPx).toInt().coerceAtLeast(0)
        return if (softWrap) rowIndex.lineAtRow(row) else row.coerceIn(0, (lineCount - 1).coerceAtLeast(0))
    }

    // 量化滚动：组合只订阅「首个可见行」，跨行才重组；同一行内的滚动仅由 EditorCanvas 在 draw 阶段读
    // 像素级 scrollY/scrollX 平滑重绘，不再每滚 1px 全量重组。derivedStateOf 仅在整数结果变化时通知读者。
    val scrollLine by remember(rowIndex, lineHeightPx, softWrap) { derivedStateOf { lineAtPx(scrollY) } }

    // 预测量可见窗口（组合阶段填充缓存 + 行索引），并求不换行下最宽行以定横向滚动范围。
    val firstVisibleLine = (scrollLine - 3).coerceAtLeast(0)
    val approxRows = (viewportHeight / lineHeightPx).toInt() + 8
    val measureEnd = (firstVisibleLine + approxRows).coerceAtMost((lineCount - 1).coerceAtLeast(0))
    var maxLineWidth = 0f
    for (ln in firstVisibleLine..measureEnd) {
        val l = layoutFor(ln)
        if (!softWrap) maxLineWidth = maxOf(maxLineWidth, l?.size?.width?.toFloat() ?: 0f)
    }

    val contentHeight = (if (softWrap) rowIndex.totalRows() else lineCount) * lineHeightPx
    val maxScrollY = (contentHeight - viewportHeight).coerceAtLeast(0f)
    val maxScrollX = if (softWrap) 0f else (gutterWidthPx + padXPx * 2 + maxLineWidth - viewportWidth).coerceAtLeast(0f)

    val vScroll = rememberScrollableState { delta ->
        val c = (scrollY - delta).coerceIn(0f, maxScrollY)
        val moved = scrollY - c; scrollY = c; moved
    }
    val hScroll = rememberScrollableState { delta ->
        val c = (scrollX - delta).coerceIn(0f, maxScrollX)
        val moved = scrollX - c; scrollX = c; moved
    }

    // 选区拖拽状态（提升到组合级，供边缘自动滚动 effect 读取）。
    var selectionAnchor by remember { mutableStateOf<TextPosition?>(null) }
    var selectionDragPos by remember { mutableStateOf<Offset?>(null) }

    var blink by remember { mutableStateOf(true) }
    LaunchedEffect(engine.selection, readOnly) {
        blink = true
        if (!readOnly) while (true) {
            delay(500.milliseconds); blink = !blink
        }
    }

    LaunchedEffect(engine.selection, viewportHeight) {
        if (viewportHeight <= 0f) return@LaunchedEffect
        if (selectionDragPos != null) return@LaunchedEffect // 选区拖拽时由边缘自动滚动接管
        val caretTop = lineTopPx(engine.selEnd.line)
        val caretBottom = caretTop + rowsOf(engine.selEnd.line) * lineHeightPx
        if (caretTop < scrollY) scrollY = caretTop
        else if (caretBottom > scrollY + viewportHeight) scrollY = caretBottom - viewportHeight
    }

    // 手势闭包由 pointerInput(engine) 固定、不随滚动重启，会按值捕获几何量。gutter 宽用 rememberUpdatedState
    // 提到当前帧；滚动量则在 positionAt 里直接读活的 scrollY/scrollX——既让命中测试始终对齐当前滚动，
    // 又不因组合阶段读取滚动量而每滚 1px 重组。
    val hitGutterWidth = rememberUpdatedState(gutterWidthPx)

    fun positionAtWithScroll(offset: Offset, sY: Float, sX: Float): TextPosition {
        val ln = lineAtPx(offset.y + sY)
        val layout = layoutFor(ln)
        val localX = (offset.x - hitGutterWidth.value - padXPx + sX).coerceAtLeast(0f)
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

    // 边缘自动滚动 effect 的 key 是 Boolean、不随重组刷新，循环体会按值捕获滚动上限。软换行下自动滚动
    // 驶入未测量区域时真实 maxScrollY 会随测量增大，用 rememberUpdatedState 让循环读到当前帧上限，
    // 否则会停在旧估算的「假底部」、选不到文档末尾（与 hitScrollY 同类修复）。
    val liveMaxScrollY = rememberUpdatedState(maxScrollY)
    val liveMaxScrollX = rememberUpdatedState(maxScrollX)

    // 选区拖拽到视口上/下热区时，按帧持续滚动并同步延伸选区；速度随进入热区的深度线性增大。
    LaunchedEffect(selectionDragPos != null) {
        if (selectionDragPos == null) return@LaunchedEffect
        while (true) {
            val pos = selectionDragPos ?: break
            val anchor = selectionAnchor ?: break
            val maxY = liveMaxScrollY.value
            val step = edgeAutoScrollSpeed(pos.y, viewportHeight, lineHeightPx)
            if (step != 0f && maxY > 0f) {
                val newY = (scrollY + step).coerceIn(0f, maxY)
                if (newY != scrollY) {
                    scrollY = newY
                    engine.setSelection(anchor, positionAtWithScroll(pos, newY, scrollX.coerceIn(0f, liveMaxScrollX.value)))
                }
            }
            withFrameNanos { }
        }
    }

    Box(
        modifier
            .background(colors.background)
            .clipToBounds()
            .onSizeChanged { viewportWidth = it.width.toFloat(); viewportHeight = it.height.toFloat() }
            .scrollable(vScroll, Orientation.Vertical)
            .scrollable(hScroll, Orientation.Horizontal)
            .pointerInput(Unit) {
                // 双指缩放调字号：仅在 ≥2 指时消费，单指留给滚动/选择。以双指焦点为锚——焦点下的文档
                // 位置在缩放后仍停在焦点处（而非锚住视口顶/左，否则想放大看的那行会滑离手指）。
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        if (event.changes.count { it.pressed } >= 2) {
                            val zoom = event.calculateZoom()
                            if (zoom != 1f) {
                                val old = fontSizeSp
                                val next = (old * zoom).coerceIn(8f, 40f)
                                if (next != old) {
                                    val k = next / old
                                    val c = event.calculateCentroid(useCurrent = true)
                                    // 锚基取「当前显示的钳制值」：下游只在 draw/命中处按 maxScroll 钳制、不回写 state，
                                    // 若直接以原始 scrollY/X 为锚，边缘缩放会让其溢出上界并逐帧累积，反向缩放时黏在
                                    // 边缘不跟随焦点。用 liveMaxScrollY/X 先钳回显示值再代入焦点公式。
                                    val baseY = scrollY.coerceIn(0f, liveMaxScrollY.value)
                                    val baseX = scrollX.coerceIn(0f, liveMaxScrollX.value)
                                    fontSizeSp = next
                                    if (c != Offset.Unspecified) {
                                        // 令焦点处内容缩放后不动：newScroll = (base + focal) * k - focal。
                                        scrollY = ((baseY + c.y) * k - c.y).coerceAtLeast(0f)
                                        // 焦点相对正文起点；gutter 常量项随字号变、此处用旧宽近似，字号大变时横向有微量漂移（纵向精确）。
                                        val fx = c.x - (hitGutterWidth.value + padXPx)
                                        scrollX = ((baseX + fx) * k - fx).coerceAtLeast(0f)
                                    } else {
                                        scrollY = baseY * k
                                    }
                                }
                                event.changes.forEach { it.consume() }
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
                            engine.selectedText()?.let { clipboard.setText(AnnotatedString(it)) }; true
                        }
                        // 剪切/粘贴会改动文档，只读时消费事件但不执行。
                        Key.X -> {
                            if (!readOnly) engine.selectedText()
                                ?.let { clipboard.setText(AnnotatedString(it)); engine.replaceSelection("") }; true
                        }

                        Key.V -> {
                            if (!readOnly) clipboard.getText()?.text?.let { engine.insert(it) }; true
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
                            engine.moveCaretVertically(-1, shift); true
                        }

                        Key.DirectionDown -> {
                            engine.moveCaretVertically(1, shift); true
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
                    if (!readOnlyLive.value) engine.requestShowKeyboard?.invoke()
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
                        val pos = positionAtLive.value(p)
                        selectionAnchor = pos
                        val w = engine.wordRangeAt(pos)
                        engine.setSelection(w.start, w.end)
                        focusRequester.requestFocus()
                        selectionDragPos = p
                    },
                    onDrag = { change, _ ->
                        selectionDragPos = change.position
                        selectionAnchor?.let { engine.setSelection(it, positionAtLive.value(change.position)) }
                    },
                    onDragEnd = { selectionDragPos = null },
                    onDragCancel = { selectionDragPos = null },
                )
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
            scrollX = { scrollX.coerceIn(0f, maxScrollX) },
            scrollY = { scrollY.coerceIn(0f, maxScrollY) },
            firstVisibleLine = { (lineAtPx(scrollY) - 3).coerceAtLeast(0) },
            lineTopPx = ::lineTopPx,
            refBaselinePx = refBaselinePx,
            caretVisible = { !readOnly && blink },
            layoutFor = ::layoutFor,
            modifier = Modifier.fillMaxSize(),
        )
    }
}

/**
 * 选区拖拽的边缘自动滚动：finger 进入顶部/底部「热区」时返回每帧滚动步长（顶部为负、底部为正），
 * 步长随进入热区的深度（含越过边缘）线性放大，上限 3 倍。不在热区返回 0。
 */
private fun edgeAutoScrollSpeed(y: Float, viewportHeight: Float, lineHeight: Float): Float {
    if (viewportHeight <= 0f) return 0f
    val hot = lineHeight * 2.5f
    val maxStep = lineHeight * 0.6f
    return when {
        y < hot -> -maxStep * ((hot - y) / hot).coerceIn(0f, 3f)
        y > viewportHeight - hot -> maxStep * ((y - (viewportHeight - hot)) / hot).coerceIn(0f, 3f)
        else -> 0f
    }
}
