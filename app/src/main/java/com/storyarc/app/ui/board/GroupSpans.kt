package com.storyarc.app.ui.board

import com.storyarc.board.BoardState

/** Horizontal extent of a group on its row; [depth] is 0 for top-level groups. */
data class GroupSpan(val groupId: String, val row: Int, val first: Int, val last: Int, val depth: Int)

fun groupSpans(board: BoardState): List<GroupSpan> {
    val members = HashMap<String, MutableList<Int>>()
    for ((nodeId, cell) in board.placements) {
        for (g in board.groupChain(nodeId)) members.getOrPut(g) { ArrayList() } += cell.slot
    }
    return board.groups.values.mapNotNull { g ->
        val slots = members[g.id] ?: return@mapNotNull null
        GroupSpan(g.id, board.timelines.indexOf(g.timelineId), slots.min(), slots.max(), board.ancestorsOf(g.id).size - 1)
    }
}

/** Vertical offset of a group's bar above the bottom of its row, in strip units (see BoardDims.groupStrip). */
fun barOffsetFraction(depth: Int) = 0.2f + 0.25f * depth.coerceAtMost(3)
