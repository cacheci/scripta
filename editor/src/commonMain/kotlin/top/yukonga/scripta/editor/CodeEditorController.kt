package top.yukonga.scripta.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
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
class CodeEditorController internal constructor(initialText: String = "") {

    internal val engine: EditorEngine = EditorEngine(initialText)

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

    /** 设置选区：anchor=[start]（固定端）、caret=[end]（活动端），keep-in-view 滚动露出的是 caret 端——
     *  想露出范围起点就反序传参（存储的选区恒归一化，不受传参顺序影响）。两端越界自动钳制、落在
     *  代理对中间自动退到码点边界。保证触发露出（含与当前选区相同的重复设置）。 */
    fun select(start: TextPosition, end: TextPosition) {
        engine.setSelection(snapToCodePoint(start), snapToCodePoint(end))
        engine.requestReveal()
    }

    /** 光标移到 [position]（越界钳制、代理对中间退到码点边界）并滚动露出，清空选区。
     *  保证触发露出——重复跳到同一位置（如连点同一条 lint 错误）也会把视口滚回。 */
    fun jumpTo(position: TextPosition) = select(position, position)

    /** 便捷：跳到 [line] 行行首（0 基，越界钳制）并滚动露出。 */
    fun jumpToLine(line: Int) = jumpTo(TextPosition(line, 0))

    /** 在光标处插入 [text]（有选区则替换选区），入撤销历史。已有 IME 预编辑会先原样定格上屏
     *  （不丢失、自成撤销单元）再插入——宿主文本决不顶替用户未提交的拼音。不经 readOnly 门：
     *  那是 UI 交互约束，宿主的编程修改由宿主负责。 */
    fun insertText(text: String) {
        engine.finishComposing()
        engine.insert(text)
    }

    /**
     * 把 [start], [end])（越界与代理对中间自动钳制）替换为 [text]，一个撤销单元，光标落替换文本
     * 末尾——整篇替换时即文档末尾、视口随 keep-in-view 滚到最后一行。要保住阅读位置（如格式化回写），
     * 在同一事件回调内紧接着 [jumpTo] 回目标位置：两次选区写落同一快照周期，keep-in-view 只对最终
     * 位置露出一次、无中间滚动。已有 IME 预编辑先原样定格上屏（不丢失）。不经 readOnly 门。
     */
    fun replaceRange(start: TextPosition, end: TextPosition, text: String) {
        engine.finishComposing()
        engine.replaceRange(TextRange(snapToCodePoint(start), snapToCodePoint(end)), text)
    }

    // 公开入口的列钳制：数值越界 + 代理对中间回退（内部导航恒按码点移动、到不了对儿中间；lint/编译器
    // 输出常按字节或码点计列，宿主换算后会落进 emoji/增补 CJK 的两个 char 之间——放行会破坏引擎不变量）。
    // 刻意不进 buffer.clamp：那是每次 offset 换算都走的热路径，取行文本是 O(行长)，超长行会回退性能。
    private fun snapToCodePoint(p: TextPosition): TextPosition {
        val clamped = engine.buffer.clamp(p)
        val t = engine.buffer.lineText(clamped.line)
        val c = clamped.column
        return if (c in 1 until t.length && t[c].isLowSurrogate() && t[c - 1].isHighSurrogate()) {
            TextPosition(clamped.line, c - 1)
        } else clamped
    }

    /**
     * 换文档：无条件以 [text] 整篇替换——清撤销历史、光标回文首（keep-in-view 随之把视口滚回文档
     * 开头）、脏标记复位。即使 [text] 与当前内容相同也完整执行：重开同名文件就该回到「刚打开」状态，
     * 相等守卫式的静默 no-op 会把历史与脏标记留在旧状态。初始内容经 [rememberCodeEditorController]
     * 的 initialText 播种一次，此后换文档一律走本方法。
     */
    fun setDocument(text: String) {
        engine.setText(text)
        savedVersion = engine.buffer.version
    }

    companion object {
        // Bundle 事务上限 ~1MB：超限文档不入 instance state（save 返回 null，不炸 TransactionTooLarge）；
        // 恢复回退为工厂的 initialText 重新播种，大文件宿主应自行持久化（临时文件等）。
        private const val SAVE_TEXT_LIMIT = 200_000

        /** 进程死亡/配置变更恢复：文本 + 方向性选区 + 脏标记。滚动位置不存——恢复后 keep-in-view
         *  按光标近似露出。脏标记只有布尔事实可携带（版本基准随进程死亡失效），恢复侧造一个不等基准。
         *  用通用 Saver 而非 listSaver：超限文档要 save 出 null（跳过保存），listSaver 不允许。 */
        internal val Saver: Saver<CodeEditorController, Any> = Saver(
            save = { c ->
                val text = c.getText()
                if (text.length > SAVE_TEXT_LIMIT) null
                else listOf(
                    text,
                    c.engine.selectionAnchor.line, c.engine.selectionAnchor.column,
                    c.caret.line, c.caret.column,
                    c.isModified,
                )
            },
            restore = { raw ->
                val l = raw as List<*>
                CodeEditorController(l[0] as String).apply {
                    engine.setSelection(
                        TextPosition(l[1] as Int, l[2] as Int),
                        TextPosition(l[3] as Int, l[4] as Int),
                    )
                    if (l[5] as Boolean) savedVersion = engine.buffer.version - 1
                }
            },
        )
    }
}

/** 记住一个 [CodeEditorController]，以 [initialText] 播种（仅构造时一次；换文档用
 *  [CodeEditorController.setDocument]）。生命周期与组合一致，不跨进程死亡/配置变更——
 *  需要旋转不丢稿用 [rememberSaveableCodeEditorController]。 */
@Composable
fun rememberCodeEditorController(initialText: String = ""): CodeEditorController =
    remember { CodeEditorController(initialText) }

/**
 * 同 [rememberCodeEditorController]，另经 rememberSaveable 在进程死亡/配置变更（旋转）后恢复文本、
 * 选区与脏标记——旋转不丢稿。超过约 20 万字符的文档不入 instance state（Bundle 事务上限 ~1MB，
 * 超限即 TransactionTooLargeException），此时恢复回退为按 [initialText] 重新播种；大文件宿主应
 * 自行持久化（如临时文件）。滚动位置不保存，恢复后按光标位置近似露出。
 */
@Composable
fun rememberSaveableCodeEditorController(initialText: String = ""): CodeEditorController =
    rememberSaveable(saver = CodeEditorController.Saver) { CodeEditorController(initialText) }
