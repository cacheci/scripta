package top.yukonga.scripta.editor.input

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type

/** 语义编辑命令：把平台各异的按键组合归一，供 onKeyEvent 派发（Shift 由调用方另读，决定是否扩选）。 */
enum class EditorKeyCommand {
    SelectAll, Copy, Cut, Paste,
    WordLeft, WordRight, LineStart, LineEnd, DocStart, DocEnd, PageUp, PageDown,
}

/** 平台主修饰键映射（Windows/Linux/Android，硬件键盘）：Ctrl 系。 */
internal fun resolveCtrlBased(e: KeyEvent): EditorKeyCommand? {
    val ctrl = e.isCtrlPressed
    return when (e.key) {
        Key.A -> if (ctrl) EditorKeyCommand.SelectAll else null
        Key.C -> if (ctrl) EditorKeyCommand.Copy else null
        Key.X -> if (ctrl) EditorKeyCommand.Cut else null
        Key.V -> if (ctrl) EditorKeyCommand.Paste else null
        Key.DirectionLeft -> if (ctrl) EditorKeyCommand.WordLeft else null
        Key.DirectionRight -> if (ctrl) EditorKeyCommand.WordRight else null
        Key.MoveHome -> if (ctrl) EditorKeyCommand.DocStart else EditorKeyCommand.LineStart
        Key.MoveEnd -> if (ctrl) EditorKeyCommand.DocEnd else EditorKeyCommand.LineEnd
        Key.PageUp -> EditorKeyCommand.PageUp
        Key.PageDown -> EditorKeyCommand.PageDown
        else -> null
    }
}

/** macOS 映射：Cmd(Meta) 剪贴板/行/文档，Opt(Alt) 按词。约定，未经真机验证。 */
internal fun resolveMacBased(e: KeyEvent): EditorKeyCommand? {
    val cmd = e.isMetaPressed
    val opt = e.isAltPressed
    return when (e.key) {
        Key.A -> if (cmd) EditorKeyCommand.SelectAll else null
        Key.C -> if (cmd) EditorKeyCommand.Copy else null
        Key.X -> if (cmd) EditorKeyCommand.Cut else null
        Key.V -> if (cmd) EditorKeyCommand.Paste else null
        Key.DirectionLeft -> when {
            opt -> EditorKeyCommand.WordLeft
            cmd -> EditorKeyCommand.LineStart
            else -> null
        }
        Key.DirectionRight -> when {
            opt -> EditorKeyCommand.WordRight
            cmd -> EditorKeyCommand.LineEnd
            else -> null
        }
        Key.DirectionUp -> if (cmd) EditorKeyCommand.DocStart else null
        Key.DirectionDown -> if (cmd) EditorKeyCommand.DocEnd else null
        Key.MoveHome -> EditorKeyCommand.DocStart
        Key.MoveEnd -> EditorKeyCommand.DocEnd
        Key.PageUp -> EditorKeyCommand.PageUp
        Key.PageDown -> EditorKeyCommand.PageDown
        else -> null
    }
}

/**
 * 把一个 KeyEvent 解析成语义命令（仅 KeyDown；解析失败返回 null，交回 onKeyEvent 的平台无关分支）。
 * Shift 不影响命令本身（只在调用方决定是否扩选），故此处不读 Shift。
 */
expect fun resolveEditorKeyCommand(event: KeyEvent): EditorKeyCommand?
