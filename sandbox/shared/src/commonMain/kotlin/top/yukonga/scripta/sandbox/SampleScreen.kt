package top.yukonga.scripta.sandbox

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.layout.widthIn
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
import top.yukonga.scripta.editor.highlight.SyntaxColors
import top.yukonga.scripta.editor.rememberCodeEditorController

/** 演示可选的三套编辑器配色：两套内置预设 + 一套全槽位自定义。 */
private enum class DemoTheme { Dark, Light, Custom }

/**
 * 自定义主题演示：EditorColors 的每个槽位（含 [SyntaxColors] 语法高亮逐类型颜色）全部显式指定，
 * 展示编辑器颜色接口的完整可配性——暖棕底、橙光标/手柄、绿字符串、粉数字。
 */
private val CustomEditorColors = EditorColors(
    background = Color(0xFF282828),
    foreground = Color(0xFFEBDBB2),
    gutterBackground = Color(0xFF32302F),
    gutterForeground = Color(0xFF928374),
    cursor = Color(0xFFFE8019),
    selection = Color(0x66458588),
    handle = Color(0xFFFE8019),
    symbolBarBackground = Color(0xFF3C3836),
    symbolBarForeground = Color(0xFFEBDBB2),
    symbolBarPressed = Color(0x33FFFFFF),
    findMatch = Color(0x4DFABD2F),
    findMatchActive = Color(0x99FE8019),
    currentLine = Color(0xFF3C3836),
    syntax = SyntaxColors(
        comment = Color(0xFF928374),
        key = Color(0xFF83A598),
        string = Color(0xFFB8BB26),
        number = Color(0xFFD3869B),
        boolean = Color(0xFFFB4934),
        nullLike = Color(0xFFFB4934),
        keyword = Color(0xFFFE8019),
        punctuation = Color(0xFFA89984),
        anchor = Color(0xFF8EC07C),
        tag = Color(0xFF8EC07C),
        directive = Color(0xFFFE8019),
    ),
)

@OptIn(ExperimentalLayoutApi::class)
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
    var themeOverride by remember { mutableStateOf<DemoTheme?>(null) }
    val theme = themeOverride ?: if (systemDark) DemoTheme.Dark else DemoTheme.Light
    val dark = theme != DemoTheme.Light // 自定义主题为暖深色底，顶栏按深色系取色
    val editorColors = when (theme) {
        DemoTheme.Dark -> EditorColors.Default
        DemoTheme.Light -> EditorColors.Light
        DemoTheme.Custom -> CustomEditorColors
    }

    val cWindow = if (dark) Color(0xFF1E1E1E) else Color(0xFFFFFFFF)
    val cBar = if (dark) Color(0xFF2D2D30) else Color(0xFFECECEC)
    val cOpen = if (dark) Color(0xFF56A3F5) else Color(0xFF1A73E8)
    val cSample = if (dark) Color(0xFFE0E0E0) else Color(0xFF3C3C3C)
    val cWrap = if (dark) Color(0xFF6FCF97) else Color(0xFF2E9E5B)
    val cReadOnly = if (dark) Color(0xFFE0A458) else Color(0xFFC77A17)
    val cLineNo = if (dark) Color(0xFFB39DDB) else Color(0xFF7E57C2)
    val cTheme = if (dark) Color(0xFFF48FB1) else Color(0xFFC2185B)
    val cName = if (dark) Color(0xFF858585) else Color(0xFF6B6B6B)
    val cHistory = if (dark) Color(0xFF80CBC4) else Color(0xFF00796B)
    val cHistoryOff = cHistory.copy(alpha = 0.35f)

    val opener = rememberDocumentOpener(
        onOpened = { name, content ->
            errorMessage = null
            openedName = name
            language = languageForName(name)
            text = content
        },
        onError = { errorMessage = it },
    )
    SyncSystemBarsAppearance(dark)

    Column(
        modifier
            .fillMaxSize()
            .background(cWindow)
    ) {
        FlowRow(
            Modifier
                .fillMaxWidth()
                .background(cBar)
                .windowInsetsPadding(
                    WindowInsets.statusBars
                        .union(WindowInsets.captionBar)
                        .only(WindowInsetsSides.Top)
                )
                .padding(8.dp)
            // 演示开关越加越多，窄屏一行放不下：FlowRow 自动折行（窄屏两行、宽屏一行）。
        ) {
            BasicText(
                text = "  打开  ",
                style = TextStyle(color = cOpen, fontSize = 13.sp),
                modifier = Modifier.clickable { opener.open() },
            )
            BasicText(
                text = "  示例  ",
                style = TextStyle(color = cSample, fontSize = 13.sp),
                modifier = Modifier.clickable {
                    text = SAMPLE_YAML
                    language = EditorLanguage.Yaml
                    openedName = null
                    errorMessage = null
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
                text = when (theme) {
                    DemoTheme.Dark -> "  主题: 深  "
                    DemoTheme.Light -> "  主题: 浅  "
                    DemoTheme.Custom -> "  主题: 自定义  "
                },
                style = TextStyle(color = cTheme, fontSize = 13.sp),
                modifier = Modifier.clickable {
                    themeOverride = when (theme) {
                        DemoTheme.Dark -> DemoTheme.Light
                        DemoTheme.Light -> DemoTheme.Custom
                        DemoTheme.Custom -> DemoTheme.Dark
                    }
                },
            )
            BasicText(
                text = "  撤销  ",
                style = TextStyle(color = if (controller.canUndo) cHistory else cHistoryOff, fontSize = 13.sp),
                modifier = Modifier.clickable { controller.undo() },
            )
            BasicText(
                text = "  重做  ",
                style = TextStyle(color = if (controller.canRedo) cHistory else cHistoryOff, fontSize = 13.sp),
                modifier = Modifier.clickable { controller.redo() },
            )
            BasicText(
                text = "  查找  ",
                style = TextStyle(color = cOpen, fontSize = 13.sp),
                modifier = Modifier.clickable {
                    if (controller.isFindVisible) controller.closeFind() else controller.openReplace()
                },
            )
            (errorMessage ?: openedName)?.let {
                BasicText(
                    text = it,
                    style = TextStyle(color = cName, fontSize = 13.sp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    softWrap = false,
                    // FlowRow 的折行布局没有「剩余空间」语义可分（weight 不适用）；给个上限宽即可。
                    modifier = Modifier
                        .widthIn(max = 220.dp)
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
