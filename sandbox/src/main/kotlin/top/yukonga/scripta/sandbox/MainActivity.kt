package top.yukonga.scripta.sandbox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.scripta.editor.CodeEditor
import top.yukonga.scripta.editor.EditorLanguage
import top.yukonga.scripta.editor.rememberCodeEditorController

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val controller = rememberCodeEditorController()
            var text by remember { mutableStateOf(SAMPLE_YAML) }
            var wrap by remember { mutableStateOf(false) }
            var readOnly by remember { mutableStateOf(false) }
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                Row(Modifier.fillMaxWidth().background(Color(0xFF2D2D30)).padding(8.dp)) {
                    BasicText(
                        text = "  加载 3MB YAML  ",
                        style = TextStyle(color = Color(0xFFE0E0E0), fontSize = 13.sp),
                        modifier = Modifier.clickable { text = bigYaml(60_000) },
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
                }
                CodeEditor(
                    controller = controller,
                    initialText = text,
                    language = EditorLanguage.Yaml,
                    softWrap = wrap,
                    readOnly = readOnly,
                    modifier = Modifier.fillMaxWidth().weight(1f),
                )
            }
        }
    }
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
