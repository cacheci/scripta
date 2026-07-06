package top.yukonga.scripta.editor.input

import android.text.InputType
import android.text.TextUtils
import android.view.KeyEvent
import android.view.View
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
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
import top.yukonga.scripta.editor.EditorEngine

/** 自管 IME 的 InputConnection：BaseInputConnection 子类，全部文本操作转发到 EditorEngine。 */
internal class EditorInputConnection(
    view: View,
    private val engine: EditorEngine,
) : BaseInputConnection(view, /* fullEditor = */ false) {

    override fun beginBatchEdit(): Boolean {
        engine.beginBatch(); return true
    }

    override fun endBatchEdit(): Boolean = engine.endBatch()

    override fun setComposingText(text: CharSequence, newCursorPosition: Int): Boolean {
        engine.setComposingText(text.toString(), newCursorPosition); return true
    }

    override fun setComposingRegion(start: Int, end: Int): Boolean {
        engine.setComposingRegion(start, end); return true
    }

    override fun finishComposingText(): Boolean {
        engine.finishComposing(); return true
    }

    override fun commitText(text: CharSequence, newCursorPosition: Int): Boolean {
        engine.commitText(text.toString(), newCursorPosition); return true
    }

    override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
        engine.deleteSurroundingText(beforeLength, afterLength); return true
    }

    override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean {
        engine.deleteSurroundingTextInCodePoints(beforeLength, afterLength); return true
    }

    override fun getTextBeforeCursor(n: Int, flags: Int): CharSequence = engine.textBeforeCursor(n)
    override fun getTextAfterCursor(n: Int, flags: Int): CharSequence = engine.textAfterCursor(n)
    override fun getSelectedText(flags: Int): CharSequence? = engine.selectedText()

    override fun getCursorCapsMode(reqModes: Int): Int {
        // 有界窗口即可（避免大文件 O(n)）。
        val before = engine.textBeforeCursor(256).toString()
        return TextUtils.getCapsMode(before, before.length, reqModes)
    }

    override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText {
        val (s, e) = engine.selectionOffsets()
        return ExtractedText().apply {
            text = engine.getText() // 已设 IME_FLAG_NO_EXTRACT_UI，极少被调；大文件下为已知限制。
            startOffset = 0
            selectionStart = s
            selectionEnd = e
        }
    }

    override fun sendKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_DEL -> {
                    engine.backspace(); return true
                }

                KeyEvent.KEYCODE_FORWARD_DEL -> {
                    engine.deleteForward(); return true
                }

                KeyEvent.KEYCODE_ENTER -> {
                    engine.insert("\n"); return true
                }

                else -> {
                    val ch = event.unicodeChar
                    if (ch != 0) {
                        engine.insert(String(Character.toChars(ch))); return true
                    }
                }
            }
        }
        return super.sendKeyEvent(event)
    }
}

actual fun Modifier.editorTextInput(engine: EditorEngine, enabled: Boolean): Modifier =
    if (enabled) this then EditorTextInputElement(engine) else this

private data class EditorTextInputElement(val engine: EditorEngine) :
    ModifierNodeElement<EditorTextInputNode>() {
    override fun create(): EditorTextInputNode = EditorTextInputNode(engine)
    override fun update(node: EditorTextInputNode) {
        node.engine = engine
    }

    override fun InspectorInfo.inspectableProperties() {
        name = "editorTextInput"
    }
}

private class EditorTextInputNode(var engine: EditorEngine) :
    Modifier.Node(), FocusEventModifierNode, PlatformTextInputModifierNode {

    private var focusedJob: Job? = null
    private var focused = false

    override fun onFocusEvent(focusState: FocusState) {
        if (focusState.isFocused == focused) return
        focused = focusState.isFocused
        focusedJob?.cancel()
        focusedJob = if (focused) coroutineScope.launch { runSession() } else null
    }

    private suspend fun runSession() {
        establishTextInputSession {
            val v = view
            val imm = v.context.getSystemService(InputMethodManager::class.java)
            engine.imeListener = object : EditorEngine.ImeListener {
                override fun onSelectionChanged(selStart: Int, selEnd: Int, composingStart: Int, composingEnd: Int) {
                    imm?.updateSelection(v, selStart, selEnd, composingStart, composingEnd)
                }
            }
            engine.requestShowKeyboard = { imm?.showSoftInput(v, 0) }
            val request = PlatformTextInputMethodRequest { outAttrs ->
                // NO_SUGGESTIONS：代码/YAML 不要候选条与拼写波浪线，避免输入法把 fooBar、i++ 等联想改坏。
                outAttrs.inputType =
                    InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                // NO_PERSONALIZED_LEARNING（API 26+，旧版忽略）：别把代码喂进输入法个人词库。
                outAttrs.imeOptions =
                    EditorInfo.IME_ACTION_NONE or EditorInfo.IME_FLAG_NO_FULLSCREEN or
                        EditorInfo.IME_FLAG_NO_EXTRACT_UI or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
                val (s, e) = engine.selectionOffsets()
                outAttrs.initialSelStart = s
                outAttrs.initialSelEnd = e
                EditorInputConnection(v, engine)
            }
            try {
                startInputMethod(request)
            } finally {
                engine.imeListener = null
                engine.requestShowKeyboard = null
            }
        }
    }

    override fun onDetach() {
        focusedJob?.cancel()
        focusedJob = null
        focused = false
        engine.imeListener = null
        engine.requestShowKeyboard = null
    }
}
