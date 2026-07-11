package top.yukonga.scripta.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import top.yukonga.scripta.editor.find.FindSession
import top.yukonga.scripta.editor.text.TextPosition
import top.yukonga.scripta.editor.text.TextRange

/**
 * 编辑器文本模型的公开壳，内部由 [EditorEngine] 承载全部编辑/选择/IME 语义。
 *
 * 契约以拉为主：文本用 [getText] 在需要时拉取，无每键回调；观测走 Compose 快照 state
 * （[selection] / [caret] / [documentVersion] / [isModified] / [isComposing]）——组合中读取即自动
 * 订阅重组，事件流用 `snapshotFlow`。
 *
 * **全部成员仅限 UI 线程**：内部文档模型是无同步的 piece-tree，且 [getText] 并非纯读（会回写整篇
 * 缓存）——后台线程即使「只读」也是数据竞争。保存范式：主线程无挂起点地背靠背取
 * `val v = documentVersion; val text = getText()`，切 IO 线程写盘，回主线程 `markSaved(v)`。
 */
@Stable
class CodeEditorController internal constructor() {

    internal val engine: EditorEngine = EditorEngine()

    internal val find: FindSession = FindSession(engine)

    internal val gotoLine: GotoLineSession = GotoLineSession(engine)

    /** 拉取当前文档文本。 */
    fun getText(): String = engine.getText()

    /** 当前选区（恒归一化 start ≤ end；空选区 = 光标）。快照 state。行/列 0 基，列是 UTF-16 char 下标。 */
    val selection: TextRange get() = engine.selection

    /** 光标（选区活动端：Shift/拖拽扩选时移动的那端）。快照 state。行/列 0 基。 */
    val caret: TextPosition get() = engine.caret

    /** 文档变更计数：任何可能改动内容的操作都会推进（含 IME 预编辑的中间更新），仅用于相等比较、
     *  不承诺步长或连续性。快照 state。与 [markSaved] 配对实现脏标记。 */
    val documentVersion: Int get() = engine.buffer.version

    /** 是否有未提交的 IME 预编辑（拼音 composing）。预编辑文本已物理写入文档（[getText] 会含它、
     *  [documentVersion] 随之推进），自动保存应以本值门控（如 `filter { !isComposing }`）。快照 state。 */
    val isComposing: Boolean get() = engine.composing != null

    // 「已保存」基准版本。初值 = 构造时版本：新 controller 不脏；播种/换文档后由 setText 重置。
    private var savedVersion: Int by mutableStateOf(engine.buffer.version)

    /** 自上次 [markSaved]（或换文档）以来文档是否被改过。保守语义：按版本比较——undo 回到保存点、
     *  或被取消的 IME 预编辑，仍计为已修改（宁可多问一次「是否保存」，不静默丢改动）。快照 state。 */
    val isModified: Boolean get() = engine.buffer.version != savedVersion

    /** 把 [version] 记为已保存基准。必须传「取走保存文本那一刻」的 [documentVersion]：保存通常异步
     *  （主线程取文本、IO 线程写盘），写盘期间的键入决不能被顺手标为已保存——传旧版本号时那些键入
     *  会保持 [isModified] 为 true，方向正确。见类 KDoc 的保存范式。 */
    fun markSaved(version: Int) {
        savedVersion = version
    }

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

    /** 替换整篇。供 [CodeEditor] 播种初始文本。播种后文档不脏（新打开的文档没有未保存改动）。 */
    internal fun setText(value: String) {
        if (engine.getText() != value) {
            engine.setText(value)
            savedVersion = engine.buffer.version
        }
    }
}

/** 记住一个 [CodeEditorController]，生命周期与组合一致。 */
@Composable
fun rememberCodeEditorController(): CodeEditorController = remember { CodeEditorController() }
