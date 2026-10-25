package com.storyarc.board

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import kotlin.random.Random

/**
 * Applies long random operation sequences and checks after every step that the invariants
 * hold and that items the operation does not touch keep their relative order (including
 * simultaneity). Pushes may reorder only the target timeline, so its nodes are exempt for Onto drops.
 */
class BoardFuzzTest {

    private fun Random.drop(b: BoardState): Drop {
        val t = b.timelines.random(this)
        val s = nextInt(0, b.slotCount + 2)
        return if (nextBoolean()) Drop.Between(t, s) else Drop.Onto(t, s)
    }

    private fun sign(a: Int, b: Int) = a.compareTo(b).coerceIn(-1, 1)

    /** Pairwise slot order between [ids] (nodes and markers) that must survive an operation. */
    private fun order(b: BoardState, ids: Collection<String>): Map<Pair<String, String>, Int> {
        fun slot(id: String) = b.placements[id]?.slot ?: b.markers.getValue(id)
        val list = ids.toList()
        return buildMap { for (i in list.indices) for (j in i + 1 until list.size) put(list[i] to list[j], sign(slot(list[i]), slot(list[j]))) }
    }

    @Test
    fun `random operation sequences keep invariants`() {
        repeat(300) { seed ->
            val rnd = Random(seed)
            var b = BoardState().addTimeline("T0").addTimeline("T1")
            var next = 0
            repeat(150) { step ->
                val before = b
                val op = rnd.nextInt(100)
                val label: String
                var stable: Collection<String> = emptyList()
                when {
                    op < 25 || b.placements.isEmpty() -> {
                        val d = rnd.drop(b)
                        label = "add n$next $d"
                        stable = b.placements.filterValues { d is Drop.Between || it.timelineId != d.timelineId }.keys + b.markers.keys
                        b = b.addNode("n${next++}", d)
                    }
                    op < 55 -> {
                        val id = b.placements.keys.random(rnd)
                        val d = rnd.drop(b)
                        label = "move $id $d"
                        val others = b.placements.keys - id
                        stable = others.filter { d is Drop.Between || b.placements.getValue(it).timelineId != d.timelineId } + b.markers.keys
                        b = b.move(Item.Node(id), d)
                    }
                    op < 63 && b.groups.isNotEmpty() -> {
                        val g = b.groups.keys.random(rnd)
                        val d = rnd.drop(b)
                        label = "move group $g $d"
                        val others = b.placements.keys - b.nodesOf(g).toSet()
                        stable = others.filter { d is Drop.Between || b.placements.getValue(it).timelineId != d.timelineId } + b.markers.keys
                        b = b.move(Item.GroupItem(g), d)
                    }
                    op < 73 -> {
                        val t = b.timelines.random(rnd)
                        val line = b.nodesOn(t)
                        if (line.isEmpty()) return@repeat
                        val from = rnd.nextInt(line.size)
                        val run = line.subList(from, (from + rnd.nextInt(1, 4)).coerceAtMost(line.size))
                        if (b.groupingProblem(run) != null) return@repeat
                        label = "group $run"
                        b = b.createGroup("g${next++}", run)
                    }
                    op < 76 && b.groups.isNotEmpty() -> {
                        val g = b.groups.keys.random(rnd)
                        label = "ungroup $g"
                        b = b.ungroup(g)
                    }
                    op < 83 -> {
                        val s = rnd.nextInt(0, b.slotCount + 1)
                        label = "add marker m$next at $s"
                        stable = b.placements.keys + b.markers.keys
                        b = b.addMarker("m${next++}", s)
                    }
                    op < 88 && b.markers.isNotEmpty() -> {
                        val m = b.markers.keys.random(rnd)
                        val s = rnd.nextInt(0, b.slotCount + 1)
                        label = "move marker $m to $s"
                        stable = b.placements.keys + b.markers.keys - m
                        b = b.moveMarker(m, s)
                    }
                    op < 91 -> {
                        val item = if (b.groups.isNotEmpty() && rnd.nextBoolean()) Item.GroupItem(b.groups.keys.random(rnd))
                        else Item.Node(b.placements.keys.random(rnd))
                        val (rest, block) = b.extract(item)
                        rest.violations().let { if (it.isNotEmpty()) fail<Unit>("seed $seed step $step extract $item: $it") }
                        val d = rnd.drop(rest)
                        label = "extract $item, insert at $d"
                        b = rest.insert(block, d)
                        // The block lands whole: in order, on one timeline, with its groups' nesting intact.
                        val byOffset = block.nodes.entries.sortedBy { it.value }.map { it.key }
                        assertEquals(byOffset, byOffset.sortedBy { b.placements.getValue(it).slot }, "seed $seed step $step")
                        assertTrue(byOffset.all { b.placements.getValue(it).timelineId == d.timelineId }, "seed $seed step $step")
                        block.groups.forEach { (g, parent) -> if (parent != null) assertEquals(parent, b.groups.getValue(g).parentId) }
                    }
                    op < 94 -> {
                        val id = b.placements.keys.random(rnd)
                        label = "remove $id"
                        b = b.removeNode(id)
                    }
                    op < 96 && b.markers.isNotEmpty() -> {
                        val m = b.markers.keys.random(rnd)
                        label = "remove marker $m"
                        b = b.removeMarker(m)
                    }
                    op < 98 && b.timelines.size < 5 -> {
                        label = "add timeline"
                        b = b.addTimeline("X${next++}", rnd.nextInt(0, b.timelines.size + 1))
                    }
                    b.timelines.size > 2 -> {
                        val t = b.timelines.random(rnd)
                        label = "remove timeline $t"
                        b = b.removeTimeline(t)
                    }
                    else -> return@repeat
                }
                val v = b.violations()
                if (v.isNotEmpty()) fail<Unit>("seed $seed step $step after $label: $v\nbefore=$before\nafter=$b")
                if (stable.isNotEmpty()) {
                    val was = order(before, stable)
                    val now = order(b, stable)
                    was.forEach { (k, o) ->
                        if (now.getValue(k) != o) {
                            fail<Unit>("seed $seed step $step after $label: order of $k changed $o -> ${now[k]}")
                        }
                    }
                }
            }
        }
    }

    @Test
    fun `pushes never move a node across a marker`() {
        repeat(500) { seed ->
            val rnd = Random(seed)
            var b = BoardState().addTimeline("A").addTimeline("B")
            repeat(12) { i -> b = b.addNode("n$i", Drop.Onto(b.timelines.random(rnd), rnd.nextInt(0, b.slotCount + 1))) }
            b = b.addMarker("m", rnd.nextInt(0, b.slotCount + 1))
            val before = b.placements.mapValues { sign(it.value.slot, b.markers.getValue("m")) }
            val d = Drop.Onto(b.timelines.random(rnd), rnd.nextInt(0, b.slotCount))
            val after = b.addNode("new", d)
            assertEquals(emptyList<String>(), after.violations())
            before.forEach { (id, side) -> assertEquals(side, sign(after.placements.getValue(id).slot, after.markers.getValue("m")), "seed $seed $id") }
        }
    }

    @Test
    fun `large boards stay fast`() {
        val rnd = Random(7)
        var b = BoardState()
        repeat(10) { b = b.addTimeline("T$it") }
        // 2000 nodes spread over ~400 slots, with markers and groups.
        val placements = HashMap<String, Cell>()
        var n = 0
        for (slot in 0 until 400) for (t in 0 until 10) if (rnd.nextInt(2) == 0) placements["n${n++}"] = Cell("T$t", slot)
        b = b.copy(placements = placements)
        b = b.copy(placements = b.placements.filterValues { it.slot % 50 != 25 })
        for (m in 0 until 8) b = b.addMarker("m$m", m * 50 + 25)
        assertEquals(emptyList<String>(), b.violations())
        val start = System.nanoTime()
        repeat(300) {
            val id = b.placements.keys.random(rnd)
            b = b.move(Item.Node(id), Drop.Onto(b.timelines.random(rnd), rnd.nextInt(b.slotCount)))
        }
        val perMoveMs = (System.nanoTime() - start) / 1e6 / 300
        println("large board: ${b.placements.size} nodes, ${b.slotCount} slots, %.2f ms per move".format(perMoveMs))
        assertEquals(emptyList<String>(), b.violations())
        assertTrue(perMoveMs < 50, "move took $perMoveMs ms")
    }
}
