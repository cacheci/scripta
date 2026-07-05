package top.yukonga.scripta.editor

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/** The languages the editor knows how to highlight. v1 targets YAML; PlainText is the no-highlight fallback. */
enum class EditorLanguage {
    PlainText,
    Yaml,
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
        )
    }
}
