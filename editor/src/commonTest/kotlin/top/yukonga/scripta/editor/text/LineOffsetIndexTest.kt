package top.yukonga.scripta.editor.text

import kotlin.test.Test
import kotlin.test.assertEquals

class LineOffsetIndexTest {
    @Test
    fun offsetOfCountsNewlines() {
        val b = TextBuffer("ab\ncd\nef")
        val idx = LineOffsetIndex(b)
        assertEquals(0, idx.offsetOf(TextPosition(0, 0)))
        assertEquals(2, idx.offsetOf(TextPosition(0, 2)))
        assertEquals(3, idx.offsetOf(TextPosition(1, 0))) // 'c' after "ab\n"
        assertEquals(6, idx.offsetOf(TextPosition(2, 0))) // 'e' after "ab\ncd\n"
        assertEquals(8, idx.totalLength())
    }

    @Test
    fun positionOfIsInverse() {
        val b = TextBuffer("ab\ncd\nef")
        val idx = LineOffsetIndex(b)
        for (off in 0..8) {
            assertEquals(off, idx.offsetOf(idx.positionOf(off)))
        }
        assertEquals(TextPosition(1, 1), idx.positionOf(4))
    }

    @Test
    fun offsetOfCorrectAfterEditWithInvalidation() {
        val b = TextBuffer("ab\ncd\nef")
        val idx = LineOffsetIndex(b)
        assertEquals(6, idx.offsetOf(TextPosition(2, 0)))
        // 在第 0 行插入两个字符 -> 后续行首右移 2
        b.replace(TextRange.cursor(TextPosition(0, 0)), "XY")
        idx.invalidateFrom(0)
        assertEquals(8, idx.offsetOf(TextPosition(2, 0)))
        assertEquals(TextPosition(2, 0), idx.positionOf(8))
    }
}
