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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.round
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.ui.unit.dp
import com.storyarc.app.data.db.NodeEntity
import com.storyarc.app.data.db.TagEntity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.alpha
import com.storyarc.board.BoardState
import com.storyarc.board.Drop
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * Scroll position (content pixels, in board coordinates; see [BoardAxes]) and zoom of a board,
 * saved across configuration changes. Board coordinates keep the position when the layout
 * switches between horizontal and vertical.
 */
@Stable
class BoardViewport(scrollAlong: Float = 0f, scrollAcross: Float = 0f, zoom: Float = 1f) {
    var scrollAlong by mutableFloatStateOf(scrollAlong)
    var scrollAcross by mutableFloatStateOf(scrollAcross)
    var zoom by mutableFloatStateOf(zoom)

    /** First slot drawn in the last layout; not state, read only by event handlers. */
    var firstVisibleSlot = 0
        internal set

    companion object {
        const val MIN_ZOOM = 0.5f
        const val MAX_ZOOM = 2f
        val Saver = Saver<BoardViewport, List<Float>>(
            save = { listOf(it.scrollAlong, it.scrollAcross, it.zoom) },
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

/**
 * Base sizes at zoom 1. Lengths along the time axis and thicknesses across it depend on the
 * layout so that node cards are 132×88 either way.
 */
object BoardDims {
    /** Thickness of the pinned timeline header: a column when horizontal, a row when vertical. */
    fun header(vertical: Boolean) = if (vertical) 48.dp else 104.dp
    fun slot(vertical: Boolean) = if (vertical) 88.dp else 132.dp
    fun row(vertical: Boolean) = if (vertical) 132.dp else 88.dp
    val marker = 40.dp
    val cardPadding = 4.dp

    /** Band along the trailing edge of each timeline where group bars are drawn and grabbed. */
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
    vertical: Boolean = false,
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val axes = BoardAxes(vertical)
    val headerDp = BoardDims.header(vertical)
    val headerPx = with(density) { headerDp.toPx() }
    val stripPx = with(density) { BoardDims.groupStrip.toPx() } * viewport.zoom
    val edgePx = with(density) { BoardDims.autoScrollEdge.toPx() }
    val colors = MaterialTheme.colorScheme

    fun geometryFor(board: BoardState) = with(density) {
        BoardGeometry(
            board,
            slotWidth = BoardDims.slot(vertical).toPx() * viewport.zoom,
            markerWidth = BoardDims.marker.toPx() * viewport.zoom,
            rowHeight = BoardDims.row(vertical).toPx() * viewport.zoom,
        )
    }

    var session by remember { mutableStateOf<DragSession?>(null) }
    // After a drop, keep showing the previewed layout until the saved board arrives.
    var pending by remember { mutableStateOf<Pair<BoardState, BoardState>?>(null) }
    LaunchedEffect(ui.board) { if (pending?.first !== ui.board) pending = null }

    val shown: BoardState = session?.preview ?: pending?.takeIf { it.first === ui.board }?.second ?: ui.board
    // Targets are always resolved against the board as it was before the drag.
    val baseGeometry = remember(ui.board, viewport.zoom, density, vertical) { geometryFor(ui.board) }
    val geometry = remember(shown, viewport.zoom, density, vertical) { geometryFor(shown) }
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
        // The canvas size in board coordinates; the content area excludes the header.
        val view = axes.board(Offset(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat()))
        val viewAlong = view.x - headerPx
        val viewAcross = view.y
        fun clamp() {
            viewport.scrollAlong = viewport.scrollAlong.coerceIn(0f, (geometry.contentWidth - viewAlong).coerceAtLeast(0f))
            viewport.scrollAcross = viewport.scrollAcross.coerceIn(0f, (geometry.contentHeight - viewAcross).coerceAtLeast(0f))
        }
        clamp()

        /** Screen position of a point given relative to the content area's scrolled origin. */
        fun at(along: Float, across: Float) = axes.screen(headerPx + along, across)

        /** A screen point relative to the content area's origin, in board coordinates. */
        fun local(p: Offset) = axes.board(p).let { Offset(it.x - headerPx, it.y) }

        fun resolveAt(item: DragItem, pointer: Offset, unlocked: Set<String>): Resolution {
            val all = if (currentBarrier.delayEnabled) unlocked else currentUi.board.markers.keys
            val q = local(pointer)
            return resolveTarget(currentUi.board, currentBase, item, q.x + viewport.scrollAlong, q.y + viewport.scrollAcross, all)
        }

        fun update(s: DragSession) {
            val r = resolveAt(s.item, s.pointer, s.unlocked)
            if (r != s.resolution) {
                s.resolution = r
                s.preview = preview(currentUi.board, s.item, r.target, collapse = false)
            }
        }

        // Edge auto-scroll direction for a pointer, in board coordinates.
        fun depth(d: Float) = (d / edgePx).coerceIn(0f, 1f)
        fun edgePull(p: Offset): Offset {
            val q = local(p)
            return Offset(
                depth(q.x - (viewAlong - edgePx)) - depth(edgePx - q.x),
                depth(q.y - (viewAcross - edgePx)) - depth(edgePx - q.y),
            )
        }
        // Zero when the board can't move that way.
        val scrollDir: Offset = session?.let { st ->
            val d = edgePull(st.pointer)
            val maxAlong = (geometry.contentWidth - viewAlong).coerceAtLeast(0f)
            val maxAcross = (geometry.contentHeight - viewAcross).coerceAtLeast(0f)
            Offset(
                if ((d.x < 0 && viewport.scrollAlong > 0f) || (d.x > 0 && viewport.scrollAlong < maxAlong)) d.x else 0f,
                if ((d.y < 0 && viewport.scrollAcross > 0f) || (d.y > 0 && viewport.scrollAcross < maxAcross)) d.y else 0f,
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
                val d = edgePull(s.pointer)
                viewport.scrollAlong += step * d.x
                viewport.scrollAcross += step * d.y
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

        val sx = viewport.scrollAlong
        val sy = viewport.scrollAcross
        val slots = geometry.visibleSlots(sx, sx + viewAlong)
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
                .pointerInput(vertical) {
                    detectTransformGestures { centroid, pan, zoomChange, _ ->
                        val old = viewport.zoom
                        val new = (old * zoomChange).coerceIn(BoardViewport.MIN_ZOOM, BoardViewport.MAX_ZOOM)
                        val f = new / old
                        // Keep the content point under the centroid fixed while zooming.
                        val c = local(centroid)
                        val move = axes.board(pan)
                        viewport.scrollAlong = (viewport.scrollAlong + c.x) * f - c.x - move.x
                        viewport.scrollAcross = (viewport.scrollAcross + c.y) * f - c.y - move.y
                        viewport.zoom = new
                    }
                }
                .pointerInput(vertical) {
                    detectTapGestures { p -> hitTest(currentUi, currentBase, currentSpans, currentStrip, axes.board(p), headerPx, viewport)?.let(currentOnTap) }
                }
                .pointerInput(vertical) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val ui0 = currentUi
                        val g0 = currentBase
                        val item = when (val hit = hitTest(ui0, g0, currentSpans, currentStrip, axes.board(down.position), headerPx, viewport)) {
                            is BoardHit.Node -> DragItem.Node(hit.nodeId)
                            is BoardHit.Marker -> DragItem.Marker(hit.markerId)
                            is BoardHit.Group -> DragItem.Group(hit.groupId)
                            else -> null
                        } ?: return@awaitEachGesture
                        val longPress = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        val origin = itemOrigin(ui0.board, g0, item, viewport).let { at(it.x, it.y) }
                        val r = resolveAt(item, longPress.position, emptySet())
                        val s = DragSession(item, longPress.position, longPress.position - origin, r, preview(ui0.board, item, r.target, collapse = false))
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
                        val result = preview(ui0.board, item, s.resolution.target)
                        if (completed && result != ui0.board) {
                            pending = ui0.board to result
                            currentOnDrop(item, s.resolution.target)
                        }
                        session = null
                    }
                },
        ) {
            // Grid: timeline stripes, slot dividers, marker bands.
            Canvas(Modifier.fillMaxSize()) {
                for (r in shown.timelines.indices) {
                    val y = geometry.y(r) - sy
                    if (r % 2 == 1) drawRect(colors.surfaceContainerLow, at(-headerPx, y), axes.size(view.x, geometry.rowHeight))
                }
                for (s in slots) {
                    val x = geometry.x(s) - sx
                    val markerId = shown.markerAt(s)
                    if (markerId != null) {
                        val fill = when (markerId) {
                            blockedBy -> colors.tertiaryContainer
                            draggedMarker -> colors.surfaceVariant
                            else -> ui.markerColor(markerId)?.let { Color(it) } ?: colors.primaryContainer
                        }
                        val alpha = if (ui.isHidden(ui.markerTags[markerId])) 0.25f else 1f
                        drawRect(fill.copy(alpha = fill.alpha * alpha), at(x, 0f), axes.size(geometry.width(s), view.y))
                    } else {
                        drawLine(colors.outlineVariant, at(x, 0f), at(x, geometry.contentHeight - sy), 1f)
                    }
                }
                val barH = 3.dp.toPx() * viewport.zoom
                for (span in spans) {
                    if (span.last < slots.first || span.first > slots.last) continue
                    val color = ui.groupColor(span.groupId)?.let { Color(it) } ?: colors.tertiary
                    val faded = ui.isHidden(ui.groupTags[span.groupId])
                    val start = geometry.x(span.first) - sx + BoardDims.cardPadding.toPx()
                    val end = geometry.x(span.last) + geometry.width(span.last) - sx - BoardDims.cardPadding.toPx()
                    val y = geometry.y(span.row + 1) - sy - stripPx * barOffsetFraction(span.depth)
                    drawRoundRect(
                        color.copy(alpha = if (faded || span.groupId == (session?.item as? DragItem.Group)?.id) 0.3f else 1f),
                        at(start, y - barH / 2), axes.size(end - start, barH),
                        androidx.compose.ui.geometry.CornerRadius(barH / 2),
                    )
                }
            }

            for (s in slots) {
                val markerId = shown.markerAt(s) ?: continue
                if (markerId == draggedMarker) continue
                MarkerLabel(
                    ui.markers[markerId]?.label.orEmpty(),
                    ui.markerColor(markerId)?.let { readableOn(Color(it)) } ?: colors.onPrimaryContainer,
                    rotated = !vertical,
                    Modifier
                        .offset { at(geometry.x(s) - sx, 0f).round() }
                        .band(vertical, with(density) { geometry.width(s).toDp() })
                        .markerSemantics(ui, markerId, { currentOnTap(it) }, { i, t -> currentOnDrop(i, t) }),
                )
            }

            for (span in spans) {
                if (span.depth > 0 || span.last < slots.first || span.first > slots.last) continue
                val label = ui.groups[span.groupId]?.label.orEmpty()
                if (label.isBlank()) continue
                val color = ui.groupColor(span.groupId)?.let { Color(it) } ?: colors.tertiary
                val maxLength = with(density) { (geometry.x(span.last) + geometry.width(span.last) - geometry.x(span.first)).toDp() - 24.dp }
                    .coerceAtLeast(0.dp)
                // A chip sitting on the group's bar, reading along it.
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
                                val barY = geometry.y(span.row + 1) - sy - stripPx * barOffsetFraction(span.depth)
                                val thickness = if (vertical) p.width else p.height
                                p.place((at(geometry.x(span.first) - sx + 12.dp.toPx(), barY - thickness / 2f)).round())
                            }
                        }
                        .then(if (vertical) Modifier.heightIn(max = maxLength).vertical() else Modifier.widthIn(max = maxLength))
                        .background(color, RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp),
                )
            }

            val cardSize = with(density) { axes.size(geometry.slotWidth, geometry.rowHeight).toDpSize() }
            val cardPadding = PaddingValues(
                start = BoardDims.cardPadding,
                top = BoardDims.cardPadding,
                end = if (vertical) BoardDims.groupStrip * viewport.zoom else BoardDims.cardPadding,
                bottom = if (vertical) BoardDims.cardPadding else BoardDims.groupStrip * viewport.zoom,
            )
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
                        color = ui.nodeColor(nodeId),
                        tags = ui.nodeTags[nodeId].orEmpty().mapNotNull { ui.tags[it] },
                        faded = ui.isHidden(ui.nodeTags[nodeId]),
                        modifier = Modifier
                            .offset {
                                // The ghost marks the landing cell exactly; everything else glides.
                                val x = geometry.xAt(if (ghost) cell.slot.toFloat() else slotPos)
                                val y = (if (ghost) row.toFloat() else rowPos) * geometry.rowHeight
                                at(x - sx, y - sy).round()
                            }
                            .size(cardSize)
                            .padding(cardPadding)
                            .nodeSemantics(ui, nodeId, { currentOnTap(it) }, { i, t -> currentOnDrop(i, t) }),
                        ghost = ghost,
                        selected = nodeId in selected,
                    )
                }
            }

            // Pinned timeline header: a column on the left, or a row along the top when vertical.
            val headerCell = with(density) { axes.size(headerPx, geometry.rowHeight).toDpSize() }
            val colorEdge = with(density) { axes.size(4.dp.toPx(), geometry.rowHeight).toDpSize() }
            Box(Modifier.band(vertical, headerDp).background(colors.surfaceContainer)) {
                for ((r, t) in ui.timelines.withIndex()) {
                    Box(
                        Modifier
                            .offset { axes.screen(0f, geometry.y(r) - sy).round() }
                            .size(headerCell)
                            .padding(horizontal = 8.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(t.name, style = MaterialTheme.typography.labelLarge, maxLines = if (vertical) 2 else 3, overflow = TextOverflow.Ellipsis)
                    }
                    t.color?.let { c ->
                        Box(
                            Modifier
                                .offset { axes.screen(0f, geometry.y(r) - sy).round() }
                                .size(colorEdge)
                                .background(Color(c)),
                        )
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
                            color = ui.nodeColor(node.id),
                            modifier = Modifier
                                .offset { topLeft.round() }
                                .size(cardSize)
                                .padding(cardPadding),
                            lifted = true,
                        )
                    }
                    is DragItem.Marker -> Box(
                        Modifier
                            .offset { axes.screen(axes.board(topLeft).x, 0f).round() }
                            .band(vertical, with(density) { geometry.markerWidth.toDp() })
                            .background(colors.primary.copy(alpha = 0.85f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(
                            ui.markers[item.id]?.label.orEmpty(),
                            color = colors.onPrimary,
                            style = MaterialTheme.typography.labelLarge,
                            maxLines = 1,
                            softWrap = false,
                            modifier = if (vertical) Modifier else Modifier.vertical(),
                        )
                    }
                    is DragItem.Group -> {
                        // Members keep their offsets from the group's first slot.
                        val members = ui.board.nodesOf(item.id)
                        val first = members.firstOrNull()?.let { ui.board.placements.getValue(it).slot } ?: 0
                        for (m in members) {
                            val node = ui.nodes[m] ?: continue
                            val shift = axes.screen(baseGeometry.x(ui.board.placements.getValue(m).slot) - baseGeometry.x(first), 0f)
                            NodeCard(
                                node,
                                color = ui.nodeColor(m),
                                modifier = Modifier
                                    .offset { (topLeft + shift).round() }
                                    .size(cardSize)
                                    .padding(cardPadding),
                                lifted = true,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A band spanning the whole board across the time axis, [length] long along it. */
private fun Modifier.band(vertical: Boolean, length: Dp) =
    if (vertical) height(length).fillMaxWidth() else width(length).fillMaxHeight()

/**
 * Accessibility for a node card: one merged element that opens on activation and offers
 * moves without dragging. Moves are explicit, so they may cross markers.
 */
private fun Modifier.nodeSemantics(
    ui: BoardUi,
    nodeId: String,
    onTap: (BoardHit) -> Unit,
    onDrop: (DragItem, DragTarget) -> Unit,
): Modifier = semantics(mergeDescendants = true) {
    val cell = ui.board.placements[nodeId] ?: return@semantics
    val timelines = ui.board.timelines
    val row = timelines.indexOf(cell.timelineId)
    val name = ui.timelines.find { it.id == cell.timelineId }?.name.orEmpty()
    stateDescription = "$name, position ${cell.slot + 1}"
    onClick("Edit") { onTap(BoardHit.Node(nodeId)); true }
    fun move(label: String, drop: Drop) = CustomAccessibilityAction(label) { onDrop(DragItem.Node(nodeId), DragTarget.Place(drop)); true }
    customActions = buildList {
        if (cell.slot > 0) add(move("Move earlier", Drop.Between(cell.timelineId, cell.slot - 1)))
        add(move("Move later", Drop.Between(cell.timelineId, cell.slot + 2)))
        if (row > 0) add(move("Move to ${ui.timelines.getOrNull(row - 1)?.name ?: "previous timeline"}", Drop.Onto(timelines[row - 1], cell.slot)))
        if (row < timelines.lastIndex) add(move("Move to ${ui.timelines.getOrNull(row + 1)?.name ?: "next timeline"}", Drop.Onto(timelines[row + 1], cell.slot)))
    }
}

private fun Modifier.markerSemantics(
    ui: BoardUi,
    markerId: String,
    onTap: (BoardHit) -> Unit,
    onDrop: (DragItem, DragTarget) -> Unit,
): Modifier = semantics(mergeDescendants = true) {
    val slot = ui.board.markers[markerId] ?: return@semantics
    stateDescription = "Marker, position ${slot + 1}"
    onClick("Edit") { onTap(BoardHit.Marker(markerId)); true }
    customActions = buildList {
        if (slot > 0) add(CustomAccessibilityAction("Move earlier") { onDrop(DragItem.Marker(markerId), DragTarget.MarkerAt(slot - 1)); true })
        add(CustomAccessibilityAction("Move later") { onDrop(DragItem.Marker(markerId), DragTarget.MarkerAt(slot + 2)); true })
    }
}

/** Top-left of [item] relative to the content area's scrolled origin, in board coordinates. */
private fun itemOrigin(board: BoardState, g: BoardGeometry, item: DragItem, vp: BoardViewport): Offset {
    val (slot, row) = when (item) {
        is DragItem.Node -> board.placements.getValue(item.id).let { it.slot to board.timelines.indexOf(it.timelineId) }
        is DragItem.Group -> board.placements.getValue(board.nodesOf(item.id).first()).let {
            it.slot to board.timelines.indexOf(it.timelineId)
        }
        is DragItem.Marker -> board.markers.getValue(item.id) to 0
    }
    return Offset(g.x(slot) - vp.scrollAlong, if (item is DragItem.Marker) 0f else g.y(row) - vp.scrollAcross)
}

/** What lies under canvas point [p], given in board coordinates (see [BoardAxes.board]). */
private fun hitTest(
    ui: BoardUi,
    g: BoardGeometry,
    spans: List<GroupSpan>,
    stripPx: Float,
    p: Offset,
    headerPx: Float,
    vp: BoardViewport,
): BoardHit? {
    val cy = p.y + vp.scrollAcross
    val row = g.rowAt(cy)
    if (p.x < headerPx) return row?.let { BoardHit.Timeline(ui.board.timelines[it]) }
    val slot = g.slotAt(p.x - headerPx + vp.scrollAlong)
    ui.board.markerAt(slot)?.let { return BoardHit.Marker(it) }
    if (row == null) return null
    val fromEdge = g.y(row + 1) - cy
    if (fromEdge < stripPx) {
        // The nearest bar among the groups spanning this slot.
        spans.filter { it.row == row && slot in it.first..it.last }
            .minByOrNull { kotlin.math.abs(stripPx * barOffsetFraction(it.depth) - fromEdge) }
            ?.let { return BoardHit.Group(it.groupId) }
    }
    val t = ui.board.timelines[row]
    return ui.board.nodeAt(t, slot)?.let { BoardHit.Node(it) } ?: BoardHit.Empty(t, slot)
}

@Composable
fun NodeCard(
    node: NodeEntity,
    modifier: Modifier = Modifier,
    color: Int? = node.color,
    tags: List<TagEntity> = emptyList(),
    lifted: Boolean = false,
    ghost: Boolean = false,
    selected: Boolean = false,
    faded: Boolean = false,
) {
    val colors = MaterialTheme.colorScheme
    if (ghost) {
        Box(modifier.border(BorderStroke(2.dp, colors.primary.copy(alpha = 0.6f)), RoundedCornerShape(10.dp)))
        return
    }
    val bg = color?.let { Color(it) } ?: colors.secondaryContainer
    Surface(
        modifier = if (faded) modifier.alpha(0.2f) else modifier,
        shape = RoundedCornerShape(10.dp),
        color = bg,
        contentColor = if (color != null) readableOn(bg) else colors.onSecondaryContainer,
        shadowElevation = if (lifted) 8.dp else 1.dp,
        border = if (selected) BorderStroke(3.dp, colors.primary) else null,
    ) {
        Box(Modifier.padding(8.dp)) {
            if (node.text.isBlank()) {
                Text("Untitled", style = MaterialTheme.typography.bodySmall, color = colors.outline)
            } else {
                Text(node.text, style = MaterialTheme.typography.bodySmall, overflow = TextOverflow.Ellipsis)
            }
            if (tags.isNotEmpty()) {
                // Tag dots along the bottom edge; outlined so a dot matching the card stays visible.
                Row(Modifier.align(Alignment.BottomEnd), horizontalArrangement = Arrangement.spacedBy(3.dp)) {
                    tags.take(5).forEach { t ->
                        Box(
                            Modifier
                                .size(8.dp)
                                .background(t.color?.let { Color(it) } ?: colors.outline, CircleShape)
                                .border(1.dp, readableOn(bg).copy(alpha = 0.4f), CircleShape),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MarkerLabel(label: String, color: Color, rotated: Boolean, modifier: Modifier) {
    Box(modifier, contentAlignment = Alignment.Center) {
        Text(
            label,
            style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.SemiBold),
            color = color,
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Ellipsis,
            modifier = if (rotated) Modifier.vertical() else Modifier.padding(horizontal = 8.dp),
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
