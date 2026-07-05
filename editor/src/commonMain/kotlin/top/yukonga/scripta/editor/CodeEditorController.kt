package top.yukonga.scripta.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/**
 * Owns the editor's text model and exposes it to callers on demand.
 *
 * The public contract is pull-based: callers read the current text via [getText] when they need it
 * (e.g. on save), rather than through a per-keystroke callback.
 *
 * SCAFFOLD STATUS: the model is a single [String] here. The real controller will back it with the
 * line-oriented buffer (sora `ContentLine` style) so edits are O(changed lines), not O(document).
 */
@Stable
class CodeEditorController internal constructor() {

    internal var text: String by mutableStateOf("")
        private set

    /** Pull the current document text from the model. */
    fun getText(): String = text

    /** Replace the whole document. Used by [CodeEditor] to seed the initial text. */
    internal fun setText(value: String) {
        if (text != value) text = value
    }
}

/** Remembers a [CodeEditorController] for the lifetime of the composition. */
@Composable
fun rememberCodeEditorController(): CodeEditorController = remember { CodeEditorController() }
