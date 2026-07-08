package top.yukonga.scripta.editor.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ZoomMathTest {
    @Test
    fun clampScaleKeepsFontInRange() {
        assertEquals(40f / 14f, ZoomMath.clampScaleToFontRange(10f, 14f, 8f, 40f), 1e-4f) // 放大撞上界
        assertEquals(8f / 14f, ZoomMath.clampScaleToFontRange(0.1f, 14f, 8f, 40f), 1e-4f) // 缩小撞下界
        assertEquals(1.5f, ZoomMath.clampScaleToFontRange(1.5f, 14f, 8f, 40f), 1e-4f)      // 区间内原样
        assertEquals(2f, ZoomMath.clampScaleToFontRange(2f, 0f, 8f, 40f), 1e-4f)           // fontStart<=0 原样
    }

    @Test
    fun commitFontSizeIsExactAndClamps() {
        assertEquals(15.4f, ZoomMath.commitFontSize(14f, 1.1f, 8f, 40f), 1e-4f) // 精确、不量化到档
        assertEquals(14f, ZoomMath.commitFontSize(14f, 1f, 8f, 40f), 1e-4f)     // 系数 1 不变
        assertEquals(40f, ZoomMath.commitFontSize(14f, 100f, 8f, 40f), 1e-4f)   // 上钳
        assertEquals(8f, ZoomMath.commitFontSize(14f, 0.01f, 8f, 40f), 1e-4f)   // 下钳
    }

    @Test
    fun commitNoWrapIsIdentityAtK1() {
        val r = ZoomMath.commitScrollNoWrap(30f, 50f, 80f, 100f, 1f)
        assertEquals(30f, r[0], 1e-4f)
        assertEquals(50f, r[1], 1e-4f)
    }

    @Test
    fun commitNoWrapBothAxesFocalAnchored() {
        // 两轴焦点锚定：焦点处内容缩放后仍落在同一视口位置。fx=200, fy=100, k=2, 起始滚动 0。
        val r = ZoomMath.commitScrollNoWrap(0f, 0f, 200f, 100f, 2f)
        assertEquals(200f, r[0], 1e-4f)
        assertEquals(100f, r[1], 1e-4f)
        assertEquals(200f, (0f + 200f) * 2f - r[0], 1e-4f) // 焦点内容新坐标 − 新滚动 == 焦点屏幕坐标
        assertEquals(100f, (0f + 100f) * 2f - r[1], 1e-4f)
    }

    @Test
    fun provisionalWrapReturnsFocalToViewportY() {
        assertEquals(200f - 60f, ZoomMath.provisionalScrollYWrap(200f, 1f, 60f), 1e-4f)
        assertEquals(300f * 2f - 60f, ZoomMath.provisionalScrollYWrap(300f, 2f, 60f), 1e-4f)
    }

    @Test
    fun previewVerticalIsIdentityAtScale1() {
        val t = ZoomMath.previewVerticalTransform(120f, 300f, 1f, 800f, 5000f, 200f)
        assertEquals(0f, t[0], 1e-4f)
        assertEquals(0f, t[1], 1e-4f)
        assertEquals(800f, t[2], 1e-4f)
    }

    @Test
    fun previewVerticalClampsTopNoBlankAboveFirstLine() {
        // 文首缩小：纯焦点会把行 0 下移、上方留白；钳顶后 scaledScrollTop=0 ⇒ vTranslate=0，行 0 恰在屏幕 0。
        val t = ZoomMath.previewVerticalTransform(0f, 600f, 0.5f, 800f, 5000f, 200f)
        assertEquals(0f, t[0], 1e-4f)
    }

    @Test
    fun previewVerticalFocalCenteredWhenContentOverflows() {
        // 内容溢出、焦点在内容上：放大时焦点固定、上方内容自由上移（不锁顶）。
        val t = ZoomMath.previewVerticalTransform(0f, 400f, 1.2f, 800f, 5000f, 200f)
        val vTranslate = t[0]
        assertEquals(400f, 1.2f * 400f + vTranslate, 1e-3f) // 焦点固定
        assertTrue(vTranslate < 0f) // 内容上移了、未锁顶
    }

    @Test
    fun previewVerticalClampsBottomToPadding() {
        // 滚过文末以触发底部钳制（scaledScrollTop 撞 scaledMax）：末行下方恰留 bottomPad。
        val vh = 800f;
        val ch = 850f;
        val pad = 100f
        val t = ZoomMath.previewVerticalTransform(1000f, 400f, 1f, vh, ch, pad)
        val vTranslate = t[0]
        // 末行（内容 y=ch）屏幕 y = 1·(ch − scrollY) + vTranslate 应 = vh − pad。
        assertEquals(vh - pad, 1f * (ch - 1000f) + vTranslate, 1e-2f)
    }

    @Test
    fun previewVerticalBandEndpointsMapToViewportEdges() {
        val t = ZoomMath.previewVerticalTransform(300f, 250f, 1.7f, 900f, 8000f, 200f)
        val vTranslate = t[0];
        val s = 1.7f
        assertEquals(0f, s * t[1] + vTranslate, 1e-2f)
        assertEquals(900f, s * t[2] + vTranslate, 1e-2f)
    }

    @Test
    fun previewHorizontalLeftPinnedForWrap() {
        // 换行（focalX=0, scrollX=0, contentWidth<=viewport）：hTranslate=0，正文横向钉左。
        assertEquals(0f, ZoomMath.previewHorizontalTranslate(0f, 0f, 1.5f, 500f, 0f, 0f), 1e-4f)
    }

    @Test
    fun previewHorizontalFocalZoomMovesLeftTextOff() {
        // 宽内容放大（focalX=400, scrollX=0, s=1.5）：焦点固定，焦点左侧正文（起点 px=0）移到屏幕 x<0（滑出/到 gutter 后）。
        val h = ZoomMath.previewHorizontalTranslate(0f, 400f, 1.5f, 500f, 5000f, 0f)
        assertEquals(400f, 1.5f * 400f + h, 1e-3f) // 焦点固定
        assertTrue(1.5f * 0f + h < 0f) // 正文起点移出左侧、未锁前端
    }

    @Test
    fun previewHorizontalClampsLeftNoBlankBeforeOrigin() {
        // 文首横向缩小：钳左端 ⇒ hTranslate=0，正文起点不被右拉出左侧留白。
        assertEquals(0f, ZoomMath.previewHorizontalTranslate(0f, 100f, 0.5f, 500f, 5000f, 0f), 1e-4f)
    }

    @Test
    fun previewHorizontalClampsRightToPadding() {
        // 横滚过右端以触发右侧钳制：右缘内容恰停在 viewportWidth − rightPad。
        val vw = 500f;
        val cw = 600f;
        val pad = 50f
        val h = ZoomMath.previewHorizontalTranslate(1000f, 400f, 1f, vw, cw, pad)
        assertEquals(vw - pad, 1f * (cw - 1000f) + h, 1e-2f)
    }
}
