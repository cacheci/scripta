package top.yukonga.scripta.sandbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.captionBar
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.scripta.editor.CodeEditor
import top.yukonga.scripta.editor.EditorColors
import top.yukonga.scripta.editor.EditorLanguage
import top.yukonga.scripta.editor.LineNumberMode
import top.yukonga.scripta.editor.rememberCodeEditorController

@Composable
fun SampleScreen(modifier: Modifier = Modifier) {
    val controller = rememberCodeEditorController()
    var text by remember { mutableStateOf(SAMPLE_YAML) }
    var language by remember { mutableStateOf(EditorLanguage.Yaml) }
    var wrap by remember { mutableStateOf(false) }
    var readOnly by remember { mutableStateOf(false) }
    var lineNumberMode by remember { mutableStateOf(LineNumberMode.PinnedToScreen) }
    var openedName by remember { mutableStateOf<String?>(null) }
    var errorMessage by remember { mutableStateOf<String?>(null) }

    val systemDark = isSystemInDarkTheme()
    var darkOverride by remember { mutableStateOf<Boolean?>(null) }
    val dark = darkOverride ?: systemDark
    val editorColors = if (dark) EditorColors.Default else EditorColors.Light

    val cWindow = if (dark) Color(0xFF1E1E1E) else Color(0xFFFFFFFF)
    val cBar = if (dark) Color(0xFF2D2D30) else Color(0xFFECECEC)
    val cOpen = if (dark) Color(0xFF56A3F5) else Color(0xFF1A73E8)
    val cSample = if (dark) Color(0xFFE0E0E0) else Color(0xFF3C3C3C)
    val cWrap = if (dark) Color(0xFF6FCF97) else Color(0xFF2E9E5B)
    val cReadOnly = if (dark) Color(0xFFE0A458) else Color(0xFFC77A17)
    val cLineNo = if (dark) Color(0xFFB39DDB) else Color(0xFF7E57C2)
    val cTheme = if (dark) Color(0xFFF48FB1) else Color(0xFFC2185B)
    val cName = if (dark) Color(0xFF858585) else Color(0xFF6B6B6B)

    // Task 3 replaces this with: val opener = rememberDocumentOpener(onOpened = { n, c -> ... }, onError = { errorMessage = it })
    val openDocument: () -> Unit = { errorMessage = "打开功能将在 Plan 1 Task 3 接入" }

    Column(
        modifier
            .fillMaxSize()
            .background(cWindow)
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(cBar)
                .windowInsetsPadding(
                    WindowInsets.statusBars
                        .union(WindowInsets.captionBar)
                        .only(WindowInsetsSides.Top)
                )
                .padding(8.dp)
        ) {
            BasicText(
                text = "  打开  ",
                style = TextStyle(color = cOpen, fontSize = 13.sp),
                modifier = Modifier.clickable { openDocument() },
            )
            BasicText(
                text = "  示例  ",
                style = TextStyle(color = cSample, fontSize = 13.sp),
                modifier = Modifier.clickable {
                    text = SAMPLE_YAML
                    language = EditorLanguage.Yaml
                    openedName = null
                },
            )
            BasicText(
                text = if (wrap) "  换行: 开  " else "  换行: 关  ",
                style = TextStyle(color = cWrap, fontSize = 13.sp),
                modifier = Modifier.clickable { wrap = !wrap },
            )
            BasicText(
                text = if (readOnly) "  只读: 开  " else "  只读: 关  ",
                style = TextStyle(color = cReadOnly, fontSize = 13.sp),
                modifier = Modifier.clickable { readOnly = !readOnly },
            )
            BasicText(
                text = if (lineNumberMode == LineNumberMode.PinnedToScreen) "  行号: 固定  " else "  行号: 跟随  ",
                style = TextStyle(color = cLineNo, fontSize = 13.sp),
                modifier = Modifier.clickable {
                    lineNumberMode = if (lineNumberMode == LineNumberMode.PinnedToScreen) {
                        LineNumberMode.PinnedToLine
                    } else {
                        LineNumberMode.PinnedToScreen
                    }
                },
            )
            BasicText(
                text = if (dark) "  主题: 深  " else "  主题: 浅  ",
                style = TextStyle(color = cTheme, fontSize = 13.sp),
                modifier = Modifier.clickable { darkOverride = !dark },
            )
            (errorMessage ?: openedName)?.let {
                BasicText(
                    text = it,
                    style = TextStyle(color = cName, fontSize = 13.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false,
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 4.dp),
                )
            }
        }
        CodeEditor(
            controller = controller,
            initialText = text,
            language = language,
            colors = editorColors,
            softWrap = wrap,
            readOnly = readOnly,
            lineNumberMode = lineNumberMode,
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
        )
    }
}
