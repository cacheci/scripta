package top.yukonga.scripta.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberScrollableState
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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

    val textStyle = remember(colors) { TextStyle(color = colors.foreground, fontFamily = FontFamily.Monospace, fontSize = 14.sp, lineHeight = 20.sp) }
    val numberStyle = remember(colors) { TextStyle(color = colors.gutterForeground, fontFamily = FontFamily.Monospace, fontSize = 13.sp, lineHeight = 20.sp) }
    val lineHeightPx = with(density) { 20.sp.toPx() }
    val padXPx = with(density) { 8.dp.toPx() }

    // 订阅编辑版本，驱动重组（layoutFor 内按行内容失效缓存）
    val version = engine.buffer.version
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

    // 视觉行索引：仅换行模式使用；行数/宽度/模式变化即重建（未测量的行按 1 行估算）。
    val rowIndex = remember(softWrap, widthBucket, lineCount) { VisualRowIndex(lineCount) }

    // 逐行 layout 缓存（内容/宽度/模式变化即失效）。换行时带宽度约束测量并回填视觉行数。
    val layoutCache = remember(version, softWrap, widthBucket) { HashMap<Int, Pair<String, TextLayoutResult>>() }
    fun layoutFor(line: Int): TextLayoutResult? {
        if (line < 0 || line >= engine.buffer.lineCount) return null
        val content = engine.buffer.lineText(line)
        layoutCache[line]?.let { if (it.first == content) return it.second }
        val measured = if (softWrap) {
            measurer.measure(content, textStyle, softWrap = true, constraints = Constraints(maxWidth = textAreaWidthPx.toInt().coerceAtLeast(1)))
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

    // 预测量可见窗口（组合阶段填充缓存 + 行索引），并求不换行下最宽行以定横向滚动范围。
    val estFirst = (lineAtPx(scrollY) - 3).coerceAtLeast(0)
    val approxRows = (viewportHeight / lineHeightPx).toInt() + 8
    val measureEnd = (estFirst + approxRows).coerceAtMost((lineCount - 1).coerceAtLeast(0))
    var maxLineWidth = 0f
    for (ln in estFirst..measureEnd) {
        val l = layoutFor(ln)
        if (!softWrap) maxLineWidth = maxOf(maxLineWidth, l?.size?.width?.toFloat() ?: 0f)
    }

    val contentHeight = (if (softWrap) rowIndex.totalRows() else lineCount) * lineHeightPx
    val maxScrollY = (contentHeight - viewportHeight).coerceAtLeast(0f)
    val clampedScrollY = scrollY.coerceIn(0f, maxScrollY)
    val maxScrollX = if (softWrap) 0f else (gutterWidthPx + padXPx * 2 + maxLineWidth - viewportWidth).coerceAtLeast(0f)
    val clampedScrollX = scrollX.coerceIn(0f, maxScrollX)
    val firstVisibleLine = (lineAtPx(clampedScrollY) - 3).coerceAtLeast(0)

    val vScroll = rememberScrollableState { delta ->
        val c = (scrollY - delta).coerceIn(0f, maxScrollY); val moved = scrollY - c; scrollY = c; moved
    }
    val hScroll = rememberScrollableState { delta ->
        val c = (scrollX - delta).coerceIn(0f, maxScrollX); val moved = scrollX - c; scrollX = c; moved
    }

    // 选区拖拽状态（提升到组合级，供边缘自动滚动 effect 读取）。
    var selectionAnchor by remember { mutableStateOf<TextPosition?>(null) }
    var selectionDragPos by remember { mutableStateOf<Offset?>(null) }

    var blink by remember { mutableStateOf(true) }
    LaunchedEffect(engine.selection, readOnly) {
        blink = true
        if (!readOnly) while (true) { delay(500.milliseconds); blink = !blink }
    }

    LaunchedEffect(engine.selection, viewportHeight) {
        if (viewportHeight <= 0f) return@LaunchedEffect
        if (selectionDragPos != null) return@LaunchedEffect // 选区拖拽时由边缘自动滚动接管
        val caretTop = lineTopPx(engine.selEnd.line)
        val caretBottom = caretTop + rowsOf(engine.selEnd.line) * lineHeightPx
        if (caretTop < scrollY) scrollY = caretTop
        else if (caretBottom > scrollY + viewportHeight) scrollY = caretBottom - viewportHeight
    }

    // 手势闭包由 pointerInput(engine) 固定、不随滚动重启，会按值捕获几何量。用 rememberUpdatedState
    // 让命中测试读到「当前帧」的滚动与 gutter 宽度，否则滚动后点击会命中错误行、并触发自动滚动跳变。
    val hitScrollY = rememberUpdatedState(clampedScrollY)
    val hitScrollX = rememberUpdatedState(clampedScrollX)
    val hitGutterWidth = rememberUpdatedState(gutterWidthPx)

    fun positionAtWithScroll(offset: Offset, sY: Float, sX: Float): TextPosition {
        val ln = lineAtPx(offset.y + sY)
        val layout = layoutFor(ln)
        val localX = (offset.x - hitGutterWidth.value - padXPx + sX).coerceAtLeast(0f)
        val localY = ((offset.y + sY) - lineTopPx(ln)).coerceAtLeast(0f)
        val col = layout?.getOffsetForPosition(Offset(localX, localY)) ?: 0
        return TextPosition(ln, col.coerceAtMost(engine.buffer.lineLength(ln)))
    }

    fun positionAt(offset: Offset): TextPosition = positionAtWithScroll(offset, hitScrollY.value, hitScrollX.value)

    // 选区拖拽到视口上/下热区时，按帧持续滚动并同步延伸选区；速度随进入热区的深度线性增大。
    LaunchedEffect(selectionDragPos != null) {
        if (selectionDragPos == null) return@LaunchedEffect
        while (true) {
            val pos = selectionDragPos ?: break
            val anchor = selectionAnchor ?: break
            val step = edgeAutoScrollSpeed(pos.y, viewportHeight, lineHeightPx)
            if (step != 0f && maxScrollY > 0f) {
                val newY = (scrollY + step).coerceIn(0f, maxScrollY)
                if (newY != scrollY) {
                    scrollY = newY
                    engine.setSelection(anchor, positionAtWithScroll(pos, newY, scrollX.coerceIn(0f, maxScrollX)))
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
            .editorTextInput(engine, enabled = !readOnly)
            .onKeyEvent { ev ->
                if (ev.type != KeyEventType.KeyDown) return@onKeyEvent false
                if (ev.isCtrlPressed) {
                    when (ev.key) {
                        Key.A -> { engine.selectAll(); true }
                        Key.C -> { engine.selectedText()?.let { clipboard.setText(AnnotatedString(it)) }; true }
                        Key.X -> { engine.selectedText()?.let { clipboard.setText(AnnotatedString(it)); engine.replaceSelection("") }; true }
                        Key.V -> { clipboard.getText()?.text?.let { engine.insert(it) }; true }
                        else -> false
                    }
                } else {
                    val shift = ev.isShiftPressed
                    when (ev.key) {
                        Key.DirectionLeft -> { engine.moveCaretHorizontally(-1, shift); true }
                        Key.DirectionRight -> { engine.moveCaretHorizontally(1, shift); true }
                        Key.DirectionUp -> { engine.moveCaretVertically(-1, shift); true }
                        Key.DirectionDown -> { engine.moveCaretVertically(1, shift); true }
                        Key.Backspace -> { if (!readOnly) engine.backspace(); true }
                        Key.Delete -> { if (!readOnly) engine.deleteForward(); true }
                        Key.Enter, Key.NumPadEnter -> { if (!readOnly) engine.insert("\n"); true }
                        else -> false
                    }
                }
            }
            .focusRequester(focusRequester)
            .focusable(interactionSource = interaction)
            .then(
                if (readOnly) Modifier else Modifier.pointerInput(engine) {
                    detectTapGestures(
                        onTap = { p -> engine.setCursor(positionAt(p)); focusRequester.requestFocus(); engine.requestShowKeyboard?.invoke() },
                        onDoubleTap = { p -> val w = engine.wordRangeAt(positionAt(p)); engine.setSelection(w.start, w.end); focusRequester.requestFocus() },
                    )
                }
            )
            .then(
                if (readOnly) Modifier else Modifier.pointerInput(engine) {
                    // 长按才进入选择：长按处先选中该词，随后拖拽扩展选区。普通拖拽不在此消费，
                    // 于是落到上面的 scrollable 去滚动页面（移动端标准行为）。拖到上下边缘由
                    // selectionDragPos 驱动的边缘自动滚动 effect 接管。
                    detectDragGesturesAfterLongPress(
                        onDragStart = { p ->
                            val pos = positionAt(p)
                            selectionAnchor = pos
                            val w = engine.wordRangeAt(pos)
                            engine.setSelection(w.start, w.end)
                            focusRequester.requestFocus()
                            selectionDragPos = p
                        },
                        onDrag = { change, _ ->
                            selectionDragPos = change.position
                            selectionAnchor?.let { engine.setSelection(it, positionAt(change.position)) }
                        },
                        onDragEnd = { selectionDragPos = null },
                        onDragCancel = { selectionDragPos = null },
                    )
                }
            )
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
            scrollX = clampedScrollX,
            scrollY = clampedScrollY,
            firstVisibleLine = firstVisibleLine,
            lineTopPx = ::lineTopPx,
            caretVisible = !readOnly && blink,
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
