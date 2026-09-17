package top.yukonga.scripta.editor.render

import androidx.compose.ui.graphics.RenderEffect

actual fun magnifierGlassRenderEffect(
    left: Float,
    top: Float,
    width: Float,
    height: Float,
    cornerRadius: Float,
    refractionHeight: Float,
    refractionAmount: Float,
    depthEffect: Float,
    chromaticAberration: Float,
): RenderEffect? = null

actual fun isMagnifierGlassSupported(): Boolean = false
