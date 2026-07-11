package top.yukonga.scripta.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.remember
import top.yukonga.scripta.editor.find.FindSession

/**
 * 编辑器文本模型的公开壳。契约是拉模型：调用方需要时用 [getText]（如保存时）取，而非每键回调。
 * 内部由 [EditorEngine] 承载全部编辑/选择/IME 语义。
 */
@Stable
class CodeEditorController internal constructor() {

    internal val engine: EditorEngine = EditorEngine()

    internal val find: FindSession = FindSession(engine)

    internal val gotoLine: GotoLineSession = GotoLineSession(engine)

    /** 拉取当前文档文本。 */
    fun getText(): String = engine.getText()

    /** 撤销 / 重做（无可回退历史时返回 false）。可用性经 [canUndo]/[canRedo]（快照 state）观测。 */
    fun undo(): Boolean = engine.undo()
    fun redo(): Boolean = engine.redo()
    val canUndo: Boolean get() = engine.canUndo
    val canRedo: Boolean get() = engine.canRedo

    /** 打开查找 / 查找替换条（两种模式互斥：纯查找不带替换行）、关闭。宿主可编程驱动（工具栏按钮等）；
     *  快捷键在编辑器内部已接。停靠条之间互斥：打开一条先收起另一条。 */
    fun openFind() {
        gotoLine.close()
        find.open(withReplace = false)
    }

    fun openReplace() {
        gotoLine.close()
        find.open(withReplace = true)
    }

    fun closeFind() = find.close()
    val isFindVisible: Boolean get() = find.visible
    val isReplaceVisible: Boolean get() = find.replaceVisible

    /** 打开 / 关闭跳转行号条（Ctrl+G / mac Cmd+L 在编辑器内部已接）。 */
    fun openGotoLine() {
        find.close()
        gotoLine.open()
    }

    fun closeGotoLine() = gotoLine.close()
    val isGotoLineVisible: Boolean get() = gotoLine.visible

    /** 替换整篇。供 [CodeEditor] 播种初始文本。 */
    internal fun setText(value: String) {
        if (engine.getText() != value) engine.setText(value)
    }
}

/** 记住一个 [CodeEditorController]，生命周期与组合一致。 */
@Composable
fun rememberCodeEditorController(): CodeEditorController = remember { CodeEditorController() }
