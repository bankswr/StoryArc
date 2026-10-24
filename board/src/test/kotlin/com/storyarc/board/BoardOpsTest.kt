package com.storyarc.board

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class BoardOpsTest {

    /**
     * Builds a board from rows like `"A: a b . c"`: one row per timeline, one token per slot,
     * `.` for empty and `|x` for a marker column `x` (only valid when every row has it).
     */
    private fun board(vararg rows: String): BoardState {
        var s = BoardState()
        val parsed = rows.map { r -> r.substringBefore(":").trim() to r.substringAfter(":").trim().split(Regex("\\s+")) }
        val placements = HashMap<String, Cell>()
        val markers = HashMap<String, Int>()
        parsed.forEach { (t, cells) ->
            s = s.addTimeline(t)
            cells.forEachIndexed { i, tok ->
                when {
                    tok == "." -> {}
                    tok.startsWith("|") -> markers[tok.drop(1)] = i
                    else -> placements[tok] = Cell(t, i)
                }
            }
        }
        return s.copy(placements = placements, markers = markers).also { assertEquals(emptyList<String>(), it.violations()) }
    }

    /** Renders back to the row format used by [board]. */
    private fun BoardState.render(): List<String> = timelines.map { t ->
        "$t: " + (0 until slotCount).joinToString(" ") { s -> markerAt(s)?.let { "|$it" } ?: nodeAt(t, s) ?: "." }
    }

    private fun BoardState.ok() = also { assertEquals(emptyList<String>(), violations()) }

    @Test
    fun `drop between inserts a slot on every timeline`() {
        val b = board("A: a b", "B: x y")
        val r = b.addNode("n", Drop.Between("A", 1)).ok()
        assertEquals(listOf("A: a n b", "B: x . y"), r.render())
    }

    @Test
    fun `drop onto pushes only that timeline and empty slots absorb it`() {
        val b = board("A: a b . c", "B: w x y z")
        val r = b.addNode("n", Drop.Onto("A", 0)).ok()
        assertEquals(listOf("A: n a b c", "B: w x y z"), r.render())
    }

    @Test
    fun `push past the end appends slots`() {
        val b = board("A: a b", "B: x .")
        val r = b.addNode("n", Drop.Onto("A", 0)).ok()
        assertEquals(listOf("A: n a b", "B: x . ."), r.render())
    }

    @Test
    fun `onto an empty slot places without pushing`() {
        val b = board("A: a . c", "B: x y z")
        val r = b.addNode("n", Drop.Onto("A", 1)).ok()
        assertEquals(listOf("A: a n c", "B: x y z"), r.render())
    }

    @Test
    fun `push stops at a marker by inserting slots before it`() {
        val b = board("A: a b |m c", "B: x y |m z")
        val r = b.addNode("n", Drop.Onto("A", 0)).ok()
        assertEquals(listOf("A: n a b |m c", "B: x y . |m z"), r.render())
    }

    @Test
    fun `onto a marker slot becomes an insert before it`() {
        val b = board("A: a |m b", "B: x |m y")
        val r = b.addNode("n", Drop.Onto("A", 1)).ok()
        assertEquals(listOf("A: a n |m b", "B: x . |m y"), r.render())
    }

    @Test
    fun `moving the only node out of a slot collapses it`() {
        val b = board("A: a b c", "B: . y .")
        val r = b.move(Item.Node("y"), Drop.Onto("B", 0)).ok()
        assertEquals(listOf("A: a b c", "B: y . ."), r.render())
        val r2 = board("A: a . c", "B: . y .").move(Item.Node("y"), Drop.Onto("B", 0)).ok()
        assertEquals(listOf("A: a c", "B: y ."), r2.render())
    }

    @Test
    fun `move across timelines uses pre-move coordinates`() {
        val b = board("A: a b c", "B: x . .")
        val r = b.move(Item.Node("a"), Drop.Onto("B", 2)).ok()
        assertEquals(listOf("A: . b c", "B: x . a"), r.render())
    }

    @Test
    fun `moving a node onto itself is a no-op`() {
        val b = board("A: a b c", "B: x y z")
        assertEquals(b.render(), b.move(Item.Node("b"), Drop.Onto("A", 1)).ok().render())
    }

    @Test
    fun `marker slots never collapse and markers move by insertion`() {
        val b = board("A: a |m b", "B: x |m y")
        val removed = b.removeNode("a").removeNode("x").ok()
        assertEquals(listOf("A: |m b", "B: |m y"), removed.render())
        val moved = b.moveMarker("m", 3).ok()
        assertEquals(listOf("A: a b |m", "B: x y |m"), moved.render())
        assertEquals(listOf("A: a b", "B: x y"), b.removeMarker("m").ok().render())
    }

    @Test
    fun `add marker inserts its own slot`() {
        val b = board("A: a b", "B: x y")
        assertEquals(listOf("A: a |m b", "B: x |m y"), b.addMarker("m", 1).ok().render())
    }

    @Test
    fun `groups move as a unit keeping internal gaps`() {
        val b = board("A: a b . c d", "B: w x y z q").createGroup("g", listOf("b", "c")).ok()
        val r = b.move(Item.GroupItem("g"), Drop.Onto("B", 0)).ok()
        // The gap between b and c collapses because that slot is now empty on every timeline.
        assertEquals(listOf("A: a . . d . . .", "B: b c w x y z q"), r.render())
        assertEquals("B", r.groups.getValue("g").timelineId)
        assertEquals(listOf("b", "c"), r.nodesOf("g"))
    }

    @Test
    fun `node dropped inside a group joins it, at the edge it stays outside`() {
        val b = board("A: a b c d", "B: w x y z").createGroup("g", listOf("b", "c")).ok()
        val inside = b.move(Item.Node("w"), Drop.Between("A", 2)).ok()
        assertEquals("g", inside.membership["w"])
        val edge = b.move(Item.Node("w"), Drop.Between("A", 1)).ok()
        assertNull(edge.membership["w"])
    }

    @Test
    fun `nested groups and ungroup`() {
        val b = board("A: a b c d", "B: w x y z")
            .createGroup("outer", listOf("a", "b", "c"))
            .createGroup("inner", listOf("b", "c")).ok()
        assertEquals("outer", b.groups.getValue("inner").parentId)
        assertEquals(listOf("inner", "outer"), b.groupChain("c"))
        val wrapped = b.createGroup("mid", listOf("b", "c")).ok()
        // Wrapping a group's exact members nests the new group inside it.
        assertEquals("inner", wrapped.groups.getValue("mid").parentId)
        assertEquals(listOf("mid", "inner", "outer"), wrapped.groupChain("b"))
        val u = b.ungroup("outer").ok()
        assertNull(u.groups.getValue("inner").parentId)
        assertNull(u.membership["a"])
    }

    @Test
    fun `grouping rules`() {
        val b = board("A: a b c d", "B: w x y z").createGroup("g", listOf("b", "c"))
        assertEquals("nodes are not adjacent", b.groupingProblem(listOf("a", "c")))
        assertEquals("nodes are on different timelines", b.groupingProblem(listOf("a", "w")))
        assertEquals("selection partially overlaps a group", b.groupingProblem(listOf("c", "d")))
        assertNull(b.groupingProblem(listOf("a", "b", "c")))
        assertThrows<IllegalArgumentException> { b.createGroup("h", listOf("c", "d")) }
    }

    @Test
    fun `moving the last node out of a group deletes it`() {
        val b = board("A: a b", "B: x y").createGroup("g", listOf("a"))
        val r = b.move(Item.Node("a"), Drop.Onto("B", 0)).ok()
        assertTrue(r.groups.isEmpty())
    }

    @Test
    fun `remove timeline drops its nodes and collapses`() {
        val b = board("A: a . c", "B: . y .")
        assertEquals(listOf("A: a c"), b.removeTimeline("B").ok().render())
    }

    @Test
    fun `timeline reorder`() {
        val b = board("A: a", "B: b", "C: c")
        assertEquals(listOf("C", "A", "B"), b.moveTimeline("C", 0).timelines)
        assertEquals(listOf("B", "C", "A"), b.moveTimeline("A", 5).timelines)
    }
}
