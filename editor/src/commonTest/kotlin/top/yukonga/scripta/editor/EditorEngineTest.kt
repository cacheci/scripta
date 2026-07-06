package top.yukonga.scripta.editor

import top.yukonga.scripta.editor.text.TextPosition
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EditorEngineTest {
    @Test
    fun initialCursorAtStart() {
        val e = EditorEngine("abc")
        assertEquals(TextPosition(0, 0), e.selStart)
        assertTrue(e.selection.isEmpty)
    }

    @Test
    fun insertReplacesSelection() {
        val e = EditorEngine("hello world")
        e.setSelection(TextPosition(0, 0), TextPosition(0, 5))
        e.insert("HI")
        assertEquals("HI world", e.getText())
        assertEquals(TextPosition(0, 2), e.selStart)
    }

    @Test
    fun insertNewlineSplitsAndMovesCaret() {
        val e = EditorEngine("abcd")
        e.setCursor(TextPosition(0, 2))
        e.insert("\n")
        assertEquals("ab\ncd", e.getText())
        assertEquals(TextPosition(1, 0), e.selStart)
    }

    @Test
    fun backspaceAtColumnZeroMergesLines() {
        val e = EditorEngine("ab\ncd")
        e.setCursor(TextPosition(1, 0))
        e.backspace()
        assertEquals("abcd", e.getText())
        assertEquals(TextPosition(0, 2), e.selStart)
    }

    @Test
    fun backspaceDeletesOneCodePointEmoji() {
        val e = EditorEngine("a😀") // a + 😀
        e.setCursor(TextPosition(0, 3))
        e.backspace()
        assertEquals("a", e.getText())
        assertEquals(TextPosition(0, 1), e.selStart)
    }

    @Test
    fun backspaceDeletesSelection() {
        val e = EditorEngine("hello")
        e.setSelection(TextPosition(0, 1), TextPosition(0, 4))
        e.backspace()
        assertEquals("ho", e.getText())
    }

    @Test
    fun deleteForwardAtLineEndMergesNextLine() {
        val e = EditorEngine("ab\ncd")
        e.setCursor(TextPosition(0, 2))
        e.deleteForward()
        assertEquals("abcd", e.getText())
    }

    @Test
    fun crossLineSelectionDelete() {
        val e = EditorEngine("line1\nline2\nline3")
        e.setSelection(TextPosition(0, 2), TextPosition(2, 2))
        e.insert("X")
        assertEquals("liXne3", e.getText())
        assertEquals(TextPosition(0, 3), e.selStart)
    }

    @Test
    fun selectAllSpansDocument() {
        val e = EditorEngine("a\nb\nc")
        e.selectAll()
        assertEquals(TextPosition(0, 0), e.selStart)
        assertEquals(TextPosition(2, 1), e.selEnd)
    }

    @Test
    fun commitTextClearsComposingAndReportsOffsets() {
        val e = EditorEngine("")
        e.insert("你好")
        assertEquals("你好", e.getText())
        assertNull(e.composing)
        assertEquals(2 to 2, e.selectionOffsets())
        assertEquals(-1 to -1, e.composingOffsets())
    }

    @Test
    fun setSelectionNormalizesReversedInput() {
        val e = EditorEngine("hello")
        e.setSelection(TextPosition(0, 4), TextPosition(0, 1))
        assertEquals(TextPosition(0, 1), e.selStart)
        assertEquals(TextPosition(0, 4), e.selEnd)
    }

    @Test
    fun batchEditNotifiesOnceAtEnd() {
        val e = EditorEngine("")
        var n = 0
        e.imeListener = object : EditorEngine.ImeListener {
            override fun onSelectionChanged(selStart: Int, selEnd: Int, composingStart: Int, composingEnd: Int) {
                n++
            }
        }
        e.beginBatch()
        e.insert("a"); e.insert("b"); e.insert("c")
        assertEquals(0, n)
        e.endBatch()
        assertEquals(1, n)
        assertEquals("abc", e.getText())
    }

    @Test
    fun setTextResetsCursorToStart() {
        val e = EditorEngine("old")
        e.setText("brand\nnew")
        assertEquals("brand\nnew", e.getText())
        assertEquals(TextPosition(0, 0), e.selStart)
    }

    // --- Task 5: IME composing + surrounding 删除 ---

    @Test
    fun composingShowsRegionAndAdvancesCaret() {
        val e = EditorEngine("")
        e.setComposingText("ni", 1)
        assertEquals("ni", e.getText())
        assertEquals(0 to 2, e.composingOffsets())
        assertEquals(2 to 2, e.selectionOffsets())
    }

    @Test
    fun pinyinComposeThenCommitToHanzi() {
        val e = EditorEngine("")
        e.setComposingText("n", 1)
        e.setComposingText("ni", 1)
        e.setComposingText("nihao", 1)
        assertEquals("nihao", e.getText())
        e.commitText("你好", 1)
        assertEquals("你好", e.getText())
        assertNull(e.composing)
        assertEquals(2 to 2, e.selectionOffsets())
    }

    @Test
    fun composingKeepsSurroundingText() {
        val e = EditorEngine("ab")
        e.setCursor(TextPosition(0, 1))
        e.setComposingText("X", 1)
        assertEquals("aXb", e.getText())
        assertEquals(1 to 2, e.composingOffsets())
    }

    @Test
    fun finishComposingKeepsText() {
        val e = EditorEngine("")
        e.setComposingText("abc", 1)
        e.finishComposing()
        assertEquals("abc", e.getText())
        assertNull(e.composing)
    }

    @Test
    fun deleteSurroundingTextCharUnits() {
        val e = EditorEngine("abcdef")
        e.setCursor(TextPosition(0, 3))
        e.deleteSurroundingText(2, 1) // 删 "bc" 与 "d"
        assertEquals("aef", e.getText())
        assertEquals(TextPosition(0, 1), e.selStart)
    }

    @Test
    fun deleteSurroundingAcrossNewline() {
        val e = EditorEngine("ab\ncd")
        e.setCursor(TextPosition(1, 0)) // offset 3, 前面是 '\n'
        e.deleteSurroundingText(1, 0)   // 删掉换行 -> 合并
        assertEquals("abcd", e.getText())
    }

    @Test
    fun deleteSurroundingInCodePointsRespectsSurrogates() {
        val e = EditorEngine("😀X") // 😀X
        e.setCursor(TextPosition(0, 3))           // X 之后
        e.deleteSurroundingTextInCodePoints(2, 0) // 删 X 与整个 😀（2 个 code point）
        assertEquals("", e.getText())
    }

    // --- Task 6: 光标导航 + getter ---

    @Test
    fun horizontalMoveIsCodePointAware() {
        val e = EditorEngine("😀") // 😀 长度 2
        e.setCursor(TextPosition(0, 2))
        e.moveCaretHorizontally(-1, extend = false)
        assertEquals(TextPosition(0, 0), e.selStart)
    }

    @Test
    fun horizontalMoveCrossesLineBoundary() {
        val e = EditorEngine("ab\ncd")
        e.setCursor(TextPosition(0, 2))
        e.moveCaretHorizontally(1, extend = false) // 越过行尾 -> 下一行行首
        assertEquals(TextPosition(1, 0), e.selStart)
    }

    @Test
    fun verticalMovePreservesColumn() {
        val e = EditorEngine("abcd\nef")
        e.setCursor(TextPosition(0, 3))
        e.moveCaretVertically(1, extend = false)
        assertEquals(TextPosition(1, 2), e.selStart) // 目标行较短 -> 夹到行尾
    }

    @Test
    fun extendSelectionWithArrow() {
        val e = EditorEngine("hello")
        e.setCursor(TextPosition(0, 1))
        e.moveCaretHorizontally(1, extend = true)
        assertEquals(TextPosition(0, 1), e.selStart)
        assertEquals(TextPosition(0, 2), e.selEnd)
    }

    @Test
    fun selectedTextIncludesNewline() {
        val e = EditorEngine("line1\nline2\nline3")
        e.setSelection(TextPosition(0, 2), TextPosition(2, 2))
        assertEquals("ne1\nline2\nli", e.selectedText())
    }

    @Test
    fun noSelectionSelectedTextNull() {
        val e = EditorEngine("abc")
        e.setCursor(TextPosition(0, 1))
        assertNull(e.selectedText())
    }

    @Test
    fun textBeforeAndAfterCursor() {
        val e = EditorEngine("abcdef")
        e.setCursor(TextPosition(0, 3))
        assertEquals("bc", e.textBeforeCursor(2).toString())
        assertEquals("de", e.textAfterCursor(2).toString())
    }

    @Test
    fun wordRangeSelectsWord() {
        val e = EditorEngine("foo bar baz")
        val r = e.wordRangeAt(TextPosition(0, 5)) // 落在 "bar"
        assertEquals(TextPosition(0, 4), r.start)
        assertEquals(TextPosition(0, 7), r.end)
    }

    @Test
    fun wordRangeIncludesUnderscore() {
        val e = EditorEngine("my_variable = 1")
        val r = e.wordRangeAt(TextPosition(0, 4)) // 落在标识符内
        assertEquals(TextPosition(0, 0), r.start)
        assertEquals(TextPosition(0, 11), r.end) // 整个 my_variable
    }

    @Test
    fun wordRangeSelectsWhitespaceRun() {
        val e = EditorEngine("a    b")
        val r = e.wordRangeAt(TextPosition(0, 2)) // 落在空白段
        assertEquals(TextPosition(0, 1), r.start)
        assertEquals(TextPosition(0, 5), r.end)
    }

    @Test
    fun wordRangeSelectsPunctuationRun() {
        val e = EditorEngine("a==b")
        val r = e.wordRangeAt(TextPosition(0, 1)) // 落在 "=="
        assertEquals(TextPosition(0, 1), r.start)
        assertEquals(TextPosition(0, 3), r.end)
    }

    // --- 长按选词后按词粒度扩展（selectWordRange） ---

    @Test
    fun selectWordRangeUnionsAnchorAndTargetWord() {
        val e = EditorEngine("foo bar baz")
        val anchor = e.wordRangeAt(TextPosition(0, 1)) // "foo"
        e.selectWordRange(anchor, TextPosition(0, 9))  // 目标落在 "baz"
        assertEquals(TextPosition(0, 0), e.selStart)
        assertEquals(TextPosition(0, 11), e.selEnd)    // foo..baz
    }

    @Test
    fun selectWordRangeBackwardStillUnions() {
        val e = EditorEngine("foo bar baz")
        val anchor = e.wordRangeAt(TextPosition(0, 9)) // "baz"
        e.selectWordRange(anchor, TextPosition(0, 1))  // 反向拖到 "foo"
        assertEquals(TextPosition(0, 0), e.selStart)
        assertEquals(TextPosition(0, 11), e.selEnd)
    }

    @Test
    fun selectWordRangeSameWordKeepsWholeWord() {
        // 手指仍落在锚定词内（如长按后边缘自动滚动首帧）时，必须保留整词、不塌成光标——这正是回归 bug 的核心。
        val e = EditorEngine("foo bar baz")
        val anchor = e.wordRangeAt(TextPosition(0, 5)) // "bar"
        e.selectWordRange(anchor, TextPosition(0, 6))  // 仍在 "bar" 内
        assertEquals(TextPosition(0, 4), e.selStart)
        assertEquals(TextPosition(0, 7), e.selEnd)
        assertTrue(!e.selection.isEmpty)
    }

    // --- goal/desired column：连续上下移动记忆目标列 ---

    @Test
    fun verticalMovementRestoresGoalColumnAcrossShortLine() {
        val e = EditorEngine("abcdefgh\nxy\nabcdefgh")
        e.setCursor(TextPosition(0, 6))
        e.moveCaretVertically(1, extend = false) // 短行 "xy" -> 夹到列 2
        assertEquals(TextPosition(1, 2), e.selEnd)
        e.moveCaretVertically(1, extend = false) // 回到长行 -> 恢复目标列 6
        assertEquals(TextPosition(2, 6), e.selEnd)
    }

    @Test
    fun horizontalMoveResetsGoalColumn() {
        val e = EditorEngine("abcdefgh\nxy\nabcdefgh")
        e.setCursor(TextPosition(0, 6))
        e.moveCaretVertically(1, extend = false)    // -> (1,2)
        e.moveCaretHorizontally(-1, extend = false) // -> (1,1)，重置目标列
        e.moveCaretVertically(1, extend = false)    // 以当前列 1 下移，而非旧目标 6
        assertEquals(TextPosition(2, 1), e.selEnd)
    }
}
