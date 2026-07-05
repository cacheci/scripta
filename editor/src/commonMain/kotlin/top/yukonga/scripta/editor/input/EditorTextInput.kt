package top.yukonga.scripta.editor.input

import androidx.compose.ui.Modifier
import top.yukonga.scripta.editor.EditorEngine

/**
 * 平台输入缝。Android：自管 IME（无 BasicTextField）。桌面：v1 只读，返回原 Modifier。
 * [enabled] 为 false（只读）时不建立输入会话。
 */
expect fun Modifier.editorTextInput(engine: EditorEngine, enabled: Boolean): Modifier
