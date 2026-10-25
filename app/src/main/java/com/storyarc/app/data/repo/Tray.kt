package com.storyarc.app.data.repo

import com.storyarc.board.Block
import com.storyarc.board.Drop
import com.storyarc.board.Item
import com.storyarc.board.extract
import com.storyarc.board.insert
import com.storyarc.board.removeNode

/**
 * Index of the tray item [item] names, or -1: a single-node item by its node, a group item by
 * its top group.
 */
fun List<Block>.indexOfItem(item: Item): Int = indexOfFirst { b ->
    when (item) {
        is Item.Node -> b.root == null && item.id in b.nodes
        is Item.GroupItem -> b.root == item.id
    }
}

/** The tray item containing group [groupId] (at any depth), or null. */
fun List<Block>.blockWithGroup(groupId: String): Block? = firstOrNull { groupId in it.groups }

/**
 * Moves [item] into the tray at insertion point [index] (0..size, counted before the move;
 * null appends). The item may come from the board or already be in the tray (a reorder).
 */
fun Layout.toTray(item: Item, index: Int? = null): Layout {
    val from = tray.indexOfItem(item)
    if (from >= 0) {
        val list = tray.toMutableList()
        val b = list.removeAt(from)
        val at = (if (index != null && index > from) index - 1 else index ?: list.size).coerceIn(0, list.size)
        list.add(at, b)
        return copy(tray = list)
    }
    val (rest, block) = board.extract(item)
    return Layout(rest, tray.toMutableList().apply { add((index ?: size).coerceIn(0, size), block) })
}

/** Places tray item [item] on the board at [drop]. */
fun Layout.place(item: Item, drop: Drop): Layout {
    val i = tray.indexOfItem(item)
    require(i >= 0) { "$item is not in the tray" }
    return Layout(board.insert(tray[i], drop), tray.filterIndexed { j, _ -> j != i })
}

fun Layout.append(block: Block) = copy(tray = tray + block)

/** The layout without [nodeIds], wherever they are; emptied groups and tray items go too. */
fun Layout.without(nodeIds: Set<String>) = Layout(
    nodeIds.filter { it in board.placements }.fold(board) { b, n -> b.removeNode(n) },
    tray.mapNotNull { it.without(nodeIds) },
)
