package top.yukonga.scripta.editor

import kotlin.test.Test
import kotlin.test.assertEquals

class CodeEditorControllerTest {
    @Test
    fun getTextRoundTripsSetText() {
        val c = CodeEditorController()
        c.setText("hello\nworld")
        assertEquals("hello\nworld", c.getText())
    }

    @Test
    fun controllerExposesEngine() {
        val c = CodeEditorController()
        c.setText("abc")
        assertEquals("abc", c.engine.getText())
    }
}
