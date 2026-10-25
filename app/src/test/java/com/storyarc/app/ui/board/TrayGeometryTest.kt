package com.storyarc.app.ui.board

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.Density
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** At density 1, dp equal px: bottom items are 128 wide with 8 gaps and 8 padding. */
class TrayGeometryTest {
    private val d = Density(1f)

    @Test
    fun bottomTrayTakesTheBottomEdge() {
        val t = TrayGeometry(side = false, expanded = true, width = 400f, height = 800f, count = 3, scroll = 0f, density = d)
        assertEquals(680f, t.rect.top)
        assertEquals(400f, t.boardSize.width)
        assertEquals(680f, t.boardSize.height)
        assertEquals(8f, t.itemRect(0).left)
        assertEquals(144f, t.itemRect(1).left)
    }

    @Test
    fun sideTrayTakesTheRightEdge() {
        val t = TrayGeometry(side = true, expanded = true, width = 900f, height = 400f, count = 2, scroll = 0f, density = d)
        assertEquals(748f, t.rect.left)
        assertEquals(748f, t.boardSize.width)
        assertEquals(400f, t.boardSize.height)
        assertEquals(t.itemRect(0).left, t.itemRect(1).left)
        assertEquals(68f, t.itemRect(1).top - t.itemRect(0).top)
    }

    @Test
    fun hitTestingAndInsertionPoints() {
        val t = TrayGeometry(side = false, expanded = true, width = 400f, height = 800f, count = 3, scroll = 0f, density = d)
        val y = t.itemRect(0).center.y
        assertEquals(0, t.itemAt(Offset(20f, y)))
        assertEquals(1, t.itemAt(Offset(200f, y)))
        assertNull("in the gap", t.itemAt(Offset(140f, y)))
        assertNull("header", t.itemAt(Offset(20f, t.header.center.y)))
        assertEquals(0, t.insertionAt(Offset(20f, y)))
        assertEquals(1, t.insertionAt(Offset(120f, y)))
        assertEquals(3, t.insertionAt(Offset(399f, y)))
    }

    @Test
    fun scrollShiftsItemsAndIsClamped() {
        val t = TrayGeometry(side = false, expanded = true, width = 400f, height = 800f, count = 10, scroll = 136f, density = d)
        assertEquals(8f - 136f, t.itemRect(0).left)
        assertEquals(0, TrayGeometry(false, true, 400f, 800f, 10, -50f, d).scroll.toInt())
        val far = TrayGeometry(false, true, 400f, 800f, 10, 1e6f, d)
        assertEquals(far.maxScroll, far.scroll)
    }

    @Test
    fun collapsedTrayListsNothingAndAppends() {
        val t = TrayGeometry(side = false, expanded = false, width = 400f, height = 800f, count = 3, scroll = 0f, density = d)
        assertEquals(764f, t.rect.top)
        assertNull(t.itemAt(Offset(20f, 780f)))
        assertEquals(3, t.insertionAt(Offset(20f, 780f)))
        // The trash sits just above a collapsed tray.
        assertTrue(t.trashCenter.y < t.rect.top)
    }

    @Test
    fun trashIsAtTheFarEnd() {
        val t = TrayGeometry(side = false, expanded = true, width = 400f, height = 800f, count = 3, scroll = 0f, density = d)
        assertTrue(t.onTrash(Offset(400f - 36f, t.itemArea.center.y)))
        assertFalse(t.onTrash(Offset(100f, t.itemArea.center.y)))
    }
}
