package com.storyarc.app.ui.board

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.drag
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.storyarc.app.data.db.NodeEntity
import com.storyarc.board.BoardState
import kotlinx.coroutines.delay
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
    data class Group(val groupId: String) : BoardHit
}

/** Base sizes at zoom 1. */
object BoardDims {
    val header = 104.dp
    val slot = 132.dp
    val marker = 40.dp
    val row = 88.dp
    val cardPadding = 4.dp

    /** Band along the bottom of each row where group bars are drawn and grabbed. */
    val groupStrip = 16.dp
    val autoScrollEdge = 56.dp
}

/** Marker-crossing behaviour while dragging. */
data class BarrierConfig(val delayEnabled: Boolean = true, val delayMs: Long = 500)

/** State of an in-progress drag. Positions are in canvas pixels. */
@Stable
private class DragSession(val item: DragItem, start: Offset, val grab: Offset, initial: Resolution, preview: BoardState) {
    var pointer by mutableStateOf(start)
    var resolution by mutableStateOf(initial)
    var preview by mutableStateOf(preview)
    val unlocked = HashSet<String>()

    /** Event uptime at which the current barrier started blocking, for event-driven dwell. */
    var blockedSinceMs: Long? = null
}

@Composable
fun BoardCanvas(
    ui: BoardUi,
    viewport: BoardViewport,
    barrier: BarrierConfig,
    onTap: (BoardHit) -> Unit,
    onDrop: (DragItem, DragTarget) -> Unit,
    modifier: Modifier = Modifier,
    selected: Set<String> = emptySet(),
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val headerPx = with(density) { BoardDims.header.toPx() }
    val stripPx = with(density) { BoardDims.groupStrip.toPx() } * viewport.zoom
    val edgePx = with(density) { BoardDims.autoScrollEdge.toPx() }
    val colors = MaterialTheme.colorScheme

    fun geometryFor(board: BoardState) = with(density) {
        BoardGeometry(
            board,
            slotWidth = BoardDims.slot.toPx() * viewport.zoom,
            markerWidth = BoardDims.marker.toPx() * viewport.zoom,
            rowHeight = BoardDims.row.toPx() * viewport.zoom,
        )
    }

    var session by remember { mutableStateOf<DragSession?>(null) }
    // After a drop, keep showing the previewed layout until the saved board arrives.
    var pending by remember { mutableStateOf<Pair<BoardState, BoardState>?>(null) }
    LaunchedEffect(ui.board) { if (pending?.first !== ui.board) pending = null }

    val shown: BoardState = session?.preview ?: pending?.takeIf { it.first === ui.board }?.second ?: ui.board
    // Targets are always resolved against the board as it was before the drag.
    val baseGeometry = remember(ui.board, viewport.zoom, density) { geometryFor(ui.board) }
    val geometry = remember(shown, viewport.zoom, density) { geometryFor(shown) }
    val baseSpans = remember(ui.board) { groupSpans(ui.board) }
    val spans = remember(shown) { groupSpans(shown) }

    val currentUi by rememberUpdatedState(ui)
    val currentBase by rememberUpdatedState(baseGeometry)
    val currentSpans by rememberUpdatedState(baseSpans)
    val currentStrip by rememberUpdatedState(stripPx)
    val currentBarrier by rememberUpdatedState(barrier)
    val currentOnDrop by rememberUpdatedState(onDrop)
    val currentOnTap by rememberUpdatedState(onTap)

    BoxWithConstraints(modifier.clipToBounds()) {
        val viewW = constraints.maxWidth.toFloat() - headerPx
        val viewH = constraints.maxHeight.toFloat()
        fun clamp() {
            viewport.scrollX = viewport.scrollX.coerceIn(0f, (geometry.contentWidth - viewW).coerceAtLeast(0f))
            viewport.scrollY = viewport.scrollY.coerceIn(0f, (geometry.contentHeight - viewH).coerceAtLeast(0f))
        }
        clamp()

        fun resolveAt(item: DragItem, pointer: Offset, unlocked: Set<String>): Resolution {
            val all = if (currentBarrier.delayEnabled) unlocked else currentUi.board.markers.keys
            val cx = pointer.x - headerPx + viewport.scrollX
            val cy = pointer.y + viewport.scrollY
            return resolveTarget(currentUi.board, currentBase, item, cx, cy, all)
        }

        fun update(s: DragSession) {
            val r = resolveAt(s.item, s.pointer, s.unlocked)
            if (r != s.resolution) {
                s.resolution = r
                s.preview = preview(currentUi.board, s.item, r.target)
            }
        }

        // Edge auto-scroll direction for the current pointer, zero when the board can't move that way.
        fun depth(d: Float) = (d / edgePx).coerceIn(0f, 1f)
        val scrollDir: Offset = session?.let { st ->
            val p = st.pointer
            val dx = depth(p.x - (headerPx + viewW - edgePx)) - depth(headerPx + edgePx - p.x)
            val dy = depth(p.y - (viewH - edgePx)) - depth(edgePx - p.y)
            val maxX = (geometry.contentWidth - viewW).coerceAtLeast(0f)
            val maxY = (geometry.contentHeight - viewH).coerceAtLeast(0f)
            Offset(
                if ((dx < 0 && viewport.scrollX > 0f) || (dx > 0 && viewport.scrollX < maxX)) dx else 0f,
                if ((dy < 0 && viewport.scrollY > 0f) || (dy > 0 && viewport.scrollY < maxY)) dy else 0f,
            )
        } ?: Offset.Zero
        val scrolling = scrollDir != Offset.Zero

        // Frames run only while auto-scrolling, so an idle drag costs nothing.
        LaunchedEffect(session, scrolling) {
            val s = session ?: return@LaunchedEffect
            if (!scrolling) return@LaunchedEffect
            var last = withFrameNanos { it }
            while (true) {
                val now = withFrameNanos { it }
                val step = 900f * (now - last) / 1_000_000_000f
                last = now
                val p = s.pointer
                viewport.scrollX += step * (depth(p.x - (headerPx + viewW - edgePx)) - depth(headerPx + edgePx - p.x))
                viewport.scrollY += step * (depth(p.y - (viewH - edgePx)) - depth(edgePx - p.y))
                clamp()
                update(s)
            }
        }

        fun unlock(s: DragSession, markerId: String) {
            if (!s.unlocked.add(markerId)) return
            haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
            update(s)
        }

        // Marker-crossing dwell: holding against a barrier for the configured time unlocks it.
        // A timer covers a finger held perfectly still; pointer events also check elapsed event
        // time (see the drag loop), which keeps the rule deterministic under test clocks.
        val blockedBy = session?.resolution?.blockedBy
        LaunchedEffect(session, blockedBy) {
            val s = session ?: return@LaunchedEffect
            val b = blockedBy ?: return@LaunchedEffect
            delay(currentBarrier.delayMs)
            unlock(s, b)
        }

        val sx = viewport.scrollX
        val sy = viewport.scrollY
        val slots = geometry.visibleSlots(sx, sx + viewW)
        viewport.firstVisibleSlot = slots.first
        val rowOf = shown.timelines.withIndex().associate { (i, t) -> t to i }
        val dragged: Set<String> = when (val item = session?.item) {
            is DragItem.Node -> setOf(item.id)
            is DragItem.Group -> shown.nodesOf(item.id).toSet()
            else -> emptySet()
        }
        val draggedMarker = (session?.item as? DragItem.Marker)?.id

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
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
                .pointerInput(Unit) {
                    detectTapGestures { p -> hitTest(currentUi, currentBase, currentSpans, currentStrip, p, headerPx, viewport)?.let(currentOnTap) }
                }
                .pointerInput(Unit) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val ui0 = currentUi
                        val g0 = currentBase
                        val item = when (val hit = hitTest(ui0, g0, currentSpans, currentStrip, down.position, headerPx, viewport)) {
                            is BoardHit.Node -> DragItem.Node(hit.nodeId)
                            is BoardHit.Marker -> DragItem.Marker(hit.markerId)
                            is BoardHit.Group -> DragItem.Group(hit.groupId)
                            else -> null
                        } ?: return@awaitEachGesture
                        val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        val origin = itemOrigin(ui0.board, g0, item, headerPx, viewport)
                        val r = resolveAt(item, longPress.position, emptySet())
                        val s = DragSession(item, longPress.position, longPress.position - origin, r, preview(ui0.board, item, r.target))
                        session = s
                        longPress.consume()
                        val completed = drag(longPress.id) { change ->
                            s.pointer = change.position
                            update(s)
                            val blocked = s.resolution.blockedBy
                            val since = s.blockedSinceMs
                            when {
                                blocked == null -> s.blockedSinceMs = null
                                since == null -> s.blockedSinceMs = change.uptimeMillis
                                change.uptimeMillis - since >= currentBarrier.delayMs -> {
                                    s.blockedSinceMs = null
                                    unlock(s, blocked)
                                }
                            }
                            change.consume()
                        }
                        currentEvent.changes.forEach { it.consume() }
                        if (completed && s.preview != ui0.board) {
                            pending = ui0.board to s.preview
                            currentOnDrop(item, s.resolution.target)
                        }
                        session = null
                    }
                },
        ) {
            // Grid: row stripes, slot dividers, marker columns.
            Canvas(Modifier.fillMaxSize()) {
                for (r in shown.timelines.indices) {
                    val y = geometry.y(r) - sy
                    if (r % 2 == 1) drawRect(colors.surfaceContainerLow, Offset(0f, y), Size(size.width, geometry.rowHeight))
                }
                for (s in slots) {
                    val x = headerPx + geometry.x(s) - sx
                    val markerId = shown.markerAt(s)
                    if (markerId != null) {
                        val fill = when (markerId) {
                            blockedBy -> colors.tertiaryContainer
                            draggedMarker -> colors.surfaceVariant
                            else -> colors.primaryContainer
                        }
                        drawRect(fill, Offset(x, 0f), Size(geometry.width(s), size.height))
                    } else {
                        drawLine(colors.outlineVariant, Offset(x, 0f), Offset(x, geometry.contentHeight - sy), 1f)
                    }
                }
                val barH = 3.dp.toPx() * viewport.zoom
                for (span in spans) {
                    if (span.last < slots.first || span.first > slots.last) continue
                    val color = ui.groups[span.groupId]?.color?.let { Color(it) } ?: colors.tertiary
                    val left = headerPx + geometry.x(span.first) - sx + BoardDims.cardPadding.toPx()
                    val right = headerPx + geometry.x(span.last) + geometry.width(span.last) - sx - BoardDims.cardPadding.toPx()
                    val y = geometry.y(span.row + 1) - sy - stripPx * barOffsetFraction(span.depth)
                    drawRoundRect(
                        color.copy(alpha = if (span.groupId == (session?.item as? DragItem.Group)?.id) 0.35f else 1f),
                        Offset(left, y - barH / 2), Size(right - left, barH),
                        androidx.compose.ui.geometry.CornerRadius(barH / 2),
                    )
                }
            }

            for (s in slots) {
                val markerId = shown.markerAt(s) ?: continue
                if (markerId == draggedMarker) continue
                MarkerLabel(
                    ui.markers[markerId]?.label.orEmpty(),
                    Modifier
                        .offset { IntOffset((headerPx + geometry.x(s) - sx).roundToInt(), 0) }
                        .width(with(density) { geometry.width(s).toDp() })
                        .fillMaxHeight(),
                )
            }

            for (span in spans) {
                if (span.depth > 0 || span.last < slots.first || span.first > slots.last) continue
                val label = ui.groups[span.groupId]?.label.orEmpty()
                if (label.isBlank()) continue
                val color = ui.groups[span.groupId]?.color?.let { Color(it) } ?: colors.tertiary
                val maxW = with(density) { (geometry.x(span.last) + geometry.width(span.last) - geometry.x(span.first)).toDp() - 24.dp }
                // A chip sitting on the group's bar.
                Text(
                    label,
                    style = MaterialTheme.typography.labelSmall,
                    color = readableOn(color),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .layout { m, c ->
                            val p = m.measure(c)
                            layout(p.width, p.height) {
                                val x = headerPx + geometry.x(span.first) - sx + 12.dp.toPx()
                                val barY = geometry.y(span.row + 1) - sy - stripPx * barOffsetFraction(span.depth)
                                p.place(x.roundToInt(), (barY - p.height / 2f).roundToInt())
                            }
                        }
                        .widthIn(max = maxW.coerceAtLeast(0.dp))
                        .background(color, RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp),
                )
            }

            val cardW = with(density) { geometry.slotWidth.toDp() }
            val cardH = with(density) { geometry.rowHeight.toDp() }
            for ((nodeId, cell) in shown.placements) {
                if (cell.slot !in (slots.first - 1)..(slots.last + 1)) continue
                val node = ui.nodes[nodeId] ?: continue
                val row = rowOf[cell.timelineId] ?: continue
                key(nodeId) {
                    val slotPos by animateFloatAsState(cell.slot.toFloat(), spring(stiffness = Spring.StiffnessMediumLow), label = "slot")
                    val rowPos by animateFloatAsState(row.toFloat(), spring(stiffness = Spring.StiffnessMediumLow), label = "row")
                    val ghost = nodeId in dragged
                    NodeCard(
                        node,
                        Modifier
                            .offset {
                                // The ghost marks the landing cell exactly; everything else glides.
                                val x = geometry.xAt(if (ghost) cell.slot.toFloat() else slotPos)
                                val y = (if (ghost) row.toFloat() else rowPos) * geometry.rowHeight
                                IntOffset((headerPx + x - sx).roundToInt(), (y - sy).roundToInt())
                            }
                            .size(cardW, cardH)
                            .padding(start = BoardDims.cardPadding, end = BoardDims.cardPadding, top = BoardDims.cardPadding, bottom = BoardDims.groupStrip * viewport.zoom),
                        ghost = ghost,
                        selected = nodeId in selected,
                    )
                }
            }

            // Pinned timeline header column.
            Box(Modifier.width(BoardDims.header).fillMaxHeight().background(colors.surfaceContainer)) {
                for ((r, t) in ui.timelines.withIndex()) {
                    Box(
                        Modifier
                            .offset { IntOffset(0, (geometry.y(r) - sy).roundToInt()) }
                            .size(BoardDims.header, cardH)
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(t.name, style = MaterialTheme.typography.labelLarge, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    }
                }
            }

            // The dragged item follows the finger.
            session?.let { s ->
                val topLeft = s.pointer - s.grab
                when (val item = s.item) {
                    is DragItem.Node -> ui.nodes[item.id]?.let { node ->
                        NodeCard(
                            node,
                            Modifier
                                .offset { IntOffset(topLeft.x.roundToInt(), topLeft.y.roundToInt()) }
                                .size(cardW, cardH)
                                .padding(start = BoardDims.cardPadding, end = BoardDims.cardPadding, top = BoardDims.cardPadding, bottom = BoardDims.groupStrip * viewport.zoom),
                            lifted = true,
                        )
                    }
                    is DragItem.Marker -> Box(
                        Modifier
                            .offset { IntOffset(topLeft.x.roundToInt(), 0) }
                            .width(with(density) { geometry.markerWidth.toDp() })
                            .fillMaxHeight()
                            .background(colors.primary.copy(alpha = 0.85f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            ui.markers[item.id]?.label.orEmpty(),
                            color = colors.onPrimary,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.vertical(),
                        )
                    }
                    is DragItem.Group -> {
                        // Members keep their offsets from the group's first slot.
                        val members = ui.board.nodesOf(item.id)
                        val first = members.firstOrNull()?.let { ui.board.placements.getValue(it).slot } ?: 0
                        for (m in members) {
                            val node = ui.nodes[m] ?: continue
                            val dx = baseGeometry.x(ui.board.placements.getValue(m).slot) - baseGeometry.x(first)
                            NodeCard(
                                node,
                                Modifier
                                    .offset { IntOffset((topLeft.x + dx).roundToInt(), topLeft.y.roundToInt()) }
                                    .size(cardW, cardH)
                                    .padding(start = BoardDims.cardPadding, end = BoardDims.cardPadding, top = BoardDims.cardPadding, bottom = BoardDims.groupStrip * viewport.zoom),
                                lifted = true,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Top-left of [item] in canvas pixels, used as the grab reference when a drag starts. */
private fun itemOrigin(board: BoardState, g: BoardGeometry, item: DragItem, headerPx: Float, vp: BoardViewport): Offset {
    val (slot, row) = when (item) {
        is DragItem.Node -> board.placements.getValue(item.id).let { it.slot to board.timelines.indexOf(it.timelineId) }
        is DragItem.Group -> board.placements.getValue(board.nodesOf(item.id).first()).let {
            it.slot to board.timelines.indexOf(it.timelineId)
        }
        is DragItem.Marker -> board.markers.getValue(item.id) to 0
    }
    return Offset(headerPx + g.x(slot) - vp.scrollX, if (item is DragItem.Marker) 0f else g.y(row) - vp.scrollY)
}

private fun hitTest(
    ui: BoardUi,
    g: BoardGeometry,
    spans: List<GroupSpan>,
    stripPx: Float,
    p: Offset,
    headerPx: Float,
    vp: BoardViewport,
): BoardHit? {
    val cy = p.y + vp.scrollY
    val row = g.rowAt(cy)
    if (p.x < headerPx) return row?.let { BoardHit.Timeline(ui.board.timelines[it]) }
    val slot = g.slotAt(p.x - headerPx + vp.scrollX)
    ui.board.markerAt(slot)?.let { return BoardHit.Marker(it) }
    if (row == null) return null
    val fromBottom = g.y(row + 1) - cy
    if (fromBottom < stripPx) {
        // The nearest bar among the groups spanning this slot.
        spans.filter { it.row == row && slot in it.first..it.last }
            .minByOrNull { kotlin.math.abs(stripPx * barOffsetFraction(it.depth) - fromBottom) }
            ?.let { return BoardHit.Group(it.groupId) }
    }
    val t = ui.board.timelines[row]
    return ui.board.nodeAt(t, slot)?.let { BoardHit.Node(it) } ?: BoardHit.Empty(t, slot)
}

@Composable
fun NodeCard(
    node: NodeEntity,
    modifier: Modifier = Modifier,
    lifted: Boolean = false,
    ghost: Boolean = false,
    selected: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    if (ghost) {
        Box(modifier.border(BorderStroke(2.dp, colors.primary.copy(alpha = 0.6f)), RoundedCornerShape(10.dp)))
        return
    }
    val bg = node.color?.let { Color(it) } ?: colors.secondaryContainer
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(10.dp),
        color = bg,
        contentColor = if (node.color != null) readableOn(bg) else colors.onSecondaryContainer,
        shadowElevation = if (lifted) 8.dp else 1.dp,
        border = if (selected) BorderStroke(3.dp, colors.primary) else null,
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
