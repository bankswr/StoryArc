package com.storyarc.app.ui.board

import com.storyarc.board.BoardState

/**
 * Pixel layout of a board's content area (excluding the pinned timeline header column).
 * Node slots are [slotWidth] wide; marker slots are the narrower [markerWidth]. One extra
 * trailing node-width column is always laid out so there is room to drop or add at the end.
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
