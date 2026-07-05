package top.yukonga.scripta.editor

import androidx.compose.ui.text.PlatformTextStyle

internal actual fun editorPlatformName(): String = "Android"

internal actual fun editorNoFontPaddingStyle(): PlatformTextStyle? =
    PlatformTextStyle(includeFontPadding = false)
