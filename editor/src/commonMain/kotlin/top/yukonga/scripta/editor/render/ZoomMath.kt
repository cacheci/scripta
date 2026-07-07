package top.yukonga.scripta.editor.render

/**
 * 双指缩放的纯数学，与 Compose 无关、便于单测。核心思想 = 图片缩放：**手势期显示的就是最终状态**，靠「统一的四方向钳制」
 * 做到完全不跳。手势期由 [previewVerticalTransform] / [previewHorizontalTranslate] 把连续系数换成 draw 阶段的两轴变换
 * （零重排）；松手用 [commitFontSize] + [commitScrollNoWrap] / [provisionalScrollYWrap] 落成真实字号并锚定滚动——**提交用与
 * 预览完全相同的钳制式**，配合自相似布局（padX/gutter/字号成比例，缩放后内容尺寸 == 新字号内容尺寸），松手是 no-op、零跳变。
 *
 * 预览模型（真·焦点缩放）：内容绕双指焦点 (focalX, focalY) 两轴缩放（焦点处内容全程停在指下、其余向四周张开）；gutter/行号
 * 横向钉左（只随字号缩放、不横移）、纵向与正文一致。**四方向就地钳制**：scaledScrollTop/Left ∈ [0, scaledMax]——顶/左不露
 * 内容外留白、底/右不越过「内容尺寸 − 视口 + pad」。内容溢出时焦点跟手、到边钳住；放得下时软钉边（像图片缩放缩小图）。
 */
object ZoomMath {
    /**
     * 把连续缩放系数 [scale]（相对手势起始字号 [fontStart]）限制在字号落 [minSp,maxSp] 的范围内，
     * 使手势中放大/缩小到边界后不再继续（预览与最终字号一致）。[fontStart] 非正时原样返回。
     */
    fun clampScaleToFontRange(scale: Float, fontStart: Float, minSp: Float, maxSp: Float): Float {
        if (fontStart <= 0f) return scale
        return scale.coerceIn(minSp / fontStart, maxSp / fontStart)
    }

    /**
     * 提交缩放的最终字号：连续目标字号 `fontStart*scale` 钳到 [minSp,maxSp]。**不量化到档**——只在松手重排一次、无逐档
     * 性能压力；提交精确目标使内容/gutter 与预览末帧完全连续、消除提交时的字号吸附跳变（配合自相似布局，提交零跳变）。
     */
    fun commitFontSize(fontStart: Float, scale: Float, minSp: Float, maxSp: Float): Float =
        (fontStart * scale).coerceIn(minSp, maxSp)

    /**
     * 不换行提交的滚动锚定（`k = newFont/fontStart`）。返回 `[newScrollX, newScrollY]`（仅钳 ≥0，上界由 re-clamp 效应处理；
     * re-clamp 用的 maxScroll 与预览 scaledMax 同式 ⇒ 松手不跳）。两轴均焦点锚定：令焦点处内容缩放后仍停在原视口位置。
     * `sX' = (sX+focalX)·k − focalX`、`sY' = (sY+focalY)·k − focalY`。布局自相似 ⇒ 内容坐标严格 ×k，此式精确、与预览末帧接上。
     */
    fun commitScrollNoWrap(scrollX: Float, scrollY: Float, focalX: Float, focalY: Float, k: Float): FloatArray {
        val nx = ((scrollX + focalX) * k - focalX).coerceAtLeast(0f)
        val ny = ((scrollY + focalY) * k - focalY).coerceAtLeast(0f)
        return floatArrayOf(nx, ny)
    }

    /**
     * 换行提交那一帧的同步临时 scrollY：把锚字符旧内容 y（[anchorContentYOld]）按 k 缩放再减目标视口 y（[anchorViewportY]，
     * 顶部锚为 0），使提交帧≈预览末帧（锚点仍在目标 y 附近）、避免单帧 lurch；随后由 post-layout 效应按新布局精确校正一次。
     */
    fun provisionalScrollYWrap(anchorContentYOld: Float, k: Float, anchorViewportY: Float): Float =
        (anchorContentYOld * k - anchorViewportY).coerceAtLeast(0f)

    /**
     * 预览的纵向变换。内容按 [scale] 绕焦点（视口 y=[focalY]）缩放，**上下都钳**：scaledScrollTop ∈ `[0, scaledMax]`，
     * `scaledMax = (scale·[contentHeight] − [viewportHeight] + [bottomPad]).coerceAtLeast(0)`——顶部不露文首上方留白、底部不越过
     * 「内容 − 视口 + 底部留白」。**与提交 re-clamp 的 maxScrollY 同一连续式** ⇒ 松手不跳；连续（非「溢出才给空间」的分段）使
     * 临界字号附近平滑、near-fit 文档有 pad 的自由度不死钉。返回 `[vTranslate, preTop, preBottom]`：绘制映射
     * `screenY = scale·py + vTranslate`（`py = lineTopPx − scrollY`）；`[preTop, preBottom]` 为落在视口内的 py 区间（缩小外扩、
     * over-draw）。scale==1 时 vTranslate=0、`[0, H]`。
     */
    fun previewVerticalTransform(
        scrollY: Float,
        focalY: Float,
        scale: Float,
        viewportHeight: Float,
        contentHeight: Float,
        bottomPad: Float,
    ): FloatArray {
        if (scale <= 0f) return floatArrayOf(0f, 0f, viewportHeight)
        val scaledMax = (scale * contentHeight - viewportHeight + bottomPad).coerceAtLeast(0f)
        val scaledScrollTop = (scale * (scrollY + focalY) - focalY).coerceIn(0f, scaledMax)
        val vTranslate = scale * scrollY - scaledScrollTop
        return floatArrayOf(vTranslate, -vTranslate / scale, (viewportHeight - vTranslate) / scale)
    }

    /**
     * 预览的正文横向平移。正文按 [scale] 绕焦点（视口 x=[focalX]）缩放，**左右都钳**：scaledScrollLeft ∈ `[0, scaledMax]`，
     * `scaledMax = (scale·[contentWidth] − [viewportWidth] + [rightPad]).coerceAtLeast(0)`——左端不露正文起点左侧留白、右端不越过
     * 「内容宽 − 视口 + 右侧留白」。**与提交 re-clamp 的 maxScrollX 同一连续式** ⇒ 松手不跳。返回 hTranslate：
     * `screenX = scale·px + hTranslate`（`px = contentX − scrollX`）。换行（无横滚）由上层传 [focalX]=0、[scrollX]=0 → hTranslate=0
     * （正文钉左）；gutter 恒用 hTranslate=0。内容宽溢出时焦点左侧正文左移滑到 gutter 后/移出、到右缘钳住；窄内容软钉左。
     */
    fun previewHorizontalTranslate(
        scrollX: Float,
        focalX: Float,
        scale: Float,
        viewportWidth: Float,
        contentWidth: Float,
        rightPad: Float,
    ): Float {
        if (scale <= 0f) return 0f
        val scaledMax = (scale * contentWidth - viewportWidth + rightPad).coerceAtLeast(0f)
        val scaledScrollLeft = (scale * (scrollX + focalX) - focalX).coerceIn(0f, scaledMax)
        return scale * scrollX - scaledScrollLeft
    }
}
