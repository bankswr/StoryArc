package com.storyarc.board

/** A node's position within one view: which timeline row and which slot column. */
data class Cell(val timelineId: String, val slot: Int)

/**
 * A contiguous run of nodes on one timeline. Groups nest through [parentId]; a nested group
 * always lies on the same timeline as its parent.
 */
data class Group(val id: String, val timelineId: String, val parentId: String?)

/**
 * Immutable layout of one view.
 *
 * Slots are implicit: they are the dense indices `0 until slotCount` used by placements and
 * markers. A marker owns its slot exclusively. [membership] maps a node to its innermost group;
 * ancestry follows [Group.parentId]. See [violations] for the full set of invariants.
 */
data class BoardState(
    val timelines: List<String> = emptyList(),
    val placements: Map<String, Cell> = emptyMap(),
    val markers: Map<String, Int> = emptyMap(),
    val groups: Map<String, Group> = emptyMap(),
    val membership: Map<String, String> = emptyMap(),
) {
    val slotCount: Int by lazy {
        maxOf(placements.values.maxOfOrNull { it.slot } ?: -1, markers.values.maxOrNull() ?: -1) + 1
    }

    private val byCell: Map<Cell, String> by lazy { placements.entries.associate { (id, c) -> c to id } }
    private val byMarkerSlot: Map<Int, String> by lazy { markers.entries.associate { (id, s) -> s to id } }

    fun nodeAt(timelineId: String, slot: Int): String? = byCell[Cell(timelineId, slot)]

    fun markerAt(slot: Int): String? = byMarkerSlot[slot]

    fun isMarkerSlot(slot: Int): Boolean = slot in byMarkerSlot

    /** Node ids on [timelineId], ordered by slot. */
    fun nodesOn(timelineId: String): List<String> =
        placements.entries.filter { it.value.timelineId == timelineId }.sortedBy { it.value.slot }.map { it.key }

    /** Group chain of a node, innermost first. */
    fun groupChain(nodeId: String): List<String> = membership[nodeId]?.let(::ancestorsOf) ?: emptyList()

    /** [groupId] followed by its ancestors, innermost first. */
    fun ancestorsOf(groupId: String): List<String> =
        generateSequence(groupId) { groups[it]?.parentId }.toList()

    /** All nodes inside [groupId], including those in nested groups, ordered by slot. */
    fun nodesOf(groupId: String): List<String> =
        placements.keys.filter { groupId in groupChain(it) }.sortedBy { placements.getValue(it).slot }

    fun childGroups(groupId: String?): List<String> = groups.values.filter { it.parentId == groupId }.map { it.id }

    /** Human-readable invariant violations; empty when the state is well formed. */
    fun violations(): List<String> = buildList {
        val tlSet = timelines.toSet()
        if (tlSet.size != timelines.size) add("duplicate timelines")
        placements.forEach { (id, c) ->
            if (c.timelineId !in tlSet) add("node $id on unknown timeline ${c.timelineId}")
            if (c.slot < 0) add("node $id at negative slot")
        }
        if (byCell.size != placements.size) add("two nodes share a cell")
        if (byMarkerSlot.size != markers.size) add("two markers share a slot")
        markers.forEach { (id, s) ->
            if (placements.values.any { it.slot == s }) add("marker $id slot $s holds nodes")
        }
        val used = placements.values.map { it.slot }.toSet() + markers.values
        for (s in 0 until slotCount) if (s !in used) add("slot $s is empty")
        membership.forEach { (n, g) ->
            if (n !in placements) add("membership for unplaced node $n")
            if (g !in groups) add("node $n in unknown group $g")
        }
        groups.values.forEach { g ->
            if (g.parentId != null) {
                val p = groups[g.parentId]
                if (p == null) add("group ${g.id} has unknown parent")
                else if (p.timelineId != g.timelineId) add("group ${g.id} timeline differs from parent")
            }
            if (ancestorsOf(g.id).let { it.size != it.toSet().size }) add("group ${g.id} cycle")
        }
        if (isNotEmpty()) return@buildList
        groups.values.forEach { g ->
            val members = nodesOf(g.id)
            if (members.isEmpty()) add("group ${g.id} is empty")
            if (members.any { placements.getValue(it).timelineId != g.timelineId }) {
                add("group ${g.id} has a node on another timeline")
            } else if (members.isNotEmpty()) {
                val line = nodesOn(g.timelineId)
                val first = line.indexOf(members.first())
                if (line.drop(first).take(members.size) != members) add("group ${g.id} is not contiguous")
            }
        }
    }
}
