package com.storyarc.board

/**
 * Where a dragged item lands. Slots are in the coordinates of the board *before* the move.
 */
sealed interface Drop {
    val timelineId: String

    /** Insert new slots before [slot] across every timeline and place the item there. */
    data class Between(override val timelineId: String, val slot: Int) : Drop

    /**
     * Place the item at [slot] on [timelineId], pushing that timeline's nodes forward as needed.
     * A marker slot is not a valid target; it is treated as [Between] the marker and its
     * predecessor, so callers should resolve marker targets themselves.
     */
    data class Onto(override val timelineId: String, val slot: Int) : Drop
}

/** Something that can be picked up and moved along or across timelines. */
sealed interface Item {
    data class Node(val id: String) : Item
    data class GroupItem(val id: String) : Item
}

fun BoardState.addTimeline(timelineId: String, index: Int = timelines.size): BoardState {
    require(timelineId !in timelines) { "timeline $timelineId already present" }
    return copy(timelines = timelines.toMutableList().apply { add(index.coerceIn(0, size), timelineId) })
}

fun BoardState.moveTimeline(timelineId: String, toIndex: Int): BoardState {
    require(timelineId in timelines) { "unknown timeline $timelineId" }
    val rest = timelines - timelineId
    return copy(timelines = rest.toMutableList().apply { add(toIndex.coerceIn(0, size), timelineId) })
}

/** Removes a timeline with every node and group on it. */
fun BoardState.removeTimeline(timelineId: String): BoardState = edit {
    timelines.remove(timelineId)
    placements.entries.removeAll { it.value.timelineId == timelineId }
    groups.entries.removeAll { it.value.timelineId == timelineId }
    membership.keys.retainAll(placements.keys)
}

/** Places a node that is not yet on this board. */
fun BoardState.addNode(nodeId: String, drop: Drop): BoardState {
    require(nodeId !in placements) { "node $nodeId already placed" }
    return edit { land(listOf(nodeId to 0), 1, drop, Item.Node(nodeId)) }
}

fun BoardState.removeNode(nodeId: String): BoardState = edit {
    placements.remove(nodeId)
    membership.remove(nodeId)
}

fun BoardState.move(item: Item, drop: Drop): BoardState = edit {
    val block: List<Pair<String, Int>> = when (item) {
        is Item.Node -> {
            val c = requireNotNull(placements[item.id]) { "unknown node ${item.id}" }
            membership.remove(item.id)
            listOf(item.id to c.slot)
        }
        is Item.GroupItem -> {
            requireNotNull(groups[item.id]) { "unknown group ${item.id}" }
            groups[item.id] = groups.getValue(item.id).copy(parentId = null)
            snapshot().nodesOf(item.id).map { it to placements.getValue(it).slot }
        }
    }
    val first = block.minOf { it.second }
    val width = block.maxOf { it.second } - first + 1
    block.forEach { placements.remove(it.first) }
    land(block.map { it.first to it.second - first }, width, drop, item)
}

/** Adds a marker in a new slot inserted before [beforeSlot]. */
fun BoardState.addMarker(markerId: String, beforeSlot: Int): BoardState {
    require(markerId !in markers) { "marker $markerId already present" }
    return edit {
        insertColumns(beforeSlot.coerceIn(0, slotCount), 1)
        markers[markerId] = beforeSlot.coerceIn(0, slotCount)
    }
}

/** Moves a marker to a new slot inserted before [beforeSlot] (pre-move coordinates). */
fun BoardState.moveMarker(markerId: String, beforeSlot: Int): BoardState {
    require(markerId in markers) { "unknown marker $markerId" }
    return edit {
        val at = beforeSlot.coerceIn(0, slotCount)
        markers.remove(markerId)
        insertColumns(at, 1)
        markers[markerId] = at
    }
}

fun BoardState.removeMarker(markerId: String): BoardState = edit { markers.remove(markerId) }

/**
 * Why [nodeIds] cannot form a new group, or null if they can: they must be a consecutive run
 * on one timeline and must nest cleanly with existing groups.
 */
fun BoardState.groupingProblem(nodeIds: Collection<String>): String? {
    if (nodeIds.isEmpty()) return "no nodes selected"
    if (nodeIds.any { it !in placements }) return "unknown node"
    val timeline = placements.getValue(nodeIds.first()).timelineId
    if (nodeIds.any { placements.getValue(it).timelineId != timeline }) return "nodes are on different timelines"
    val line = nodesOn(timeline)
    val sorted = nodeIds.toSet().sortedBy { line.indexOf(it) }
    val start = line.indexOf(sorted.first())
    if (line.drop(start).take(sorted.size) != sorted) return "nodes are not adjacent"
    val set = sorted.toSet()
    for (g in groups.keys) {
        val members = nodesOf(g).toSet()
        val overlap = members.intersect(set)
        if (overlap.isNotEmpty() && overlap != members && overlap != set) return "selection partially overlaps a group"
    }
    return null
}

/** Groups [nodeIds] (see [groupingProblem]) under a new group nested in the innermost group containing them all. */
fun BoardState.createGroup(groupId: String, nodeIds: Collection<String>): BoardState {
    require(groupId !in groups) { "group $groupId already present" }
    groupingProblem(nodeIds)?.let { throw IllegalArgumentException(it) }
    val set = nodeIds.toSet()
    val parent = groupChain(set.first()).firstOrNull { g -> set.all { g in groupChain(it) } }
    val timeline = placements.getValue(set.first()).timelineId
    val adoptedGroups = childGroups(parent).filter { set.containsAll(nodesOf(it)) }
    return edit {
        groups[groupId] = Group(groupId, timeline, parent)
        adoptedGroups.forEach { groups[it] = groups.getValue(it).copy(parentId = groupId) }
        set.forEach { if (membership[it] == parent) membership[it] = groupId }
    }
}

/** Dissolves a group; its members and child groups move up to its parent. */
fun BoardState.ungroup(groupId: String): BoardState {
    val g = requireNotNull(groups[groupId]) { "unknown group $groupId" }
    return edit {
        groups.remove(groupId)
        groups.replaceAll { _, child -> if (child.parentId == groupId) child.copy(parentId = g.parentId) else child }
        membership.entries.toList().forEach { (n, m) ->
            if (m == groupId) {
                if (g.parentId == null) membership.remove(n) else membership[n] = g.parentId
            }
        }
    }
}

private inline fun BoardState.edit(block: Work.() -> Unit): BoardState =
    Work(this).apply(block).apply { pruneEmptyGroups(); collapse() }.snapshot()

/** Mutable working copy used to implement operations; always finished by prune + collapse. */
private class Work(s: BoardState) {
    val timelines = s.timelines.toMutableList()
    val placements = HashMap(s.placements)
    val markers = HashMap(s.markers)
    val groups = HashMap(s.groups)
    val membership = HashMap(s.membership)

    val slotCount get() = maxOf(placements.values.maxOfOrNull { it.slot } ?: -1, markers.values.maxOrNull() ?: -1) + 1

    fun snapshot() = BoardState(timelines.toList(), placements.toMap(), markers.toMap(), groups.toMap(), membership.toMap())

    fun insertColumns(at: Int, count: Int) {
        placements.replaceAll { _, c -> if (c.slot >= at) c.copy(slot = c.slot + count) else c }
        markers.replaceAll { _, m -> if (m >= at) m + count else m }
    }

    /** Removes slots holding neither nodes nor a marker. */
    fun collapse() {
        val used = (placements.values.map { it.slot } + markers.values).toSortedSet()
        val remap = used.withIndex().associate { (i, s) -> s to i }
        placements.replaceAll { _, c -> c.copy(slot = remap.getValue(c.slot)) }
        markers.replaceAll { _, m -> remap.getValue(m) }
    }

    /** Deletes groups that no longer contain any placed node (directly or through children). */
    fun pruneEmptyGroups() {
        val live = HashSet<String>()
        membership.forEach { (n, g) ->
            if (n in placements) generateSequence(g) { groups[it]?.parentId }.forEach { live += it }
        }
        groups.keys.retainAll(live)
        membership.entries.removeAll { it.key !in placements || it.value !in groups }
    }

    /**
     * Places a detached block (node ids with offsets from its first slot) according to [drop]
     * and attaches [item] to the innermost group that encloses its landing spot.
     */
    fun land(block: List<Pair<String, Int>>, width: Int, drop: Drop, item: Item) {
        require(drop.timelineId in timelines) { "unknown timeline ${drop.timelineId}" }
        val t = drop.timelineId
        val start: Int = when (drop) {
            is Drop.Between -> drop.slot.coerceIn(0, slotCount).also { insertColumns(it, width) }
            is Drop.Onto -> {
                val s = drop.slot.coerceIn(0, slotCount)
                if (s in markers.values) s.also { insertColumns(it, width) } else s.also { pushFrom(t, it, width) }
            }
        }
        block.forEach { (id, off) -> placements[id] = Cell(t, start + off) }

        val view = snapshot()
        val line = view.nodesOn(t)
        val ids = block.map { it.first }.toSet()
        val prev = line.lastOrNull { it !in ids && placements.getValue(it).slot < start }
        val next = line.firstOrNull { it !in ids && placements.getValue(it).slot > start }
        val container = if (prev == null || next == null) null else {
            val nextChain = view.groupChain(next)
            view.groupChain(prev).firstOrNull { it in nextChain }
        }
        when (item) {
            is Item.Node -> if (container != null) membership[item.id] = container
            is Item.GroupItem -> {
                groups[item.id] = groups.getValue(item.id).copy(parentId = container)
                groups.replaceAll { gid, g -> if (item.id in view.ancestorsOf(gid)) g.copy(timelineId = t) else g }
            }
        }
    }

    /**
     * Frees slots `start until start + width` on [timeline] by pushing its nodes forward. Empty
     * slots absorb the push; a push never crosses a marker, so new slots are inserted in front
     * of the marker instead.
     */
    fun pushFrom(timeline: String, start: Int, width: Int) {
        var end = start + width - 1
        markers.values.filter { it in start..end }.minOrNull()?.let { m -> insertColumns(m, end - m + 1) }
        var cursor = end + 1
        val followers = placements.entries.filter { it.value.timelineId == timeline && it.value.slot >= start }
            .sortedBy { it.value.slot }.map { it.key }
        for (id in followers) {
            val old = placements.getValue(id).slot
            if (old >= cursor) break
            end = cursor
            markers.values.filter { it in (old + 1)..end }.minOrNull()?.let { m -> insertColumns(m, end - m + 1) }
            placements[id] = Cell(timeline, end)
            cursor = end + 1
        }
    }
}
