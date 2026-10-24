package com.storyarc.app.ui.board

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.storyarc.app.data.db.NodeEntity
import kotlin.math.roundToInt

/** Scroll position (content pixels) and zoom of a board, saved across configuration changes. */
@Stable
class BoardViewport(scrollX: Float = 0f, scrollY: Float = 0f, zoom: Float = 1f) {
    var scrollX by mutableFloatStateOf(scrollX)
    var scrollY by mutableFloatStateOf(scrollY)
    var zoom by mutableFloatStateOf(zoom)

    /** Leftmost slot drawn in the last layout; not state, read only by event handlers. */
    var firstVisibleSlot = 0
        internal set

    companion object {
        const val MIN_ZOOM = 0.5f
        const val MAX_ZOOM = 2f
        val Saver = Saver<BoardViewport, List<Float>>(
            save = { listOf(it.scrollX, it.scrollY, it.zoom) },
            restore = { BoardViewport(it[0], it[1], it[2]) },
        )
    }
}

@Composable
fun rememberBoardViewport() = rememberSaveable(saver = BoardViewport.Saver) { BoardViewport() }

/** What a tap on the board hit. */
sealed interface BoardHit {
    data class Node(val nodeId: String) : BoardHit
    data class Marker(val markerId: String) : BoardHit
    data class Empty(val timelineId: String, val slot: Int) : BoardHit
    data class Timeline(val timelineId: String) : BoardHit
}

/** Base sizes at zoom 1. */
object BoardDims {
    val header = 104.dp
    val slot = 132.dp
    val marker = 40.dp
    val row = 88.dp
    val cardPadding = 4.dp
}

@Composable
fun BoardCanvas(
    ui: BoardUi,
    viewport: BoardViewport,
    onTap: (BoardHit) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    val headerPx = with(density) { BoardDims.header.toPx() }
    val geometry = remember(ui.board, viewport.zoom, density) {
        with(density) {
            BoardGeometry(
                ui.board,
                slotWidth = BoardDims.slot.toPx() * viewport.zoom,
                markerWidth = BoardDims.marker.toPx() * viewport.zoom,
                rowHeight = BoardDims.row.toPx() * viewport.zoom,
            )
        }
    }
    val colors = MaterialTheme.colorScheme

    BoxWithConstraints(modifier.clipToBounds()) {
        val viewW = constraints.maxWidth.toFloat() - headerPx
        val viewH = constraints.maxHeight.toFloat()
        fun clamp() {
            viewport.scrollX = viewport.scrollX.coerceIn(0f, (geometry.contentWidth - viewW).coerceAtLeast(0f))
            viewport.scrollY = viewport.scrollY.coerceIn(0f, (geometry.contentHeight - viewH).coerceAtLeast(0f))
        }
        clamp()
        val sx = viewport.scrollX
        val sy = viewport.scrollY
        val slots = geometry.visibleSlots(sx, sx + viewW)
        viewport.firstVisibleSlot = slots.first
        val rows = 0 until ui.timelines.size

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(geometry) {
                    detectTransformGestures { centroid, pan, zoomChange, _ ->
                        val old = viewport.zoom
                        val new = (old * zoomChange).coerceIn(BoardViewport.MIN_ZOOM, BoardViewport.MAX_ZOOM)
                        val f = new / old
                        // Keep the content point under the centroid fixed while zooming.
                        val cx = centroid.x - headerPx
                        viewport.scrollX = (viewport.scrollX + cx) * f - cx - pan.x
                        viewport.scrollY = (viewport.scrollY + centroid.y) * f - centroid.y - pan.y
                        viewport.zoom = new
                    }
                }
                .pointerInput(geometry, ui) {
                    detectTapGestures { p -> hitTest(ui, geometry, p, headerPx, viewport)?.let(onTap) }
                },
        ) {
            // Grid: row stripes, slot dividers, marker columns.
            Canvas(Modifier.fillMaxSize()) {
                for (r in rows) {
                    val y = geometry.y(r) - sy
                    if (r % 2 == 1) drawRect(colors.surfaceContainerLow, Offset(0f, y), Size(size.width, geometry.rowHeight))
                }
                for (s in slots) {
                    val x = headerPx + geometry.x(s) - sx
                    if (ui.board.isMarkerSlot(s)) {
                        drawRect(colors.primaryContainer, Offset(x, 0f), Size(geometry.width(s), size.height))
                    } else {
                        drawLine(colors.outlineVariant, Offset(x, 0f), Offset(x, geometry.contentHeight - sy), 1f)
                    }
                }
            }

            for (s in slots) {
                val markerId = ui.board.markerAt(s)
                if (markerId != null) {
                    MarkerLabel(
                        ui.markers[markerId]?.label.orEmpty(),
                        Modifier
                            .offset { IntOffset((headerPx + geometry.x(s) - sx).roundToInt(), 0) }
                            .width(with(density) { geometry.width(s).toDp() })
                            .fillMaxHeight(),
                    )
                    continue
                }
                for (r in rows) {
                    val nodeId = ui.board.nodeAt(ui.timelines[r].id, s) ?: continue
                    val node = ui.nodes[nodeId] ?: continue
                    NodeCard(
                        node,
                        Modifier
                            .offset { IntOffset((headerPx + geometry.x(s) - sx).roundToInt(), (geometry.y(r) - sy).roundToInt()) }
                            .size(with(density) { geometry.slotWidth.toDp() }, with(density) { geometry.rowHeight.toDp() })
                            .padding(BoardDims.cardPadding),
                    )
                }
            }

            // Pinned timeline header column.
            Box(Modifier.width(BoardDims.header).fillMaxHeight().background(colors.surfaceContainer)) {
                for (r in rows) {
                    val t = ui.timelines[r]
                    Box(
                        Modifier
                            .offset { IntOffset(0, (geometry.y(r) - sy).roundToInt()) }
                            .size(BoardDims.header, with(density) { geometry.rowHeight.toDp() })
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(t.name, style = MaterialTheme.typography.labelLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
}

private fun hitTest(ui: BoardUi, g: BoardGeometry, p: Offset, headerPx: Float, vp: BoardViewport): BoardHit? {
    val row = g.rowAt(p.y + vp.scrollY)
    if (p.x < headerPx) return row?.let { BoardHit.Timeline(ui.timelines[it].id) }
    val slot = g.slotAt(p.x - headerPx + vp.scrollX)
    ui.board.markerAt(slot)?.let { return BoardHit.Marker(it) }
    if (row == null) return null
    val t = ui.timelines[row].id
    return ui.board.nodeAt(t, slot)?.let { BoardHit.Node(it) } ?: BoardHit.Empty(t, slot)
}

@Composable
fun NodeCard(node: NodeEntity, modifier: Modifier = Modifier, lifted: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val bg = node.color?.let { Color(it) } ?: colors.secondaryContainer
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = bg,
        contentColor = if (node.color != null) readableOn(bg) else colors.onSecondaryContainer,
        shadowElevation = if (lifted) 8.dp else 1.dp,
    ) {
        Box(Modifier.padding(8.dp)) {
            if (node.text.isBlank()) {
                Text("Untitled", style = MaterialTheme.typography.bodySmall, color = colors.outline)
            } else {
                Text(node.text, style = MaterialTheme.typography.bodySmall, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun MarkerLabel(label: String, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.vertical(),
        )
    }
}

/** Lays the content out rotated a quarter turn counter-clockwise, swapping its width and height. */
fun Modifier.vertical() = layout { measurable, constraints ->
    val p = measurable.measure(Constraints(maxWidth = constraints.maxHeight, maxHeight = constraints.maxWidth))
    layout(p.height, p.width) {
        p.placeWithLayer(-(p.width - p.height) / 2, (p.width - p.height) / 2) { rotationZ = -90f }
    }
}

/** Black or white, whichever reads better on [bg]. */
fun readableOn(bg: Color): Color {
    val l = 0.2126f * bg.red + 0.7152f * bg.green + 0.0722f * bg.blue
    return if (l > 0.55f) Color.Black else Color.White
}
