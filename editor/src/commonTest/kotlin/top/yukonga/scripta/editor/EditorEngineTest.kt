package top.yukonga.scripta.editor

import top.yukonga.scripta.editor.text.TextPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorEngineTest {
    @Test fun initialCursorAtEnd() {
        val e = EditorEngine("abc")
        assertEquals(TextPosition(0, 3), e.selStart)
        assertTrue(e.selection.isEmpty)
    }

    @Test fun insertReplacesSelection() {
        val e = EditorEngine("hello world")
        e.setSelection(TextPosition(0, 0), TextPosition(0, 5))
        e.insert("HI")
        assertEquals("HI world", e.getText())
        assertEquals(TextPosition(0, 2), e.selStart)
    }

    @Test fun insertNewlineSplitsAndMovesCaret() {
        val e = EditorEngine("abcd")
        e.setCursor(TextPosition(0, 2))
        e.insert("\n")
        assertEquals("ab\ncd", e.getText())
        assertEquals(TextPosition(1, 0), e.selStart)
    }

    @Test fun backspaceAtColumnZeroMergesLines() {
        val e = EditorEngine("ab\ncd")
        e.setCursor(TextPosition(1, 0))
        e.backspace()
        assertEquals("abcd", e.getText())
        assertEquals(TextPosition(0, 2), e.selStart)
    }

    @Test fun backspaceDeletesOneCodePointEmoji() {
        val e = EditorEngine("a😀") // a + 😀
        e.setCursor(TextPosition(0, 3))
        e.backspace()
        assertEquals("a", e.getText())
        assertEquals(TextPosition(0, 1), e.selStart)
    }

    @Test fun backspaceDeletesSelection() {
        val e = EditorEngine("hello")
        e.setSelection(TextPosition(0, 1), TextPosition(0, 4))
        e.backspace()
        assertEquals("ho", e.getText())
    }

    @Test fun deleteForwardAtLineEndMergesNextLine() {
        val e = EditorEngine("ab\ncd")
        e.setCursor(TextPosition(0, 2))
        e.deleteForward()
        assertEquals("abcd", e.getText())
    }

    @Test fun crossLineSelectionDelete() {
        val e = EditorEngine("line1\nline2\nline3")
        e.setSelection(TextPosition(0, 2), TextPosition(2, 2))
        e.insert("X")
        assertEquals("liXne3", e.getText())
        assertEquals(TextPosition(0, 3), e.selStart)
    }

    @Test fun selectAllSpansDocument() {
        val e = EditorEngine("a\nb\nc")
        e.selectAll()
        assertEquals(TextPosition(0, 0), e.selStart)
        assertEquals(TextPosition(2, 1), e.selEnd)
    }

    @Test fun commitTextClearsComposingAndReportsOffsets() {
        val e = EditorEngine("")
        e.insert("你好")
        assertEquals("你好", e.getText())
        assertNull(e.composing)
        assertEquals(2 to 2, e.selectionOffsets())
        assertEquals(-1 to -1, e.composingOffsets())
    }

    @Test fun setSelectionNormalizesReversedInput() {
        val e = EditorEngine("hello")
        e.setSelection(TextPosition(0, 4), TextPosition(0, 1))
        assertEquals(TextPosition(0, 1), e.selStart)
        assertEquals(TextPosition(0, 4), e.selEnd)
    }

    @Test fun batchEditNotifiesOnceAtEnd() {
        val e = EditorEngine("")
        var n = 0
        e.imeListener = object : EditorEngine.ImeListener {
            override fun onSelectionChanged(selStart: Int, selEnd: Int, composingStart: Int, composingEnd: Int) { n++ }
        }
        e.beginBatch()
        e.insert("a"); e.insert("b"); e.insert("c")
        assertEquals(0, n)
        e.endBatch()
        assertEquals(1, n)
        assertEquals("abc", e.getText())
    }

    @Test fun setTextResetsCursorToEnd() {
        val e = EditorEngine("old")
        e.setText("brand\nnew")
        assertEquals("brand\nnew", e.getText())
        assertEquals(TextPosition(1, 3), e.selStart)
    }
}
