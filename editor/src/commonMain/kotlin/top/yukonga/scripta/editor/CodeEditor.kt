package top.yukonga.scripta.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Public entry point for the virtualized code editor.
 *
 * SCAFFOLD STATUS: this is a compile-only placeholder that renders the document read-only, so the
 * module graph, common/android/desktop source sets, and sandbox wiring can be verified end to end.
 * It intentionally does NOT yet implement the virtualized buffer / viewport / Canvas / self-managed
 * IME architecture — that arrives with the spike and the full build. Do not mistake it for the real
 * editor.
 *
 * @param controller owns the text model; obtain via [rememberCodeEditorController].
 * @param initialText the text to seed the document with.
 * @param language which highlighter to use (v1: YAML or plain text).
 * @param colors the editor color scheme.
 * @param readOnly when true, editing is disabled.
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
    LaunchedEffect(initialText) { controller.setText(initialText) }

    val text = controller.getText()
    val lineCount = if (text.isEmpty()) 0 else text.count { it == '\n' } + 1

    // Placeholder surface — replaced by gutter + viewport + Canvas + self-managed IME in the full build.
    Box(modifier = modifier.background(colors.background).padding(12.dp)) {
        BasicText(
            text = buildString {
                appendLine("⟨CodeEditor scaffold placeholder — ${editorPlatformName()}⟩")
                appendLine("language=$language  readOnly=$readOnly  lines=$lineCount")
                appendLine()
                append(text.take(2000))
                if (text.length > 2000) append("\n… (${text.length - 2000} more chars)")
            },
            style = TextStyle(
                color = colors.foreground,
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
            ),
        )
    }
}
