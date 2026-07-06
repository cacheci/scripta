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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
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
                            val body = context.contentResolver.openInputStream(uri)
                                ?.bufferedReader()?.use { it.readText() }
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
                        .statusBarsPadding() // 工具栏底色铺进状态栏，内容下移让开状态栏
                        .padding(8.dp)
                ) {
                    BasicText(
                        text = "  打开  ",
                        style = TextStyle(color = Color(0xFF56A3F5), fontSize = 13.sp),
                        modifier = Modifier.clickable { openDocument.launch(arrayOf("*/*")) },
                    )
                    BasicText(
                        text = "  加载 3MB YAML  ",
                        style = TextStyle(color = Color(0xFFE0E0E0), fontSize = 13.sp),
                        modifier = Modifier.clickable {
                            text = bigYaml(60_000); language = EditorLanguage.Yaml; openedName = null
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
                    // 让开底部导航栏，再让开键盘；imePadding 消费在导航栏之后，底部取二者较大值（关键盘=导航栏、开键盘=键盘）。
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .navigationBarsPadding()
                        .imePadding(),
                )
            }
        }
    }
}

/** 查询 SAF 文档的显示名，用于语言判定与工具栏展示；查不到返回 null。 */
private fun queryDisplayName(context: Context, uri: Uri): String? =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    }

/** 按扩展名判定语言：.yaml/.yml 走 YAML 高亮，其余按纯文本（避免把 .json/.kt 误高亮成 YAML）。 */
private fun languageForName(name: String?): EditorLanguage {
    val ext = name?.substringAfterLast('.', "")?.lowercase()
    return if (ext == "yaml" || ext == "yml") EditorLanguage.Yaml else EditorLanguage.PlainText
}

private fun bigYaml(entries: Int): String = buildString {
    append("# generated big YAML for perf smoke\n")
    for (i in 0 until entries) {
        append("item_$i:\n  id: $i\n  name: \"名称 $i\"\n  enabled: ${i % 2 == 0}\n")
    }
}

private val SAMPLE_YAML = """
    # scripta — sample YAML
    name: scripta
    version: 0.1.0
    editor:
      virtualized: true
      language: yaml
      中文: 输入测试(拼音 composing)
      long_line: 这是一行非常非常非常长的文本用来测试自动换行 aaaaaaaa bbbbbbbb cccccccc dddddddd eeeeeeee ffffffff gggggggg hhhhhhhh iiiiiiii
""".trimIndent()
