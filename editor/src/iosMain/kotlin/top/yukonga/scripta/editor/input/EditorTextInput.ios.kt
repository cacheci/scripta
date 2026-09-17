@file:OptIn(ExperimentalComposeUiApi::class)

package top.yukonga.scripta.editor.input

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusEventModifierNode
import androidx.compose.ui.focus.FocusState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.requireLayoutCoordinates
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.platform.PlatformTextInputModifierNode
import androidx.compose.ui.platform.establishTextInputSession
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.EditCommand
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.ImeOptions
import androidx.compose.ui.text.input.TextEditingScope
import androidx.compose.ui.text.input.TextEditorState
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import top.yukonga.scripta.editor.EditorEngine
import top.yukonga.scripta.editor.text.TextRange as EditorTextRange

actual fun Modifier.editorTextInput(
    engine: EditorEngine,
    enabled: Boolean,
    caretRectInEditor: () -> Rect?,
): Modifier = if (enabled) {
    this then EditorTextInputElement(engine, caretRectInEditor)
} else {
    this
}

private data class EditorTextInputElement(
    val engine: EditorEngine,
    val caretRectInEditor: () -> Rect?,
) : ModifierNodeElement<EditorTextInputNode>() {
    override fun create(): EditorTextInputNode = EditorTextInputNode(engine, caretRectInEditor)

    override fun update(node: EditorTextInputNode) {
        node.engine = engine
        node.caretRectInEditor = caretRectInEditor
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "editorTextInput"
    }
}

private class EditorTextInputNode(
    var engine: EditorEngine,
    var caretRectInEditor: () -> Rect?,
) : Modifier.Node(), FocusEventModifierNode, PlatformTextInputModifierNode {

    private var focusedJob: Job? = null
    private var focused = false

    override fun onFocusEvent(focusState: FocusState) {
        if (focusState.isFocused == focused) return
        focused = focusState.isFocused
        focusedJob?.cancel()
        focusedJob = if (focused) {
            coroutineScope.launch { runSession() }
        } else {
            null
        }
    }

    private suspend fun runSession(): Nothing = establishTextInputSession {
        startInputMethod(IosEditorImeRequest(engine, ::focusedRectInRoot))
    }

    private fun focusedRectInRoot(): Rect? {
        if (!isAttached) return null
        val coordinates = try {
            requireLayoutCoordinates()
        } catch (_: IllegalStateException) {
            return null
        }
        if (!coordinates.isAttached) return null

        val origin = coordinates.positionInRoot()
        val caret = caretRectInEditor()
        return if (caret != null) {
            caret.translate(origin)
        } else {
            Rect(origin, Size(1f, coordinates.size.height.toFloat()))
        }
    }

    override fun onDetach() {
        focusedJob?.cancel()
        focusedJob = null
        focused = false
    }
}

private class IosEditorImeRequest(
    private val engine: EditorEngine,
    private val cursorRectInRoot: () -> Rect?,
) : PlatformTextInputMethodRequest {

    override val state: TextEditorState = object : TextEditorState {
        override val text: String
            get() = engine.getText()

        override val length: Int
            get() = engine.buffer.totalLength()

        override fun get(index: Int): Char {
            if (index !in 0..<length) {
                throw IndexOutOfBoundsException("index=$index, length=$length")
            }
            return engine.buffer.textInRange(
                EditorTextRange(
                    engine.buffer.positionAt(index),
                    engine.buffer.positionAt(index + 1),
                ),
            )[0]
        }

        override fun subSequence(startIndex: Int, endIndex: Int): CharSequence =
            engine.buffer.textInRange(
                EditorTextRange(
                    engine.buffer.positionAt(startIndex),
                    engine.buffer.positionAt(endIndex),
                ),
            )

        override val selection: TextRange
            get() {
                val (start, end) = engine.selectionOffsets()
                return TextRange(start, end)
            }

        override val composition: TextRange?
            get() {
                val (start, end) = engine.composingOffsets()
                return if (start < 0) null else TextRange(start, end)
            }
    }

    override val value: () -> TextFieldValue = {
        TextFieldValue(
            text = engine.getText(),
            selection = state.selection,
            composition = state.composition,
        )
    }

    override val imeOptions: ImeOptions = ImeOptions.Default

    override val onEditCommand: (List<EditCommand>) -> Unit = { }

    override val onImeAction: ((ImeAction) -> Unit)? = null

    override val textLayoutResult: () -> TextLayoutResult? = { null }

    override val focusedRectInRoot: () -> Rect? = cursorRectInRoot

    override val textFieldRectInRoot: () -> Rect? = cursorRectInRoot

    override val textClippingRectInRoot: () -> Rect? = cursorRectInRoot

    override val unclippedTextOffsetInRoot: () -> Offset? = {
        cursorRectInRoot()?.topLeft
    }

    override val editText: (block: TextEditingScope.() -> Unit) -> Unit = { block ->
        block(
            object : TextEditingScope {
                override fun deleteSurroundingTextInCodePoints(
                    lengthBeforeCursor: Int,
                    lengthAfterCursor: Int,
                ) = engine.deleteSurroundingTextInCodePoints(
                    lengthBeforeCursor,
                    lengthAfterCursor,
                )

                override fun setSelection(start: Int, end: Int) =
                    engine.setSelection(
                        engine.buffer.positionAt(start),
                        engine.buffer.positionAt(end),
                        keepComposing = true,
                    )

                override fun commitText(text: CharSequence, newCursorPosition: Int) =
                    engine.commitText(text.toString(), newCursorPosition)

                override fun setComposingRegion(start: Int, end: Int) =
                    engine.setComposingRegion(start, end)

                override fun setComposingText(text: CharSequence, newCursorPosition: Int) =
                    engine.setComposingText(text.toString(), newCursorPosition)

                override fun finishComposingText() = engine.finishComposing()
            },
        )
    }
}
