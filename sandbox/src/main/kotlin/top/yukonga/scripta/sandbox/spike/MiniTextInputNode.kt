package top.yukonga.scripta.sandbox.spike

import android.text.InputType
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputModifierNode
import androidx.compose.ui.platform.establishTextInputSession
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * SPIKE modifier that wires our [MiniEditorState] to the Android IME with NO BasicTextField.
 *
 * This is the crux the plan flags as highest-risk. The recipe (verified against ui 1.11.2 sources):
 *   Modifier.Node + FocusEventModifierNode + PlatformTextInputModifierNode
 *     -> on focus gained, launch into the node's coroutineScope
 *        -> establishTextInputSession { startInputMethod(request) }
 *           -> request.createInputConnection(EditorInfo) returns our MiniInputConnection.
 *
 * Focus-driven session (not a bare InputConnection) is mandatory: the session shows the keyboard and
 * is torn down automatically when the node detaches or loses focus.
 */
fun Modifier.miniTextInput(state: MiniEditorState): Modifier = this then MiniTextInputElement(state)

private data class MiniTextInputElement(val state: MiniEditorState) :
    ModifierNodeElement<MiniTextInputNode>() {

    override fun create(): MiniTextInputNode = MiniTextInputNode(state)

    override fun update(node: MiniTextInputNode) {
        node.state = state
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "miniTextInput"
    }
}

private class MiniTextInputNode(var state: MiniEditorState) :
    Modifier.Node(), FocusEventModifierNode, PlatformTextInputModifierNode {

    private var focusedJob: Job? = null
    private var focused = false

    override fun onFocusEvent(focusState: FocusState) {
        if (focusState.isFocused == focused) return
        focused = focusState.isFocused
        focusedJob?.cancel()
        focusedJob = if (focused) {
            // Launch into the node's own scope so the session is cancelled when the node detaches.
            coroutineScope.launch { runInputSession() }
        } else {
            null
        }
    }

    private suspend fun runInputSession() {
        establishTextInputSession {
            val v = view
            val imm = v.context.getSystemService(InputMethodManager::class.java)

            // Mirror every cursor/selection/composing change to the platform IME.
            state.imeListener = object : MiniEditorState.ImeListener {
                override fun onSelectionChanged(
                    selStart: Int,
                    selEnd: Int,
                    composingStart: Int,
                    composingEnd: Int,
                ) {
                    imm?.updateSelection(v, selStart, selEnd, composingStart, composingEnd)
                }
            }
            state.requestShowKeyboard = { imm?.showSoftInput(v, 0) }

            val request = PlatformTextInputMethodRequest { outAttrs ->
                configureEditorInfo(outAttrs)
                MiniInputConnection(v, state)
            }

            try {
                // Suspends until the session is cancelled (focus lost / node detached / new session).
                startInputMethod(request)
            } finally {
                state.imeListener = null
                state.requestShowKeyboard = null
            }
        }
    }

    private fun configureEditorInfo(outAttrs: EditorInfo) {
        outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        outAttrs.imeOptions =
            EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_FULLSCREEN or EditorInfo.IME_FLAG_NO_EXTRACT_UI
        // Seed the IME with the current selection so it starts in sync (initial updateSelection).
        outAttrs.initialSelStart = state.selStart
        outAttrs.initialSelEnd = state.selEnd
    }

    override fun onDetach() {
        focusedJob?.cancel()
        focusedJob = null
        focused = false
        state.imeListener = null
        state.requestShowKeyboard = null
    }
}
