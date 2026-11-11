package com.storyarc.board

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Nodes that cover several slots: resizing, drops onto and around spans, markers. */
class BoardSpanTest {

    /**
     * Builds a board from rows like `"A: a a . b"`: one token per slot, `.` for empty, `|x` for a
     * marker column, and a token repeated in adjacent slots for a node spanning them.
     */
    private fun board(vararg rows: String): BoardState {
        var s = BoardState()
        val placements = HashMap<String, Cell>()
        val spans = HashMap<String, Int>()
        val markers = HashMap<String, Int>()
        rows.forEach { r ->
            val t = r.substringBefore(":").trim()
            s = s.addTimeline(t)
            r.substringAfter(":").trim().split(Regex("\\s+")).forEachIndexed { i, tok ->
                when {
                    tok == "." -> {}
                    tok.startsWith("|") -> markers[tok.drop(1)] = i
                    tok in placements -> spans[tok] = i - placements.getValue(tok).slot + 1
                    else -> placements[tok] = Cell(t, i)
                }
            }
        }
        return s.copy(placements = placements, markers = markers, spans = spans).ok()
    }

    private fun BoardState.render(): List<String> = timelines.map { t ->
        "$t: " + (0 until slotCount).joinToString(" ") { s -> markerAt(s)?.let { "|$it" } ?: nodeAt(t, s) ?: "." }
    }

    private fun BoardState.ok() = also { assertEquals(emptyList<String>(), violations()) }

    @Test
    fun `growing into empty slots pushes nothing`() {
        val r = board("A: a . . b", "B: x y z w").resize("a", 3).ok()
        assertEquals(listOf("A: a a a b", "B: x y z w"), r.render())
    }

    @Test
    fun `growing pushes following nodes on the same timeline only`() {
        val r = board("A: a b c", "B: x y z").resize("a", 2).ok()
        assertEquals(listOf("A: a a b c", "B: x y z ."), r.render())
    }

    @Test
    fun `a marker stops growth`() {
        val r = board("A: a . |m b", "B: x y |m z").resize("a", 5).ok()
        assertEquals(listOf("A: a a |m b", "B: x y |m z"), r.render())
        assertEquals(2, r.spanOf("a"))
    }

    @Test
    fun `nodes pushed by growth stop in front of a marker`() {
        val r = board("A: a b |m c", "B: x y |m z").resize("a", 2).ok()
        assertEquals(listOf("A: a a b |m c", "B: x y . |m z"), r.render())
    }

    @Test
    fun `shrinking collapses slots that are empty everywhere`() {
        assertEquals(listOf("A: a", "B: x"), board("A: a a a", "B: x . .").resize("a", 1).ok().render())
        assertEquals(listOf("A: a . .", "B: x y z"), board("A: a a a", "B: x y z").resize("a", 1).ok().render())
    }

    @Test
    fun `columns inserted inside a span on another timeline widen it`() {
        val r = board("A: a a", "B: x y").addNode("n", Drop.Between("B", 1)).ok()
        assertEquals(listOf("A: a a a", "B: x n y"), r.render())
    }

    @Test
    fun `a drop onto any slot of a span pushes the spanning node`() {
        val r = board("A: a a b", "B: x y z").addNode("n", Drop.Onto("A", 1)).ok()
        assertEquals(listOf("A: n a a b", "B: x y z ."), r.render())
    }

    @Test
    fun `a between drop inside a span on its timeline snaps to the nearer edge`() {
        val b = board("A: a a a", "B: x y z")
        assertEquals(listOf("A: n a a a", "B: . x y z"), b.addNode("n", Drop.Between("A", 1)).ok().render())
        assertEquals(listOf("A: a a a n", "B: x y z ."), b.addNode("n", Drop.Between("A", 2)).ok().render())
    }

    @Test
    fun `a marker inserted inside a span cuts it short`() {
        val r = board("A: a a a", "B: x y z").addMarker("m", 1).ok()
        assertEquals(listOf("A: a |m . .", "B: x |m y z"), r.render())
    }

    @Test
    fun `a spanning node moves with its span`() {
        val r = board("A: a a b", "B: x y z").move(Item.Node("a"), Drop.Between("B", 0)).ok()
        assertEquals(listOf("A: . . . . b", "B: a a x y z"), r.render())
    }

    @Test
    fun `extract and insert keep spans`() {
        val (rest, block) = board("A: a a b", "B: x y z").extract(Item.Node("a"))
        assertEquals(2, block.width)
        val r = rest.ok().insert(block, Drop.Onto("B", 0)).ok()
        assertEquals(2, r.spanOf("a"))
        assertEquals(listOf("A: . . b . .", "B: a a x y z"), r.render())
    }

    @Test
    fun `concurrent nodes are those on other timelines overlapping the span`() {
        val b = board("A: a a a .", "B: x y z w", "C: . . . p")
        assertEquals(listOf("x", "y", "z"), b.concurrentWith("a"))
        assertEquals(listOf("a"), b.concurrentWith("y"))
        assertEquals(listOf("w"), b.concurrentWith("p"))
    }
}
