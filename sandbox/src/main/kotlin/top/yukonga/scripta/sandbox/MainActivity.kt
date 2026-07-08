package top.yukonga.scripta.sandbox

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.scripta.editor.CodeEditor
import top.yukonga.scripta.editor.EditorLanguage
import top.yukonga.scripta.editor.LineNumberMode
import top.yukonga.scripta.editor.rememberCodeEditorController
import java.io.IOException

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        setContent {
            val controller = rememberCodeEditorController()
            var text by remember { mutableStateOf(SAMPLE_YAML) }
            var language by remember { mutableStateOf(EditorLanguage.Yaml) }
            var wrap by remember { mutableStateOf(false) }
            var readOnly by remember { mutableStateOf(false) }
            var lineNumberMode by remember { mutableStateOf(LineNumberMode.PinnedToScreen) }
            var openedName by remember { mutableStateOf<String?>(null) }

            val context = LocalContext.current
            val scope = rememberCoroutineScope()
            // SAF 文档选择器：读一次即用，不需要 takePersistableUriPermission（回调期间的临时授权已够）。
            // MIME 传 */*——.yaml/.log/.kt 等常被系统报成非 text/* 类型，用 text/* 过滤反而会把想打开的
            // 文件挡在选择器外；这里一律按 UTF-8 解码，放开筛选让所有文件可见。
            val openDocument = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
                uri ?: return@rememberLauncherForActivityResult
                scope.launch {
                    try {
                        // 文件可达数 MB，读取与文件名查询都放 IO 线程，别卡住 UI；回到主线程再写 state。
                        val (name, content) = withContext(Dispatchers.IO) {
                            val display = queryDisplayName(context, uri)
                            // 打开前查大小、超上限直接拒绝：整篇仍需入内存，超堆容量必 OOM，不如给明确提示。
                            val size = queryFileSize(context, uri)
                            if (size > MAX_OPEN_BYTES) {
                                throw IOException("文件过大（${size / 1024 / 1024}MB），上限 ${MAX_OPEN_BYTES / 1024 / 1024}MB")
                            }
                            // readBytes().decodeToString() 峰值 ≈ byte[] + String（~2N）；优于 readText 的
                            // StringWriter/StringBuffer 反复 copyOf 扩容（~3N，正是打开大文件 OOM 的那一下）。
                            val body = context.contentResolver.openInputStream(uri)
                                ?.use { it.readBytes().decodeToString() }
                                ?: throw IOException("无法读取文件")
                            display to body
                        }
                        openedName = name
                        language = languageForName(name)
                        text = content
                    } catch (e: Exception) {
                        Toast.makeText(context, "打开失败: ${e.message}", Toast.LENGTH_SHORT).show()
                    }
                }
            }

            // 全屏深色底：系统栏透明后，状态栏/导航栏区域露出的是这层底色而非白色 window 背景。
            // 各系统栏的让位下沉到子元素（工具栏让状态栏、编辑器让导航栏+键盘），使两栏底色与相邻内容无缝。
            Column(
                Modifier
                    .fillMaxSize()
                    .background(Color(0xFF1E1E1E))
            ) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0xFF2D2D30))
                        // 工具栏底色铺进状态栏，内容下移让开状态栏
                        .windowInsetsPadding(
                            WindowInsets.statusBars
                                .union(WindowInsets.captionBar)
                                .only(WindowInsetsSides.Top)
                        )
                        .padding(8.dp)
                ) {
                    BasicText(
                        text = "  打开  ",
                        style = TextStyle(color = Color(0xFF56A3F5), fontSize = 13.sp),
                        modifier = Modifier.clickable { openDocument.launch(arrayOf("*/*")) },
                    )
                    BasicText(
                        text = "  示例  ",
                        style = TextStyle(color = Color(0xFFE0E0E0), fontSize = 13.sp),
                        modifier = Modifier.clickable {
                            text = SAMPLE_YAML
                            language = EditorLanguage.Yaml
                            openedName = null
                        },
                    )
                    BasicText(
                        text = if (wrap) "  换行: 开  " else "  换行: 关  ",
                        style = TextStyle(color = Color(0xFF6FCF97), fontSize = 13.sp),
                        modifier = Modifier.clickable { wrap = !wrap },
                    )
                    BasicText(
                        text = if (readOnly) "  只读: 开  " else "  只读: 关  ",
                        style = TextStyle(color = Color(0xFFE0A458), fontSize = 13.sp),
                        modifier = Modifier.clickable { readOnly = !readOnly },
                    )
                    BasicText(
                        text = if (lineNumberMode == LineNumberMode.PinnedToScreen) "  行号: 固定  " else "  行号: 跟随  ",
                        style = TextStyle(color = Color(0xFFB39DDB), fontSize = 13.sp),
                        modifier = Modifier.clickable {
                            lineNumberMode = if (lineNumberMode == LineNumberMode.PinnedToScreen) {
                                LineNumberMode.PinnedToLine
                            } else {
                                LineNumberMode.PinnedToScreen
                            }
                        },
                    )
                    // 已打开的文件名占满剩余宽度、单行省略，绝不把上面的按钮挤出屏幕。
                    openedName?.let {
                        BasicText(
                            text = it,
                            style = TextStyle(color = Color(0xFF858585), fontSize = 13.sp),
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
                    softWrap = wrap,
                    readOnly = readOnly,
                    lineNumberMode = lineNumberMode,
                    // 底部系统栏（导航栏 / 键盘）与其沉浸配色由编辑器内部消费（据是否显示符号条决定谁让位、
                    // 底色铺到边缘），宿主不再加 imePadding/导航栏 padding，只给尺寸。
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                )
            }
        }
    }
}

/** 打开外部文件的字节上限：整篇仍需入内存，超堆容量必 OOM，超此值直接拒绝并提示。piece-table 后
 *  常驻降到 ~1×（original 只读一份、无行链表/索引），故较行链表时代翻倍。 */
private const val MAX_OPEN_BYTES = 64L * 1024 * 1024

/** 查询 SAF 文档的显示名，用于语言判定与工具栏展示；查不到返回 null。 */
private fun queryDisplayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }

/** 查询 SAF 文档字节大小，用于打开前的上限校验；查不到返回 -1（跳过校验）。 */
private fun queryFileSize(context: Context, uri: Uri): Long =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { c ->
        if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else -1L
    } ?: -1L

/** 按扩展名判定语言：.yaml/.yml 走 YAML 高亮，其余按纯文本（避免把 .json/.kt 误高亮成 YAML）。 */
private fun languageForName(name: String?): EditorLanguage {
    val ext = name?.substringAfterLast('.', "")?.lowercase()
    return if (ext == "yaml" || ext == "yml") EditorLanguage.Yaml else EditorLanguage.PlainText
}

/**
 * 默认示例：一篇覆盖常见 YAML 语法场景的配置，兼作编辑器各能力的演示（标量类型 / 嵌套 / 流式 / 锚点合并 /
 * 多行串 / 国际化 / emoji / 长行）。尾部接一条超长单行（[longLineSample]，网格虚拟化）与几行普通内容。
 */
private val SAMPLE_YAML = """
    # scripta — YAML 示例（覆盖常见语法场景）
    # 「打开」载入本地文件，或直接编辑；双指缩放、长按选词、拖动手柄看放大镜、底部符号条一键插入。
    name: scripta
    version: 0.1.0
    released: 2026-07-06
    stable: true
    maintainer: null                 # 空值：null / ~ / 留空 皆可
    homepage: ~

    # 标量类型
    scalars:
      int: 42
      negative: -17
      float: 3.14159
      scientific: 6.022e23
      hex: 0x1F
      octal: 0o17
      bools: [true, false, yes, no, on, off]   # 流式列表
      timestamp: 2026-07-06T21:00:00Z
      quoted: "含 : 冒号与 # 井号的字符串"
      single: '单引号里用 '' 转义单引号'
      plain: 直接量字符串不需要引号
      escaped: "制表\t 换行\n 转义序列"

    # 嵌套映射 + 块状列表 + 列表套映射
    editor:
      virtualized: true
      language: yaml
      features:
        - 自绘渲染
        - 视口虚拟化
        - 自管 IME(拼音 composing)
        - 软换行
        - 超长行网格化
      gestures: {tap: 落光标, long_press: 选词, pinch: 双指缩放}
      themes:
        - {name: dark,  bg: "#1E1E1E", fg: "#E0E0E0"}
        - {name: light, bg: "#FFFFFF", fg: "#1E1E1E"}

    # 锚点 & 引用 & 合并
    defaults: &defaults
      timeout: 30
      retries: 3
    service_a:
      <<: *defaults
      name: alpha
    service_b:
      <<: *defaults
      name: beta
      retries: 5                     # 覆盖默认值

    # 多行字符串
    literal_block: |
      第一行保留换行
      第二行
        这一层缩进也会保留
    folded_block: >
      这几行会被折叠
      成一行(换行变空格)
      最终是一段文本。

    # 常用符号（底部符号条一键插入）
    symbols: '{ } [ ] ( ) < > : = + - * / \ | & # _'

    # 国际化 / emoji / 长行
    i18n:
      中文: 输入测试(拼音 composing)
      日本語: テスト入力
      한국어: 입력 테스트
      emoji: "🇭🇰 🇯🇵 🇺🇸 😀 👨‍👩‍👧"
      long_line: 这是一行非常非常非常长的文本用来测试横向滚动与自动换行 aaaaaaaa bbbbbbbb cccccccc dddddddd eeeeeeee ffffffff gggggggg hhhhhhhh iiiiiiii jjjjjjjj
""".trimIndent() + "\n" + longLineSample() + "\ntail:\n  a: 1\n  b: 2\n  c: 3\n"

/** 超长行（网格虚拟化）测试用的一条 ~3500 字符单行，纯占位、不含真实数据（模拟压缩/生成代码的单行）。
 *  尾部再接几行普通内容（见上），让网格行位于文档中部，便于交互测试（点按/选词/光标随动）。 */
private fun longLineSample(): String {
    val body = (0 until 250).joinToString(" ") { "tok$it=val$it" }
    return "minified_one_liner: \"$body\""
}
