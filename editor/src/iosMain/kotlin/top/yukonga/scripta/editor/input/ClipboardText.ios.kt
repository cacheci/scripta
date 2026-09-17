@file:OptIn(ExperimentalComposeUiApi::class)

package top.yukonga.scripta.editor.input

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry

internal actual fun plainTextClipEntry(text: String): ClipEntry =
    ClipEntry.withPlainText(text)

internal actual fun ClipEntry.plainText(): String? = getPlainText()
