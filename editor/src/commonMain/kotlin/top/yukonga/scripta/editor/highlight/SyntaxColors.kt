package top.yukonga.scripta.editor.highlight

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString

/**
 * 语法高亮配色：[TokenType] → 颜色的完整映射，每个语义类型一个可自定义的槽位。
 * 插件只产出类型、不产出颜色——换主题不用换插件。扁平 [Immutable] data class，
 * 与 [top.yukonga.scripta.editor.EditorColors] 同法保持稳定 Compose 输入。
 */
@Immutable
data class SyntaxColors(
    val comment: Color,
    val key: Color,
    val string: Color,
    val number: Color,
    val boolean: Color,
    val nullLike: Color,
    val keyword: Color,
    val punctuation: Color,
    val anchor: Color,
    val tag: Color,
    val directive: Color,
) {
    fun colorFor(type: TokenType): Color = when (type) {
        TokenType.Comment -> comment
        TokenType.Key -> key
        TokenType.String -> string
        TokenType.Number -> number
        TokenType.Boolean -> boolean
        TokenType.Null -> nullLike
        TokenType.Keyword -> keyword
        TokenType.Punctuation -> punctuation
        TokenType.Anchor -> anchor
        TokenType.Tag -> tag
        TokenType.Directive -> directive
    }

    companion object {
        /** 深色底默认配色。 */
        val Dark = SyntaxColors(
            comment = Color(0xFF6A9955),
            key = Color(0xFF9CDCFE),
            string = Color(0xFFCE9178),
            number = Color(0xFFB5CEA8),
            boolean = Color(0xFF569CD6),
            nullLike = Color(0xFF569CD6),
            keyword = Color(0xFFC586C0),
            punctuation = Color(0xFF9B9B9B),
            anchor = Color(0xFF4EC9B0),
            tag = Color(0xFF4EC9B0),
            directive = Color(0xFFC586C0),
        )

        /** 浅色底默认配色。 */
        val Light = SyntaxColors(
            comment = Color(0xFF008000),
            key = Color(0xFF0451A5),
            string = Color(0xFFA31515),
            number = Color(0xFF098658),
            boolean = Color(0xFF0000FF),
            nullLike = Color(0xFF0000FF),
            keyword = Color(0xFFAF00DB),
            punctuation = Color(0xFF7B7B7B),
            anchor = Color(0xFF267F99),
            tag = Color(0xFF267F99),
            directive = Color(0xFFAF00DB),
        )
    }
}

/** 把着色段落到 [AnnotatedString]（越界段钳到内容内）；无段时不建 builder。 */
fun highlightedText(content: String, spans: List<HighlightSpan>, colors: SyntaxColors): AnnotatedString {
    if (spans.isEmpty()) return AnnotatedString(content)
    return buildAnnotatedString {
        append(content)
        for (s in spans) {
            val st = s.start.coerceIn(0, content.length)
            val en = s.end.coerceIn(st, content.length)
            if (en > st) addStyle(SpanStyle(color = colors.colorFor(s.type)), st, en)
        }
    }
}
