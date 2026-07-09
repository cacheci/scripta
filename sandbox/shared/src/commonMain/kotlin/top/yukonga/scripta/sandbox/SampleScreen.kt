package top.yukonga.scripta.sandbox

import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import top.yukonga.scripta.editor.CodeEditor
import top.yukonga.scripta.editor.EditorColors
import top.yukonga.scripta.editor.EditorLanguage
import top.yukonga.scripta.editor.rememberCodeEditorController

@Composable
fun SampleScreen(modifier: Modifier = Modifier) {
    val controller = rememberCodeEditorController()
    var text by remember { mutableStateOf(SAMPLE_YAML) }
    val dark = isSystemInDarkTheme()
    val editorColors = if (dark) EditorColors.Default else EditorColors.Light
    val window = if (dark) Color(0xFF1E1E1E) else Color(0xFFFFFFFF)
    CodeEditor(
        controller = controller,
        initialText = text,
        language = EditorLanguage.Yaml,
        colors = editorColors,
        modifier = modifier.fillMaxSize().background(window),
    )
}
