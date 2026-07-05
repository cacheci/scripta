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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.delay
import top.yukonga.scripta.editor.input.editorTextInput
import top.yukonga.scripta.editor.render.EditorCanvas
import top.yukonga.scripta.editor.render.EditorGeometry
import top.yukonga.scripta.editor.text.TextPosition
import top.yukonga.scripta.editor.viewport.Viewport

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

    // 顶层已读 engine.buffer.version 订阅重组；编辑后按行内容比对失效缓存。
    val layoutCache = remember(version) { HashMap<Int, Pair<String, TextLayoutResult>>() }
    fun layoutFor(line: Int): TextLayoutResult? {
        if (line < 0 || line >= engine.buffer.lineCount) return null
        val content = engine.buffer.lineText(line)
        layoutCache[line]?.let { if (it.first == content) return it.second }
        val measured = measurer.measure(content, textStyle)
        layoutCache[line] = content to measured
        return measured
    }

    val contentHeight = lineCount * lineHeightPx
    val maxScrollY = (contentHeight - viewportHeight).coerceAtLeast(0f)
    val clampedScrollY = scrollY.coerceIn(0f, maxScrollY)
    val visible = Viewport.visibleLines(clampedScrollY, viewportHeight, lineHeightPx, lineCount)
    val maxLineWidth = visible.maxOfOrNull { layoutFor(it)?.size?.width?.toFloat() ?: 0f } ?: 0f
    val maxScrollX = (gutterWidthPx + padXPx * 2 + maxLineWidth - viewportWidth).coerceAtLeast(0f)
    val clampedScrollX = scrollX.coerceIn(0f, maxScrollX)

    val vScroll = rememberScrollableState { delta ->
        val c = (scrollY - delta).coerceIn(0f, maxScrollY); val moved = scrollY - c; scrollY = c; moved
    }
    val hScroll = rememberScrollableState { delta ->
        val c = (scrollX - delta).coerceIn(0f, maxScrollX); val moved = scrollX - c; scrollX = c; moved
    }

    var blink by remember { mutableStateOf(true) }
    LaunchedEffect(engine.selection, readOnly) {
        blink = true
        if (!readOnly) while (true) { delay(500); blink = !blink }
    }

    LaunchedEffect(engine.selection, viewportHeight) {
        if (viewportHeight <= 0f) return@LaunchedEffect
        val caretY = engine.selEnd.line * lineHeightPx
        if (caretY < scrollY) scrollY = caretY
        else if (caretY + lineHeightPx > scrollY + viewportHeight) scrollY = caretY + lineHeightPx - viewportHeight
    }

    // 手势闭包由 pointerInput(engine) 固定、不随滚动重启，会按值捕获几何量。用 rememberUpdatedState
    // 让命中测试读到「当前帧」的滚动与 gutter 宽度，否则滚动后点击会命中错误行、并触发自动滚动跳变。
    val hitScrollY = rememberUpdatedState(clampedScrollY)
    val hitScrollX = rememberUpdatedState(clampedScrollX)
    val hitGutterWidth = rememberUpdatedState(gutterWidthPx)

    fun positionAt(offset: Offset): TextPosition {
        val ln = EditorGeometry.lineAtY(offset.y, hitScrollY.value, lineHeightPx, engine.buffer.lineCount)
        val layout = layoutFor(ln)
        val localX = (offset.x - hitGutterWidth.value - padXPx + hitScrollX.value).coerceAtLeast(0f)
        val col = layout?.getOffsetForPosition(Offset(localX, layout.size.height / 2f)) ?: 0
        return TextPosition(ln, col.coerceAtMost(engine.buffer.lineLength(ln)))
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
                    // 于是落到上面的 scrollable 去滚动页面（移动端标准行为）。
                    var anchor = TextPosition(0, 0)
                    detectDragGesturesAfterLongPress(
                        onDragStart = { p ->
                            anchor = positionAt(p)
                            val w = engine.wordRangeAt(anchor)
                            engine.setSelection(w.start, w.end)
                            focusRequester.requestFocus()
                        },
                        onDrag = { change, _ -> engine.setSelection(anchor, positionAt(change.position)) },
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
            visibleRange = visible,
            caretVisible = !readOnly && blink,
            layoutFor = ::layoutFor,
            modifier = Modifier.fillMaxSize(),
        )
    }
}
