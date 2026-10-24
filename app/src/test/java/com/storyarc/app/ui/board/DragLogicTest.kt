package com.storyarc.app.ui.board

import com.storyarc.board.BoardState
import com.storyarc.board.Cell
import com.storyarc.board.Drop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DragLogicTest {
    // Slots: 0 a/x, 1 b/y, 2 marker m, 3 c/z. Node columns 100 wide, marker 20, rows 50.
    private val board = BoardState(
        timelines = listOf("A", "B"),
        placements = mapOf(
            "a" to Cell("A", 0), "b" to Cell("A", 1), "c" to Cell("A", 3),
            "x" to Cell("B", 0), "y" to Cell("B", 1), "z" to Cell("B", 3),
        ),
        markers = mapOf("m" to 2),
    )
    private val g = BoardGeometry(board, 100f, 20f, 50f)

    private fun target(item: DragItem, x: Float, y: Float, unlocked: Set<String> = emptySet()) =
        resolveTarget(board, g, item, x, y, unlocked)

    @Test
    fun edgesAreBetweenAndCentreIsOnto() {
        assertEquals(DragTarget.Place(Drop.Between("B", 1)), target(DragItem.Node("a"), 105f, 60f).target)
        assertEquals(DragTarget.Place(Drop.Onto("B", 1)), target(DragItem.Node("a"), 150f, 60f).target)
        assertEquals(DragTarget.Place(Drop.Between("B", 2)), target(DragItem.Node("a"), 195f, 60f).target)
    }

    @Test
    fun rowsClampToTimelines() {
        assertEquals(DragTarget.Place(Drop.Onto("B", 0)), target(DragItem.Node("b"), 50f, 900f).target)
        assertEquals(DragTarget.Place(Drop.Onto("A", 0)), target(DragItem.Node("b"), 50f, -10f).target)
    }

    @Test
    fun lockedMarkerClampsToNearSide() {
        val r = target(DragItem.Node("a"), 260f, 10f)
        assertEquals(DragTarget.Place(Drop.Between("A", 2)), r.target)
        assertEquals("m", r.blockedBy)
        val back = target(DragItem.Node("c"), 50f, 10f)
        assertEquals(DragTarget.Place(Drop.Between("A", 3)), back.target)
        assertEquals("m", back.blockedBy)
    }

    @Test
    fun overTheMarkerColumnIsBeforeOrAfterIt() {
        val before = target(DragItem.Node("c"), 205f, 10f, unlocked = setOf("m"))
        assertEquals(DragTarget.Place(Drop.Between("A", 2)), before.target)
        // The right half of the marker, approached from the left, means crossing it.
        val after = target(DragItem.Node("a"), 215f, 10f)
        assertEquals(DragTarget.Place(Drop.Between("A", 2)), after.target)
        assertEquals("m", after.blockedBy)
        assertEquals(DragTarget.Place(Drop.Between("A", 3)), target(DragItem.Node("a"), 215f, 10f, setOf("m")).target)
    }

    @Test
    fun unlockedMarkerLetsItemCross() {
        val r = target(DragItem.Node("a"), 260f, 10f, unlocked = setOf("m"))
        assertEquals(DragTarget.Place(Drop.Onto("A", 3)), r.target)
        assertNull(r.blockedBy)
    }

    @Test
    fun markersResolveToNearestBoundary() {
        assertEquals(DragTarget.MarkerAt(0), target(DragItem.Marker("m"), 30f, 0f).target)
        assertEquals(DragTarget.MarkerAt(1), target(DragItem.Marker("m"), 70f, 0f).target)
    }
}
