package top.yukonga.scripta.editor.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EditorGeometryTest {
    @Test
    fun gutterDigitsHasMinimumTwo() {
        assertEquals(2, EditorGeometry.gutterDigits(1))
        assertEquals(2, EditorGeometry.gutterDigits(9))
        assertEquals(2, EditorGeometry.gutterDigits(99))
        assertEquals(3, EditorGeometry.gutterDigits(100))
        assertEquals(4, EditorGeometry.gutterDigits(1234))
    }

    @Test
    fun lineAtYMapsAndClamps() {
        assertEquals(0, EditorGeometry.lineAtY(5f, 0f, 20f, 100))
        assertEquals(1, EditorGeometry.lineAtY(25f, 0f, 20f, 100))
        assertEquals(3, EditorGeometry.lineAtY(25f, 40f, 20f, 100)) // y+scroll = 65 -> line 3
        assertEquals(4, EditorGeometry.lineAtY(9999f, 0f, 20f, 5))  // clamp
    }

    @Test
    fun caretHandleSitsBelowCaretCentered() {
        // 光标屏幕矩形：x=100, top=0, bottom=20；radius=8, slop=6
        val g = EditorGeometry.handleGeometry(HandleKind.Caret, 100f, 0f, 20f, 8f, 6f)
        assertEquals(100f, g.centerX)          // 光标手柄水平居中
        assertEquals(28f, g.centerY)           // bottom(20) + radius(8)
        assertEquals(86f, g.hitLeft)           // cx - (radius+slop) = 100-14
        assertEquals(0f, g.hitTop)             // 命中盒上沿到光标顶
        assertEquals(114f, g.hitRight)
        assertEquals(42f, g.hitBottom)         // cy + (radius+slop) = 28+14
    }

    @Test
    fun selectionHandlesLeanOutward() {
        val start = EditorGeometry.handleGeometry(HandleKind.SelectionStart, 100f, 0f, 20f, 8f, 0f)
        val end = EditorGeometry.handleGeometry(HandleKind.SelectionEnd, 100f, 0f, 20f, 8f, 0f)
        assertEquals(92f, start.centerX)       // 起点手柄偏左 radius
        assertEquals(108f, end.centerX)        // 终点手柄偏右 radius
    }

    @Test
    fun hitContainsRespectsBox() {
        val g = EditorGeometry.handleGeometry(HandleKind.Caret, 100f, 0f, 20f, 8f, 6f)
        assertTrue(g.hitContains(100f, 28f))   // 圆心
        assertTrue(g.hitContains(100f, 1f))    // 光标线附近（盒内上部）
        assertFalse(g.hitContains(100f, 60f))  // 远在盒下方
        assertFalse(g.hitContains(130f, 28f))  // 远在盒右侧
    }
}
