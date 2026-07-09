package top.yukonga.scripta.editor.input

import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.utf16CodePoint
import top.yukonga.scripta.editor.EditorEngine

/**
 * 桌面：可打印字符经 KeyEvent 到达时插入。仅当——非只读、无预编辑（预编辑中的按键归 IME）、无 Ctrl/Meta/Alt
 * 修饰（那是快捷键 / AltGr）、且码点可打印——才插入。IME 提交/预编辑的文本走输入会话的 editText、不经此处，
 * 故与之无双插。码点转字符串用 Character.toChars 以正确处理增补平面。
 */
actual fun insertTypedCharacter(engine: EditorEngine, event: KeyEvent, readOnly: Boolean): Boolean {
    if (readOnly) return false
    if (engine.composing != null) return false
    if (event.isCtrlPressed || event.isMetaPressed || event.isAltPressed) return false
    val cp = event.utf16CodePoint
    // 排除无字符按键：修饰键(Shift 等) / 功能键 / IME 中英切换键 —— AWT 对它们报 CHAR_UNDEFINED(0xFFFF)。
    // 控制字符与未分配码点同样不插。否则按 Shift 切换中英、按功能键都会各插一个 U+FFFF（显示为 ？）。
    if (cp == 0xFFFF || Character.isISOControl(cp) || !Character.isDefined(cp)) return false
    engine.insert(String(Character.toChars(cp)))
    return true
}
