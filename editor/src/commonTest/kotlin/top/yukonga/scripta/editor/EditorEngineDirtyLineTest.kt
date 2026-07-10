package top.yukonga.scripta.editor

import top.yukonga.scripta.editor.text.TextPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EditorEngineDirtyLineTest {

    @Test
    fun freshEngineIsDirtyFromTop() {
        val e = EditorEngine("a\nb")
        assertEquals(0, e.consumeDirtyLine())
        assertTrue(e.consumeDirtyLine() > 1) // 消费后复位为「无脏行」
    }

    @Test
    fun editMarksItsLine() {
        val e = EditorEngine("a\nb\nc")
        e.consumeDirtyLine()
        e.setCursor(TextPosition(2, 1))
        e.insert("x")
        assertEquals(2, e.consumeDirtyLine())
    }

    @Test
    fun multipleEditsKeepSmallestLine() {
        val e = EditorEngine("a\nb\nc")
        e.consumeDirtyLine()
        e.setCursor(TextPosition(2, 1)); e.insert("x")
        e.setCursor(TextPosition(0, 1)); e.insert("y")
        assertEquals(0, e.consumeDirtyLine())
    }

    @Test
    fun undoMarksTouchedLine() {
        val e = EditorEngine("a\nb\nc")
        e.setCursor(TextPosition(1, 1))
        e.insert("z")
        e.consumeDirtyLine()
        e.undo()
        assertEquals(1, e.consumeDirtyLine())
    }

    @Test
    fun replaceAllMarksFirstRange() {
        val e = EditorEngine("x\ny\nx")
        e.consumeDirtyLine()
        e.replaceAllOffsetRanges(listOf(0 to 1, 4 to 5), "z")
        assertEquals(0, e.consumeDirtyLine())
    }

    @Test
    fun setTextMarksTop() {
        val e = EditorEngine("a")
        e.setCursor(TextPosition(0, 1))
        e.consumeDirtyLine()
        e.setText("fresh\ndoc")
        assertEquals(0, e.consumeDirtyLine())
    }
}
