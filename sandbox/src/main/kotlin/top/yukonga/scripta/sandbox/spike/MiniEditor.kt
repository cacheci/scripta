package top.yukonga.scripta.sandbox.spike

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.focusable
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * SPIKE editor surface: a self-drawn, multi-line, editable Canvas — deliberately NOT virtualized and
 * NOT a BasicTextField. It proves the two highest-risk pieces of the plan on one screen:
 *   1. self-managed Android IME (via [miniTextInput]) — Chinese composing, commit, backspace, caret;
 *   2. cross-line selection — drag / double-tap word / select-all, drawn as an overlay.
 *
 * Geometry is intentionally the "fixed line height -> O(1) positioning" model the real editor will
 * use: each line is measured independently and placed at `line * lineHeight`, so nothing here scales
 * with document size except the per-frame line split (fine for the handful of spike lines).
 */
@Composable
fun MiniEditor(
    state: MiniEditorState,
    modifier: Modifier = Modifier,
    foreground: Color = Color(0xFFE0E0E0),
    cursorColor: Color = Color(0xFF4FC3F7),
    selectionColor: Color = Color(0x553A6DA0),
    composingColor: Color = Color(0xFF4FC3F7),
) {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val focusRequester = remember { FocusRequester() }
    val interactionSource = remember { MutableInteractionSource() }

    val textStyle = remember {
        TextStyle(color = foreground, fontFamily = FontFamily.Monospace, fontSize = 16.sp, lineHeight = 22.sp)
    }
    val lineHeightPx = remember(density) { with(density) { 22.sp.toPx() } }
    val padX = remember(density) { with(density) { 12.dp.toPx() } }
    val newlineMarkerWidth = remember(density) { with(density) { 6.dp.toPx() } }

    // Read text in composition so layouts recompute only when the document changes.
    val text = state.text
    val lineIndex = remember(text) { LineIndex(text) }
    val layouts: List<TextLayoutResult> = remember(text, textStyle) {
        lineIndex.lines.map { measurer.measure(it, textStyle) }
    }

    // Map a local Canvas position to a document offset (used by tap / drag).
    fun posToOffset(pos: Offset): Int {
        val line = (pos.y / lineHeightPx).toInt().coerceIn(0, lineIndex.lineCount - 1)
        val layout = layouts[line]
        val localX = (pos.x - padX).coerceAtLeast(0f)
        val col = layout.getOffsetForPosition(Offset(localX, layout.size.height / 2f))
        return lineIndex.lineStart(line) + col.coerceAtMost(lineIndex.lineLength(line))
    }

    fun verticalMove(dir: Int, extend: Boolean) {
        val caret = state.selEnd
        val line = lineIndex.lineOf(caret)
        val col = caret - lineIndex.lineStart(line)
        val target = (line + dir).coerceIn(0, lineIndex.lineCount - 1)
        val targetCol = col.coerceAtMost(lineIndex.lineLength(target))
        val newOffset = lineIndex.lineStart(target) + targetCol
        if (extend) state.setSelection(state.selStart, newOffset) else state.setCursor(newOffset)
    }

    Canvas(
        modifier = modifier
            .miniTextInput(state)
            .onKeyEvent { event ->
                if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                val shift = event.isShiftPressed
                when (event.key) {
                    Key.DirectionLeft -> { state.moveCaretHorizontally(-1, shift); true }
                    Key.DirectionRight -> { state.moveCaretHorizontally(1, shift); true }
                    Key.DirectionUp -> { verticalMove(-1, shift); true }
                    Key.DirectionDown -> { verticalMove(1, shift); true }
                    Key.Backspace -> { state.backspace(); true }
                    Key.Delete -> { state.deleteForward(); true }
                    Key.Enter, Key.NumPadEnter -> { state.insert("\n"); true }
                    else -> {
                        val cp = event.nativeKeyEvent.unicodeChar
                        if (cp != 0) { state.insert(String(Character.toChars(cp))); true } else false
                    }
                }
            }
            .focusRequester(focusRequester)
            .focusable(interactionSource = interactionSource)
            .pointerInput(state, lineIndex) {
                detectTapGestures(
                    onTap = { pos ->
                        state.setCursor(posToOffset(pos))
                        focusRequester.requestFocus()
                        state.requestShowKeyboard?.invoke()
                    },
                    onDoubleTap = { pos ->
                        val offset = posToOffset(pos)
                        val line = lineIndex.lineOf(offset)
                        val local = offset - lineIndex.lineStart(line)
                        val word = layouts[line].getWordBoundary(local)
                        state.setSelection(
                            lineIndex.lineStart(line) + word.start,
                            lineIndex.lineStart(line) + word.end,
                        )
                        focusRequester.requestFocus()
                    },
                )
            }
            .pointerInput(state, lineIndex) {
                var anchor = 0
                detectDragGestures(
                    onDragStart = { pos ->
                        anchor = posToOffset(pos)
                        state.setCursor(anchor)
                        focusRequester.requestFocus()
                    },
                    onDrag = { change, _ ->
                        state.setSelection(anchor, posToOffset(change.position))
                    },
                )
            },
    ) {
        val selS = state.selStart
        val selE = state.selEnd
        val cS = state.composingStart
        val cE = state.composingEnd

        // 1) Selection overlay — drawn under the glyphs, spanning every line it touches.
        if (selS != selE) {
            val startLine = lineIndex.lineOf(selS)
            val endLine = lineIndex.lineOf(selE)
            for (ln in startLine..endLine) {
                val layout = layouts[ln]
                val lnStart = lineIndex.lineStart(ln)
                val lnLen = lineIndex.lineLength(ln)
                val colStart = if (ln == startLine) selS - lnStart else 0
                val colEnd = if (ln == endLine) selE - lnStart else lnLen
                val x0 = padX + layout.getHorizontalPosition(colStart, true)
                var x1 = padX + layout.getHorizontalPosition(colEnd, true)
                if (ln != endLine) x1 += newlineMarkerWidth // show the swallowed '\n'
                val y = ln * lineHeightPx
                drawRect(
                    color = selectionColor,
                    topLeft = Offset(x0, y),
                    size = Size((x1 - x0).coerceAtLeast(2f), lineHeightPx),
                )
            }
        }

        // 2) Text — one measured line per row at a fixed line height.
        for (ln in layouts.indices) {
            drawText(layouts[ln], color = foreground, topLeft = Offset(padX, ln * lineHeightPx))
        }

        // 3) Composing underline (IME candidate region).
        if (cS in 0 until cE) {
            val ln = lineIndex.lineOf(cS)
            val layout = layouts[ln]
            val lnStart = lineIndex.lineStart(ln)
            val lnLen = lineIndex.lineLength(ln)
            val x0 = padX + layout.getHorizontalPosition((cS - lnStart).coerceIn(0, lnLen), true)
            val x1 = padX + layout.getHorizontalPosition((cE - lnStart).coerceIn(0, lnLen), true)
            val y = ln * lineHeightPx + lineHeightPx - 3f
            drawLine(composingColor, Offset(x0, y), Offset(x1, y), strokeWidth = 3f)
        }

        // 4) Caret — only when there is no selection.
        if (selS == selE) {
            val ln = lineIndex.lineOf(selS)
            val layout = layouts[ln]
            val lnStart = lineIndex.lineStart(ln)
            val x = padX + layout.getHorizontalPosition((selS - lnStart).coerceIn(0, lineIndex.lineLength(ln)), true)
            val y = ln * lineHeightPx
            drawLine(cursorColor, Offset(x, y + 2f), Offset(x, y + lineHeightPx - 2f), strokeWidth = 2.5f)
        }
    }
}

/**
 * Precomputed line boundaries for the document, so offset<->(line,col) is O(log n) / O(1) instead of
 * rescanning the text. `lineStarts[i]` is the char offset where line `i` begins; a trailing empty
 * line after a final '\n' is represented explicitly (matching how editors show a blank last line).
 */
class LineIndex(val text: String) {
    val lines: List<String> = text.split('\n')
    private val starts: IntArray = IntArray(lines.size).also { arr ->
        var acc = 0
        for (i in lines.indices) {
            arr[i] = acc
            acc += lines[i].length + 1 // + '\n'
        }
    }

    val lineCount: Int get() = lines.size
    fun lineStart(i: Int): Int = starts[i]
    fun lineLength(i: Int): Int = lines[i].length

    fun lineOf(offset: Int): Int {
        // Binary search for the greatest lineStart <= offset.
        var lo = 0
        var hi = starts.size - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) ushr 1
            if (starts[mid] <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }
}
