package com.storyarc.app.ui.board

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlin.math.floor
import kotlin.math.hypot

/** Tray sizes at zoom-independent scale (the tray does not zoom with the board). */
object TrayDims {
    val header = 36.dp
    val bottomExpanded = 120.dp
    val sideExpanded = 152.dp
    val bottomCollapsed = 36.dp
    val sideCollapsed = 44.dp
    val pad = 8.dp
    val gap = 8.dp
    val bottomItem = 128.dp
    val itemHeight = 68.dp
    val sideItemHeight = 60.dp
    val trash = 56.dp

    /** Thickness of the tray across its edge: its height at the bottom, its width at the side. */
    fun thickness(side: Boolean, expanded: Boolean) = when {
        side && expanded -> sideExpanded
        side -> sideCollapsed
        expanded -> bottomExpanded
        else -> bottomCollapsed
    }
}

/**
 * Pixel layout of the tray and the trash target inside a [width]×[height] canvas. The tray
 * runs along the bottom edge, or the right edge when [side]; items scroll along it by [scroll].
 */
class TrayGeometry(
    val side: Boolean,
    val expanded: Boolean,
    val width: Float,
    val height: Float,
    val count: Int,
    scroll: Float,
    density: Density,
) {
    private val headerPx: Float
    private val pad: Float
    private val gap: Float
    private val trashPx: Float
    private val trashSlack: Float
    val itemSize: Size

    init {
        with(density) {
            headerPx = TrayDims.header.toPx()
            pad = TrayDims.pad.toPx()
            gap = TrayDims.gap.toPx()
            trashPx = TrayDims.trash.toPx()
            trashSlack = 12.dp.toPx()
            val t = TrayDims.thickness(side, true).toPx()
            itemSize = if (side) Size(t - 2 * pad, TrayDims.sideItemHeight.toPx()) else Size(TrayDims.bottomItem.toPx(), TrayDims.itemHeight.toPx())
        }
    }

    val thickness: Float = with(density) { TrayDims.thickness(side, expanded).toPx() }

    val rect: Rect = if (side) Rect(width - thickness, 0f, width, height) else Rect(0f, height - thickness, width, height)

    /** The board's area: the canvas minus the tray. */
    val boardSize: Size = if (side) Size(width - thickness, height) else Size(width, height - thickness)

    val header = Rect(rect.left, rect.top, rect.right, rect.top + headerPx)

    /** Where items are drawn; empty when collapsed. */
    val itemArea: Rect = if (expanded) Rect(rect.left, header.bottom, rect.right, rect.bottom) else Rect(rect.left, header.bottom, rect.right, header.bottom)

    private val stride = (if (side) itemSize.height else itemSize.width) + gap
    private val areaStart = if (side) itemArea.top else itemArea.left
    private val areaLength = if (side) itemArea.height else itemArea.width

    /** Scroll range, leaving room at the end for the trash target. */
    val maxScroll: Float = (2 * pad + count * stride - gap + trashPx + pad - areaLength).coerceAtLeast(0f)

    val scroll = scroll.coerceIn(0f, maxScroll)

    private fun along(p: Offset) = if (side) p.y else p.x

    fun itemRect(i: Int): Rect {
        val start = areaStart + pad + i * stride - scroll
        return if (side) Rect(Offset(itemArea.left + pad, start), itemSize)
        else Rect(Offset(start, itemArea.top + (itemArea.height - itemSize.height) / 2), itemSize)
    }

    /** Index of the item under [p], or null. */
    fun itemAt(p: Offset): Int? {
        if (!expanded || !itemArea.contains(p)) return null
        val i = floor((along(p) - areaStart - pad + scroll) / stride).toInt()
        return i.takeIf { it in 0 until count && itemRect(it).contains(p) }
    }

    /** Insertion point (0..count) for a drop at [p]; the end when collapsed. */
    fun insertionAt(p: Offset): Int {
        if (!expanded) return count
        return floor((along(p) - areaStart - pad + scroll + stride / 2) / stride).toInt().coerceIn(0, count)
    }

    /** Center of the trash target shown while dragging: at the tray's far end. */
    val trashCenter: Offset = if (side) {
        Offset(if (expanded) rect.center.x else rect.left - pad - trashPx / 2, rect.bottom - pad - trashPx / 2)
    } else {
        Offset(rect.right - pad - trashPx / 2, if (expanded) itemArea.center.y else rect.top - pad - trashPx / 2)
    }

    val trashRect = Rect(trashCenter, trashPx / 2)

    fun onTrash(p: Offset) = hypot(p.x - trashCenter.x, p.y - trashCenter.y) <= trashPx / 2 + trashSlack

    fun contains(p: Offset) = rect.contains(p)
}
