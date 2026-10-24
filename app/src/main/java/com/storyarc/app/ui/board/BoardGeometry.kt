package com.storyarc.app.ui.board

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.storyarc.board.BoardState

/**
 * Maps board coordinates onto the screen. Board coordinates are (along, across): along the
 * time axis in slot order, and across it in timeline order. A horizontal board runs slots
 * left to right with timelines as rows; a vertical board runs slots top to bottom with
 * timelines as columns.
 */
data class BoardAxes(val vertical: Boolean) {
    /** Screen offset of an (along, across) pair. */
    fun screen(along: Float, across: Float) = if (vertical) Offset(across, along) else Offset(along, across)

    /** (along, across) of a screen offset, as x and y. */
    fun board(p: Offset) = if (vertical) Offset(p.y, p.x) else p

    fun size(along: Float, across: Float) = if (vertical) Size(across, along) else Size(along, across)
}

/**
 * Pixel layout of a board's content area (excluding the pinned timeline header), in board
 * coordinates: x runs along the time axis and y across timelines (see [BoardAxes]).
 * Node slots are [slotWidth] long; marker slots are the shorter [markerWidth]. One extra
 * trailing node-length column is always laid out so there is room to drop or add at the end.
 */
class BoardGeometry(
    val board: BoardState,
    val slotWidth: Float,
    val markerWidth: Float,
    val rowHeight: Float,
) {
    val columnCount = board.slotCount + 1

    /** Left edge of each column, plus the right edge of the last one at index [columnCount]. */
    private val edges: FloatArray = FloatArray(columnCount + 1).also { e ->
        for (s in 0 until columnCount) e[s + 1] = e[s] + width(s)
    }

    val contentWidth get() = edges[columnCount]
    val contentHeight get() = rowHeight * board.timelines.size

    fun width(slot: Int) = if (board.isMarkerSlot(slot)) markerWidth else slotWidth

    fun x(slot: Int) = edges[slot.coerceIn(0, columnCount)]

    fun y(row: Int) = row * rowHeight

    /** Left edge at a fractional slot position, interpolating across column widths (for animation). */
    fun xAt(slot: Float): Float {
        val i = slot.toInt().coerceIn(0, columnCount - 1)
        return x(i) + (slot - i) * width(i)
    }

    /** Column containing content-space [x], clamped to the laid-out columns. */
    fun slotAt(x: Float): Int {
        if (x <= 0f) return 0
        var lo = 0
        var hi = columnCount - 1
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (edges[mid] <= x) lo = mid else hi = mid - 1
        }
        return lo
    }

    /** Row index at content-space [y], or null when outside every timeline. */
    fun rowAt(y: Float): Int? = if (y < 0f) null else (y / rowHeight).toInt().takeIf { it < board.timelines.size }

    /** Columns intersecting the horizontal span `[from, to)`. */
    fun visibleSlots(from: Float, to: Float): IntRange = slotAt(from)..slotAt(to)
}
