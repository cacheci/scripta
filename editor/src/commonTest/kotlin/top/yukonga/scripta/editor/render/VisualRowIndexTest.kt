package top.yukonga.scripta.editor.render

import kotlin.test.Test
import kotlin.test.assertEquals

class VisualRowIndexTest {
    @Test fun defaultsToOneRowPerLine() {
        val idx = VisualRowIndex(5)
        assertEquals(5, idx.totalRows())
        assertEquals(0, idx.rowsBefore(0))
        assertEquals(3, idx.rowsBefore(3))
        assertEquals(1, idx.rows(2))
    }

    @Test fun setRowsUpdatesPrefixesAndTotal() {
        val idx = VisualRowIndex(5)
        idx.setRows(1, 3) // line 1 wraps to 3 visual rows
        assertEquals(7, idx.totalRows()) // 1 + 3 + 1 + 1 + 1
        assertEquals(0, idx.rowsBefore(0))
        assertEquals(1, idx.rowsBefore(1))
        assertEquals(4, idx.rowsBefore(2)) // 1 + 3
        assertEquals(5, idx.rowsBefore(3)) // 1 + 3 + 1
    }

    @Test fun lineAtRowFindsContainingLine() {
        val idx = VisualRowIndex(5)
        idx.setRows(1, 3) // visual rows: L0=[0], L1=[1,2,3], L2=[4], L3=[5], L4=[6]
        assertEquals(0, idx.lineAtRow(0))
        assertEquals(1, idx.lineAtRow(1))
        assertEquals(1, idx.lineAtRow(3))
        assertEquals(2, idx.lineAtRow(4))
        assertEquals(3, idx.lineAtRow(5))
        assertEquals(4, idx.lineAtRow(6))
        assertEquals(4, idx.lineAtRow(999)) // clamp
    }

    @Test fun resetReinitializesToOneRow() {
        val idx = VisualRowIndex(3)
        idx.setRows(0, 5)
        idx.reset(4)
        assertEquals(4, idx.totalRows())
        assertEquals(2, idx.rowsBefore(2))
    }

    @Test fun singleLine() {
        val idx = VisualRowIndex(1)
        assertEquals(1, idx.totalRows())
        assertEquals(0, idx.lineAtRow(0))
        idx.setRows(0, 4)
        assertEquals(4, idx.totalRows())
        assertEquals(0, idx.lineAtRow(3))
    }
}
