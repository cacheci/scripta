package top.yukonga.scripta.editor.render

import kotlin.test.Test
import kotlin.test.assertEquals

class EditorGeometryTest {
    @Test fun gutterDigitsHasMinimumTwo() {
        assertEquals(2, EditorGeometry.gutterDigits(1))
        assertEquals(2, EditorGeometry.gutterDigits(9))
        assertEquals(2, EditorGeometry.gutterDigits(99))
        assertEquals(3, EditorGeometry.gutterDigits(100))
        assertEquals(4, EditorGeometry.gutterDigits(1234))
    }

    @Test fun lineAtYMapsAndClamps() {
        assertEquals(0, EditorGeometry.lineAtY(5f, 0f, 20f, 100))
        assertEquals(1, EditorGeometry.lineAtY(25f, 0f, 20f, 100))
        assertEquals(3, EditorGeometry.lineAtY(25f, 40f, 20f, 100)) // y+scroll = 65 -> line 3
        assertEquals(4, EditorGeometry.lineAtY(9999f, 0f, 20f, 5))  // clamp
    }
}
