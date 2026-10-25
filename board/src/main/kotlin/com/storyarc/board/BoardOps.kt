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

/**
 * Moves a node or group. With [collapse] false the result keeps slots that became empty
 * (it then breaks the density invariant); that form is only for previews, where the
 * dragged item's old column should stay put until the drop.
 */
fun BoardState.move(item: Item, drop: Drop, collapse: Boolean = true): BoardState = edit(collapse) {
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
fun BoardState.moveMarker(markerId: String, beforeSlot: Int, collapse: Boolean = true): BoardState {
    require(markerId in markers) { "unknown marker $markerId" }
    return edit(collapse) {
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

/**
 * A run of nodes detached from a board (for example, held in a tray): each node's offset from
 * the run's first slot, plus the groups wholly inside it. [groups] maps a group to its parent
 * within the block; the block's top group, if any, maps to null and contains every node.
 */
data class Block(
    val nodes: Map<String, Int>,
    val groups: Map<String, String?> = emptyMap(),
    val membership: Map<String, String> = emptyMap(),
) {
    init {
        require(nodes.isNotEmpty()) { "a block holds at least one node" }
        require(root != null || nodes.size == 1) { "a block of several nodes needs a top group" }
    }

    /** The block's top group, or null for a single ungrouped node. */
    val root: String? get() = groups.entries.firstOrNull { it.value == null }?.key

    val width: Int get() = nodes.values.max() + 1

    /** The item this block moves as on a board. */
    val item: Item get() = root?.let { Item.GroupItem(it) } ?: Item.Node(nodes.keys.single())

    /** Groups of [nodeId] within the block, innermost first. */
    fun groupChain(nodeId: String): List<String> = generateSequence(membership[nodeId]) { groups[it] }.toList()

    /** The part of the block inside [groupId], as a block of its own. */
    fun part(groupId: String): Block {
        require(groupId in groups) { "unknown group $groupId" }
        val inside = nodes.filterKeys { groupId in groupChain(it) }
        val first = inside.values.min()
        val subgroups = groups.keys.filter { g -> generateSequence(g) { groups[it] }.any { it == groupId } }
        return Block(
            inside.mapValues { it.value - first },
            subgroups.associateWith { if (it == groupId) null else groups[it] },
            membership.filterKeys { it in inside },
        )
    }

    /** The block without [nodeIds], with emptied groups dropped; null if nothing is left. */
    fun without(nodeIds: Set<String>): Block? {
        val left = nodes.filterKeys { it !in nodeIds }
        if (left.isEmpty()) return null
        val first = left.values.min()
        val members = membership.filterKeys { it in left }
        val live = members.values.flatMap { g -> generateSequence(g) { groups[it] }.toList() }.toSet()
        return Block(left.mapValues { it.value - first }, groups.filterKeys { it in live }, members)
    }
}

/**
 * Takes [item] off the board and returns the board without it plus the item as a [Block]. A
 * single node leaves its groups; a group takes its nested groups along. With [collapse] false
 * the emptied slots are kept (for previews, as in [move]).
 */
fun BoardState.extract(item: Item, collapse: Boolean = true): Pair<BoardState, Block> {
    val block = when (item) {
        is Item.Node -> {
            requireNotNull(placements[item.id]) { "unknown node ${item.id}" }
            Block(mapOf(item.id to 0))
        }
        is Item.GroupItem -> {
            requireNotNull(groups[item.id]) { "unknown group ${item.id}" }
            val members = nodesOf(item.id)
            val first = placements.getValue(members.first()).slot
            val inside = groups.keys.filter { item.id in ancestorsOf(it) }
            Block(
                members.associateWith { placements.getValue(it).slot - first },
                inside.associateWith { if (it == item.id) null else groups.getValue(it).parentId },
                members.associateWith { membership.getValue(it) },
            )
        }
    }
    val board = edit(collapse) {
        block.nodes.keys.forEach { placements.remove(it); membership.remove(it) }
        block.groups.keys.forEach { groups.remove(it) }
    }
    return board to block
}

/** Places a detached [block] at [drop] (see [Drop]); its ids must not already be on the board. */
fun BoardState.insert(block: Block, drop: Drop, collapse: Boolean = true): BoardState {
    require(block.nodes.keys.none { it in placements }) { "block nodes already placed" }
    require(block.groups.keys.none { it in groups }) { "block groups already present" }
    return edit(collapse) {
        require(drop.timelineId in timelines) { "unknown timeline ${drop.timelineId}" }
        block.groups.forEach { (id, parent) -> groups[id] = Group(id, drop.timelineId, parent) }
        membership.putAll(block.membership)
        land(block.nodes.toList(), block.width, drop, block.item)
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

private inline fun BoardState.edit(collapse: Boolean = true, block: Work.() -> Unit): BoardState =
    Work(this).apply(block).apply { pruneEmptyGroups(); if (collapse) collapse() }.snapshot()

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
