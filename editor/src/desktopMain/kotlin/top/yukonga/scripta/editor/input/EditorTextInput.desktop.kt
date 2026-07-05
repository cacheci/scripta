package top.yukonga.scripta.editor.input

import androidx.compose.ui.Modifier
import top.yukonga.scripta.editor.EditorEngine

// v1 桌面只读：不接入 skiko 输入。
actual fun Modifier.editorTextInput(engine: EditorEngine, enabled: Boolean): Modifier = this
