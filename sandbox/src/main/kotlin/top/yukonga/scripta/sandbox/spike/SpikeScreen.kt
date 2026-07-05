package top.yukonga.scripta.sandbox.spike

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val SPIKE_SAMPLE =
    "Hello, scripta spike!\n第二行:中文输入测试(拼音 composing)\nline three: drag across these lines\n{ nested: [1, 2, 3] }"

/**
 * SPIKE host screen. Renders the self-managed [MiniEditor] plus a live readout (selection / composing
 * / length) and a clipboard toolbar, so the two risk criteria can be checked by hand on a device:
 *   - IME: type Chinese pinyin -> underlined composing, candidate commit lands, backspace deletes
 *     composing, caret advances, readout shows no dup/drop after each keystroke.
 *   - Selection: drag across >=3 lines (and reverse), double-tap a word, Select All, then Copy and
 *     confirm the clipboard text contains the '\n' separators.
 */
@Composable
fun SpikeScreen() {
    val state = remember { MiniEditorState(SPIKE_SAMPLE) }
    val clipboard = LocalClipboardManager.current

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFF1E1E1E)).padding(12.dp)) {
        BasicText(
            text = "scripta spike — self-managed IME + cross-line selection",
            style = TextStyle(color = Color(0xFFB0B0B0), fontSize = 13.sp),
        )
        StatusReadout(state)

        MiniEditor(
            state = state,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(vertical = 8.dp)
                .background(Color(0xFF141414), RoundedCornerShape(6.dp)),
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            ToolButton("Select All") { state.selectAll() }
            ToolButton("Copy") {
                state.selectedText()?.let { clipboard.setText(AnnotatedString(it.toString())) }
            }
            ToolButton("Cut") {
                state.selectedText()?.let {
                    clipboard.setText(AnnotatedString(it.toString()))
                    state.insert("") // replaces the selection with nothing
                }
            }
            ToolButton("Paste") {
                clipboard.getText()?.text?.let { state.insert(it) }
            }
        }
    }
}

@Composable
private fun StatusReadout(state: MiniEditorState) {
    val composing = if (state.composingStart in 0 until state.composingEnd) {
        "[${state.composingStart},${state.composingEnd}]='${state.text.substring(state.composingStart, state.composingEnd)}'"
    } else {
        "none"
    }
    BasicText(
        text = "sel=[${state.selStart},${state.selEnd}]  composing=$composing  len=${state.text.length}",
        style = TextStyle(color = Color(0xFF6FCF97), fontFamily = FontFamily.Monospace, fontSize = 12.sp),
    )
}

@Composable
private fun ToolButton(label: String, onClick: () -> Unit) {
    BasicText(
        text = label,
        style = TextStyle(color = Color(0xFFE0E0E0), fontSize = 13.sp),
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFF2D2D30))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 10.dp),
    )
}
