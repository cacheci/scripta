package top.yukonga.scripta.editor

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** The languages the editor knows how to highlight. v1 targets YAML; PlainText is the no-highlight fallback. */
enum class EditorLanguage {
    PlainText,
    Yaml,
}

/**
 * 行号的横向锚定方式。
 *
 * [PinnedToScreen]：行号钉在屏幕左侧，不随横向滚动移动；正文之后重绘不透明 gutter 条盖住滚进来的内容，
 * 保证任意横向滚动下数字都清晰。
 *
 * [PinnedToLine]：行号连同其 gutter 底色属于该行坐标系，随内容一起横向滚动——滚到右侧时一并从左边滑出屏幕。
 *
 * 两种模式共用同一 gutter 宽度与正文原点，切换不引起文本重排，命中/滚动几何一致。
 */
enum class LineNumberMode {
    PinnedToScreen,
    PinnedToLine,
}

/** Colors used by the editor surface. Kept flat and [Immutable] so it stays a stable Compose input. */
@Immutable
data class EditorColors(
    val background: Color,
    val foreground: Color,
    val gutterBackground: Color,
    val gutterForeground: Color,
    val cursor: Color,
    val selection: Color,
    /** Caret / selection drag handle (teardrop) color. */
    val handle: Color,
) {
    companion object {
        /** A neutral dark default so the scaffold renders visibly before a theme is wired up. */
        val Default: EditorColors = EditorColors(
            background = Color(0xFF1E1E1E),
            foreground = Color(0xFFE0E0E0),
            gutterBackground = Color(0xFF252526),
            gutterForeground = Color(0xFF858585),
            cursor = Color(0xFFAEAFAD),
            selection = Color(0x553A6DA0),
            handle = Color(0xFF277AF7),
        )
    }
}
