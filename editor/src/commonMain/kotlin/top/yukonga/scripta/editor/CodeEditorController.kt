package top.yukonga.scripta.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember

/**
 * 编辑器文本模型的公开壳。契约是拉模型：调用方需要时用 [getText]（如保存时）取，而非每键回调。
 * 内部由 [EditorEngine] 承载全部编辑/选择/IME 语义。
 */
@Stable
class CodeEditorController internal constructor() {

    internal val engine: EditorEngine = EditorEngine()

    /** 拉取当前文档文本。 */
    fun getText(): String = engine.getText()

    /** 替换整篇。供 [CodeEditor] 播种初始文本。 */
    internal fun setText(value: String) {
        if (engine.getText() != value) engine.setText(value)
    }
}

/** 记住一个 [CodeEditorController]，生命周期与组合一致。 */
@Composable
fun rememberCodeEditorController(): CodeEditorController = remember { CodeEditorController() }
