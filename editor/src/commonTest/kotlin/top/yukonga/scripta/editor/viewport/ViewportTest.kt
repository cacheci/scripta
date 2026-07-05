package top.yukonga.scripta.editor.viewport

import kotlin.test.Test
import kotlin.test.assertEquals

class ViewportTest {
    @Test fun basicWindowNoOverscan() {
        assertEquals(0..4, Viewport.visibleLines(0f, 100f, 20f, 100, overscan = 0))
    }

    @Test fun scrolledWindowNoOverscan() {
        assertEquals(1..6, Viewport.visibleLines(30f, 100f, 20f, 100, overscan = 0))
    }

    @Test fun overscanExpandsAndClampsAtTop() {
        assertEquals(0..8, Viewport.visibleLines(30f, 100f, 20f, 100, overscan = 2))
    }

    @Test fun clampsToLineCount() {
        assertEquals(0..4, Viewport.visibleLines(0f, 1000f, 20f, 5, overscan = 2))
    }

    @Test fun singleLineDoc() {
        assertEquals(0..0, Viewport.visibleLines(0f, 500f, 20f, 1, overscan = 3))
    }
}
