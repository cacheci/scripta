package top.yukonga.scripta.sandbox.spike

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure-logic tests for the spike's editing model — no Android, no device needed. This locks down the
 * highest-risk layer (IME composing / commit / code-point deletes / cross-line selection) so a device
 * session only has to confirm the platform wiring, not the semantics.
 */
class MiniEditorStateTest {

    @Test
    fun initialCursorAtEnd() {
        val s = MiniEditorState("abc")
        assertEquals(3, s.selStart)
        assertEquals(3, s.selEnd)
        assertFalse(s.hasSelection)
    }

    @Test
    fun insertReplacesSelection() {
        val s = MiniEditorState("hello world")
        s.setSelection(0, 5)
        s.insert("HI")
        assertEquals("HI world", s.text)
        assertEquals(2, s.selStart)
        assertEquals(2, s.selEnd)
    }

    @Test
    fun composingShowsRegionAndAdvancesCaret() {
        val s = MiniEditorState("")
        s.setComposingText("ni", 1)
        assertEquals("ni", s.text)
        assertTrue(s.hasComposing)
        assertEquals(0, s.composingStart)
        assertEquals(2, s.composingEnd)
        assertEquals(2, s.selStart) // caret immediately after (newCursorPosition == 1)
    }

    @Test
    fun pinyinComposeThenCommitToHanzi() {
        // Simulate typing pinyin "nihao" as a composing string, then committing the candidate "你好".
        val s = MiniEditorState("")
        s.setComposingText("n", 1)
        s.setComposingText("ni", 1)
        s.setComposingText("nih", 1)
        s.setComposingText("niha", 1)
        s.setComposingText("nihao", 1)
        assertEquals("nihao", s.text)
        assertTrue(s.hasComposing)

        s.commitText("你好", 1)
        assertEquals("你好", s.text)
        assertFalse(s.hasComposing)
        assertEquals(-1, s.composingStart)
        assertEquals(2, s.selStart) // caret after the two Han characters
    }

    @Test
    fun backspaceWithinComposingShortensText() {
        // Some IMEs shorten the composing string (pinyin backspace) via setComposingText.
        val s = MiniEditorState("")
        s.setComposingText("nihao", 1)
        s.setComposingText("niha", 1)
        assertEquals("niha", s.text)
        assertTrue(s.hasComposing)
        assertEquals(4, s.composingEnd)
    }

    @Test
    fun composingKeepsSurroundingText() {
        val s = MiniEditorState("ab")
        s.setCursor(1)
        s.setComposingText("X", 1)
        assertEquals("aXb", s.text)
        assertEquals(1, s.composingStart)
        assertEquals(2, s.composingEnd)
        assertEquals(2, s.selStart)
    }

    @Test
    fun backspaceDeletesOneCodePointIncludingEmoji() {
        val s = MiniEditorState("a😀") // "a" + 😀 (surrogate pair)
        assertEquals(3, s.text.length)
        s.backspace() // caret at end -> delete the whole emoji code point
        assertEquals("a", s.text)
        assertEquals(1, s.selStart)
    }

    @Test
    fun backspaceDeletesSelectionWhenPresent() {
        val s = MiniEditorState("hello")
        s.setSelection(1, 4)
        s.backspace()
        assertEquals("ho", s.text)
        assertEquals(1, s.selStart)
    }

    @Test
    fun deleteSurroundingTextInCodePointsRespectsSurrogates() {
        val s = MiniEditorState("😀X") // 😀X
        s.setCursor(3) // after X
        s.deleteSurroundingTextInCodePoints(2, 0) // delete X and the emoji as 2 code points
        assertEquals("", s.text)
    }

    @Test
    fun deleteSurroundingTextCharUnits() {
        val s = MiniEditorState("abcdef")
        s.setCursor(3)
        s.deleteSurroundingText(2, 1) // remove "bc" before and "d" after
        assertEquals("aef", s.text)
        assertEquals(1, s.selStart)
    }

    @Test
    fun crossLineSelectionIncludesNewline() {
        val s = MiniEditorState("line1\nline2\nline3")
        // Select from middle of line1 to middle of line3.
        s.setSelection(2, 14)
        val selected = s.selectedText().toString()
        assertTrue("selection should span newlines", selected.contains("\n"))
        assertEquals("ne1\nline2\nli", selected)
    }

    @Test
    fun selectAllSpansWholeDocument() {
        val s = MiniEditorState("a\nb\nc")
        s.selectAll()
        assertEquals(0, s.selStart)
        assertEquals(5, s.selEnd)
        assertEquals("a\nb\nc", s.selectedText())
    }

    @Test
    fun noSelectionReturnsNullSelectedText() {
        val s = MiniEditorState("abc")
        s.setCursor(1)
        assertNull(s.selectedText())
    }

    @Test
    fun batchEditNotifiesOnceAtEnd() {
        val s = MiniEditorState("")
        var notifications = 0
        s.imeListener = object : MiniEditorState.ImeListener {
            override fun onSelectionChanged(selStart: Int, selEnd: Int, composingStart: Int, composingEnd: Int) {
                notifications++
            }
        }
        s.beginBatch()
        s.commitText("a", 1)
        s.commitText("b", 1)
        s.commitText("c", 1)
        assertEquals("no IME notification inside a batch", 0, notifications)
        s.endBatch()
        assertEquals("exactly one notification when the batch closes", 1, notifications)
        assertEquals("abc", s.text)
    }

    @Test
    fun textBeforeAndAfterCursor() {
        val s = MiniEditorState("abcdef")
        s.setCursor(3)
        assertEquals("bc", s.textBeforeCursor(2))
        assertEquals("de", s.textAfterCursor(2))
        assertEquals("abc", s.textBeforeCursor(100)) // clamps to available
    }

    @Test
    fun horizontalCaretMoveIsCodePointAware() {
        val s = MiniEditorState("😀") // single emoji, length 2
        s.setCursor(2)
        s.moveCaretHorizontally(-1, extend = false) // one code point left -> offset 0, not 1
        assertEquals(0, s.selStart)
    }
}
