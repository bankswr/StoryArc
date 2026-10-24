package com.storyarc.app.ui.board

import com.storyarc.board.BoardState
import com.storyarc.board.Drop
import com.storyarc.board.Item
import com.storyarc.board.move
import com.storyarc.board.moveMarker

/** Something being dragged on the board. */
sealed interface DragItem {
    data class Node(val id: String) : DragItem
    data class Group(val id: String) : DragItem
    data class Marker(val id: String) : DragItem
}

/** Where a drag would land, in the coordinates of the board before the move. */
sealed interface DragTarget {
    data class Place(val drop: Drop) : DragTarget
    data class MarkerAt(val beforeSlot: Int) : DragTarget
}

/** A resolved target, plus the marker that is holding the item back, if any. */
data class Resolution(val target: DragTarget, val blockedBy: String? = null)

/** Fraction of a node column at either edge that counts as "between" rather than "onto". */
const val BETWEEN_EDGE = 0.22f

/**
 * Resolves the drop target under content-space point ([cx], [cy]). Markers not in [unlocked]
 * act as barriers: a target beyond one is clamped to its near side and [Resolution.blockedBy]
 * names it so the caller can run the crossing dwell.
 */
fun resolveTarget(
    board: BoardState,
    g: BoardGeometry,
    item: DragItem,
    cx: Float,
    cy: Float,
    unlocked: Set<String>,
): Resolution {
    val s = g.slotAt(cx)
    val frac = ((cx - g.x(s)) / g.width(s)).coerceIn(0f, 1f)
    if (item is DragItem.Marker) return Resolution(DragTarget.MarkerAt(if (frac < 0.5f) s else s + 1))

    val row = (cy / g.rowHeight).toInt().coerceIn(0, board.timelines.lastIndex)
    val t = board.timelines[row]
    val drop: Drop = when {
        board.isMarkerSlot(s) -> Drop.Between(t, if (frac < 0.5f) s else s + 1)
        frac < BETWEEN_EDGE -> Drop.Between(t, s)
        frac > 1 - BETWEEN_EDGE -> Drop.Between(t, s + 1)
        else -> Drop.Onto(t, s)
    }
    val origin = originSlot(board, item) ?: return Resolution(DragTarget.Place(drop))
    // A Between(x) drop lands just before slot x.
    val pos = when (drop) {
        is Drop.Between -> drop.slot - 0.5f
        is Drop.Onto -> drop.slot.toFloat()
    }
    val locked = board.markers.filterKeys { it !in unlocked }
    val barrier = if (pos > origin) {
        locked.entries.filter { it.value > origin && it.value < pos }.minByOrNull { it.value }
    } else {
        locked.entries.filter { it.value < origin && it.value > pos }.maxByOrNull { it.value }
    } ?: return Resolution(DragTarget.Place(drop))
    val clamped = Drop.Between(t, if (barrier.value > origin) barrier.value else barrier.value + 1)
    return Resolution(DragTarget.Place(clamped), barrier.key)
}

private fun originSlot(board: BoardState, item: DragItem): Int? = when (item) {
    is DragItem.Node -> board.placements[item.id]?.slot
    is DragItem.Group -> board.nodesOf(item.id).firstOrNull()?.let { board.placements.getValue(it).slot }
    is DragItem.Marker -> null
}

/** The board as it would look after dropping [item] at [target]. */
fun preview(board: BoardState, item: DragItem, target: DragTarget): BoardState = when {
    item is DragItem.Node && target is DragTarget.Place -> board.move(Item.Node(item.id), target.drop)
    item is DragItem.Group && target is DragTarget.Place -> board.move(Item.GroupItem(item.id), target.drop)
    item is DragItem.Marker && target is DragTarget.MarkerAt -> board.moveMarker(item.id, target.beforeSlot)
    else -> board
}
