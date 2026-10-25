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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Icon
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
import com.storyarc.board.Block
import com.storyarc.board.BoardState
import androidx.compose.foundation.gestures.awaitTouchSlopOrCancellation
import androidx.compose.ui.geometry.Rect
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

/** What a tap on the board hit. Tray items report as [Node] or [Group] too. */
sealed interface BoardHit {
    data class Node(val nodeId: String) : BoardHit
    data class Marker(val markerId: String) : BoardHit
    data class Empty(val timelineId: String, val slot: Int) : BoardHit
    data class Timeline(val timelineId: String) : BoardHit
    data class Group(val groupId: String) : BoardHit

    /** The new node floating over the board. */
    data class Floating(val nodeId: String) : BoardHit
    data object TrayToggle : BoardHit
    data object TrayOptions : BoardHit

    /** The plus button after the last timeline. */
    data object AddTimeline : BoardHit
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

    /** Thickness of the add-timeline button after the last timeline (not zoomed). */
    val addTimeline = 48.dp

    /** Band along the trailing edge of each timeline where group bars are drawn and grabbed. */
    val groupStrip = 16.dp
    val autoScrollEdge = 56.dp
}

/** Marker-crossing behaviour while dragging. */
data class BarrierConfig(val delayEnabled: Boolean = true, val delayMs: Long = 500)

/**
 * State of an in-progress drag. Positions are in canvas pixels. [fromTray] is the item's block
 * when it was picked up from the tray (or is the floating node), [trayIndex] its tray position.
 * [members] are the cards drawn under the finger, offset from the dragged item's top-left.
 */
@Stable
private class DragSession(
    val item: DragItem,
    start: Offset,
    val grab: Offset,
    initial: Resolution,
    preview: BoardState,
    val fromTray: Block?,
    val trayIndex: Int?,
    val members: List<Pair<String, Offset>>,
) {
    var pointer by mutableStateOf(start)
    var resolution by mutableStateOf(initial)
    var preview by mutableStateOf(preview)
    val unlocked = HashSet<String>()

    /** Event uptime at which the current barrier started blocking, for event-driven dwell. */
    var blockedSinceMs: Long? = null
}

/** Where a drag may start: the item, its block if it is not on the board, and its top-left. */
private class DragStart(val item: DragItem, val block: Block?, val trayIndex: Int?, val origin: Offset, val immediate: Boolean)

/**
 * The board with its tray. [onDrop] reports a drop with whether the item came from the tray.
 * The tray runs along the bottom, or the right edge when [traySide]. [floating] is a tray
 * node drawn floating over the board instead of in the tray; [trayHidden] nodes are not listed.
 */
@Composable
fun BoardCanvas(
    ui: BoardUi,
    viewport: BoardViewport,
    barrier: BarrierConfig,
    onTap: (BoardHit) -> Unit,
    onDrop: (DragItem, DragTarget, Boolean) -> Unit,
    modifier: Modifier = Modifier,
    selected: Set<String> = emptySet(),
    vertical: Boolean = false,
    traySide: Boolean = false,
    trayExpanded: Boolean = true,
    floating: String? = null,
    trayHidden: Set<String> = emptySet(),
    trayView: TrayView = TrayView(),
) {
    val density = LocalDensity.current
    val haptics = LocalHapticFeedback.current
    val axes = BoardAxes(vertical)
    val headerDp = BoardDims.header(vertical)
    val headerPx = with(density) { headerDp.toPx() }
    val stripPx = with(density) { BoardDims.groupStrip.toPx() } * viewport.zoom
    val edgePx = with(density) { BoardDims.autoScrollEdge.toPx() }
    val addPx = with(density) { BoardDims.addTimeline.toPx() }
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
    var trayScroll by remember { mutableFloatStateOf(0f) }
    // Where the floating node was left; null puts it just above the tray.
    var floatingPos by remember(floating) { mutableStateOf<Offset?>(null) }

    val shown: BoardState = session?.preview ?: pending?.takeIf { it.first === ui.board }?.second ?: ui.board
    // Targets are always resolved against the board as it was before the drag.
    val baseGeometry = remember(ui.board, viewport.zoom, density, vertical) { geometryFor(ui.board) }
    val geometry = remember(shown, viewport.zoom, density, vertical) { geometryFor(shown) }
    val baseSpans = remember(ui.board) { groupSpans(ui.board) }
    val spans = remember(shown) { groupSpans(shown) }
    // Tray items as listed (the floating node is not); indices map back into ui.tray.
    val unhidden = ui.tray.filter { b -> b.root != null || b.nodes.keys.single() !in trayHidden }
    val listed = remember(ui, unhidden, trayView) { ui.arrangeTray(unhidden, trayView) }
    val floatingNode = floating?.takeIf { id -> ui.tray.any { it.root == null && id in it.nodes } }?.let { ui.nodes[it] }

    val currentUi by rememberUpdatedState(ui)
    val currentBase by rememberUpdatedState(baseGeometry)
    val currentSpans by rememberUpdatedState(baseSpans)
    val currentStrip by rememberUpdatedState(stripPx)
    val currentBarrier by rememberUpdatedState(barrier)
    val currentOnDrop by rememberUpdatedState(onDrop)
    val currentOnTap by rememberUpdatedState(onTap)
    val currentListed by rememberUpdatedState(listed)
    val currentTrayView by rememberUpdatedState(trayView)

    BoxWithConstraints(modifier.clipToBounds()) {
        val tray = TrayGeometry(
            traySide, trayExpanded, constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), listed.size, trayScroll, density,
        )
        trayScroll = tray.scroll
        val boardSize = tray.boardSize
        val boardRect = Rect(Offset.Zero, boardSize)
        // The board area's size in board coordinates; the content area excludes the header.
        val view = axes.board(Offset(boardSize.width, boardSize.height))
        val viewAlong = view.x - headerPx
        val viewAcross = view.y
        fun clamp() {
            viewport.scrollAlong = viewport.scrollAlong.coerceIn(0f, (geometry.contentWidth - viewAlong).coerceAtLeast(0f))
            viewport.scrollAcross = viewport.scrollAcross.coerceIn(0f, (geometry.contentHeight - viewAcross).coerceAtLeast(0f))
        }
        clamp()

        val floatingSize = with(density) { Size(BoardDims.slot(false).toPx(), BoardDims.row(false).toPx()) }
        val floatingRect = floatingNode?.let {
            val pos = floatingPos ?: Offset(
                (boardSize.width - floatingSize.width) / 2,
                boardSize.height - floatingSize.height - with(density) { 16.dp.toPx() },
            )
            Rect(pos, floatingSize)
        }
        val currentTray by rememberUpdatedState(tray)
        val currentFloating by rememberUpdatedState(floatingRect to floatingNode?.id)

        /** Screen position of a point given relative to the content area's scrolled origin. */
        fun at(along: Float, across: Float) = axes.screen(headerPx + along, across)

        /** A screen point relative to the content area's origin, in board coordinates. */
        fun local(p: Offset) = axes.board(p).let { Offset(it.x - headerPx, it.y) }

        /** Maps an insertion point among the listed tray items to one in the whole tray. */
        fun trayIndex(listedIndex: Int): Int =
            currentListed.getOrNull(listedIndex)?.let { currentUi.tray.indexOf(it) } ?: currentUi.tray.size

        fun resolveAt(item: DragItem, pointer: Offset, unlocked: Set<String>): Resolution {
            val t = currentTray
            if (t.onTrash(pointer)) return Resolution(DragTarget.Trash)
            if (t.contains(pointer)) {
                // A sorted or filtered tray has no meaningful insertion point: append.
                val index = if (currentTrayView.isManual) trayIndex(t.insertionAt(pointer)) else currentUi.tray.size
                return Resolution(if (item is DragItem.Marker) DragTarget.None else DragTarget.Tray(index))
            }
            if (currentUi.board.timelines.isEmpty()) return Resolution(DragTarget.None)
            val all = if (currentBarrier.delayEnabled) unlocked else currentUi.board.markers.keys
            val q = local(pointer)
            return resolveTarget(currentUi.board, currentBase, item, q.x + viewport.scrollAlong, q.y + viewport.scrollAcross, all)
        }

        fun update(s: DragSession) {
            val r = resolveAt(s.item, s.pointer, s.unlocked)
            if (r != s.resolution) {
                s.resolution = r
                s.preview = preview(currentUi.board, s.item, r.target, collapse = false, fromTray = s.fromTray)
            }
        }

        /** What a tap at canvas point [p] hits. */
        fun tapAt(p: Offset): BoardHit? {
            val (fRect, fId) = currentFloating
            val t = currentTray
            return when {
                fRect != null && fId != null && fRect.contains(p) -> BoardHit.Floating(fId)
                t.optionsButton?.contains(p) == true -> BoardHit.TrayOptions
                t.header.contains(p) -> BoardHit.TrayToggle
                t.contains(p) -> t.itemAt(p)?.let { currentListed[it] }?.let { b -> b.root?.let { BoardHit.Group(it) } ?: BoardHit.Node(b.nodes.keys.single()) }
                boardRect.contains(p) -> hitTest(currentUi, currentBase, currentSpans, currentStrip, axes.board(p), headerPx, viewport, addPx)
                else -> null
            }
        }

        // Edge auto-scroll direction for a pointer over the board area, in board coordinates.
        fun depth(d: Float) = (d / edgePx).coerceIn(0f, 1f)
        fun edgePull(p: Offset): Offset {
            if (!boardRect.contains(p)) return Offset.Zero
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

        /** Cards drawn under the finger for [item], offset from its top-left. */
        fun membersOf(item: DragItem, block: Block?, g: BoardGeometry): List<Pair<String, Offset>> = when {
            item is DragItem.Marker -> emptyList()
            block != null -> block.nodes.entries.sortedBy { it.value }.map { (n, off) -> n to axes.screen(off * g.slotWidth, 0f) }
            item is DragItem.Group -> {
                val nodes = currentUi.board.nodesOf(item.id)
                val first = currentUi.board.placements.getValue(nodes.first()).slot
                nodes.map { n -> n to axes.screen(g.x(currentUi.board.placements.getValue(n).slot) - g.x(first), 0f) }
            }
            item is DragItem.Node -> listOf(item.id to Offset.Zero)
            else -> emptyList()
        }

        /** The draggable thing under [p], if any. */
        fun dragStartAt(p: Offset): DragStart? {
            val (fRect, fId) = currentFloating
            val t = currentTray
            if (fRect != null && fId != null && fRect.contains(p)) {
                return DragStart(DragItem.Node(fId), Block(mapOf(fId to 0)), currentUi.tray.indexOfFirst { fId in it.nodes }, fRect.topLeft, immediate = true)
            }
            if (t.contains(p)) {
                val i = t.itemAt(p) ?: return null
                val b = currentListed[i]
                val item = b.root?.let { DragItem.Group(it) } ?: DragItem.Node(b.nodes.keys.single())
                return DragStart(item, b, currentUi.tray.indexOf(b), t.itemRect(i).topLeft, immediate = false)
            }
            if (!boardRect.contains(p)) return null
            val ui0 = currentUi
            val item = when (val hit = hitTest(ui0, currentBase, currentSpans, currentStrip, axes.board(p), headerPx, viewport, addPx)) {
                is BoardHit.Node -> DragItem.Node(hit.nodeId)
                is BoardHit.Marker -> DragItem.Marker(hit.markerId)
                is BoardHit.Group -> DragItem.Group(hit.groupId)
                is BoardHit.Timeline -> DragItem.Timeline(hit.timelineId)
                else -> null
            } ?: return null
            if (item is DragItem.Timeline) {
                // The header cell is the grab reference.
                val row = ui0.board.timelines.indexOf(item.id)
                return DragStart(item, null, null, axes.screen(0f, currentBase.y(row) - viewport.scrollAcross), immediate = false)
            }
            val o = itemOrigin(ui0.board, currentBase, item, viewport)
            return DragStart(item, null, null, at(o.x, o.y), immediate = false)
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
                .pointerInput(vertical, traySide) {
                    detectTransformGestures { centroid, pan, zoomChange, _ ->
                        if (currentTray.contains(centroid)) {
                            // Scroll the tray along its edge.
                            trayScroll = (trayScroll - if (traySide) pan.y else pan.x).coerceIn(0f, currentTray.maxScroll)
                            return@detectTransformGestures
                        }
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
                .pointerInput(vertical, traySide) {
                    detectTapGestures { p -> tapAt(p)?.let(currentOnTap) }
                }
                .pointerInput(vertical, traySide) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val start = dragStartAt(down.position) ?: return@awaitEachGesture
                        val ui0 = currentUi
                        // The floating node moves on the first drag; everything else needs a long press.
                        val pressed = if (start.immediate) {
                            awaitTouchSlopOrCancellation(down.id) { change, _ -> change.consume() }
                        } else {
                            awaitLongPressOrCancellation(down.id)?.also { haptics.performHapticFeedback(HapticFeedbackType.LongPress) }
                        } ?: return@awaitEachGesture
                        val item = start.item
                        val r = resolveAt(item, pressed.position, emptySet())
                        val s = DragSession(
                            item, pressed.position, down.position - start.origin, r,
                            preview(ui0.board, item, r.target, collapse = false, fromTray = start.block),
                            start.block, start.trayIndex, membersOf(item, start.block, currentBase),
                        )
                        session = s
                        pressed.consume()
                        val completed = drag(pressed.id) { change ->
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
                        val target = s.resolution.target
                        val isFloating = start.immediate
                        val result = preview(ui0.board, item, target, fromTray = start.block)
                        val changed = when (target) {
                            DragTarget.None -> false
                            DragTarget.Trash -> true
                            // Dropping a tray item back where it was changes nothing (the floating node docks).
                            // Reordering happens only while the tray shows its stored order.
                            is DragTarget.Tray -> isFloating || s.trayIndex == null ||
                                (currentTrayView.isManual && target.index != s.trayIndex && target.index != s.trayIndex + 1)
                            else -> start.block != null || result != ui0.board
                        }
                        if (completed && changed) {
                            pending = ui0.board to result
                            currentOnDrop(item, target, start.block != null)
                        } else if (completed && isFloating) {
                            floatingPos = s.pointer - s.grab
                        }
                        session = null
                    }
                },
        ) {
            Box(Modifier.size(with(density) { boardSize.toDpSize() }).clipToBounds()) {
                BoardContent(
                    ui, shown, geometry, spans, slots, rowOf, dragged, draggedMarker, blockedBy, selected,
                    session?.item as? DragItem.Group, (session?.item as? DragItem.Timeline)?.id,
                    sx, sy, view, headerDp, headerPx, stripPx, vertical, axes, viewport.zoom,
                    onTap = { currentOnTap(it) },
                    onDrop = { i, t -> currentOnDrop(i, t, false) },
                )
            }

            val targeted = session?.resolution?.target
            TrayStrip(
                ui, listed, unhidden.size, trayView.isFiltered, tray,
                highlighted = targeted is DragTarget.Tray,
                draggingIndex = session?.fromTray?.let { b -> listed.indexOf(b).takeIf { it >= 0 } },
                onTap = { currentOnTap(it) },
                onDrop = { i, t -> currentOnDrop(i, t, true) },
            )

            if (floatingNode != null && floatingRect != null && (session?.item as? DragItem.Node)?.id != floatingNode.id) {
                NodeCard(
                    floatingNode,
                    color = ui.nodeColor(floatingNode.id),
                    tags = ui.nodeTags[floatingNode.id].orEmpty().mapNotNull { ui.tags[it] },
                    modifier = Modifier
                        .offset { floatingRect.topLeft.round() }
                        .size(with(density) { floatingRect.size.toDpSize() })
                        .padding(BoardDims.cardPadding)
                        .semantics(mergeDescendants = true) {
                            stateDescription = "New node, not placed"
                            onClick("Edit") { currentOnTap(BoardHit.Floating(floatingNode.id)); true }
                            customActions = listOf(
                                CustomAccessibilityAction("Put in tray") {
                                    currentOnDrop(DragItem.Node(floatingNode.id), DragTarget.Tray(currentUi.tray.size), true); true
                                },
                            )
                        },
                    lifted = true,
                )
            }

            session?.let { s ->
                Trash(tray.trashRect, targeted == DragTarget.Trash)
                DragOverlay(ui, s, geometry, axes, vertical, viewport.zoom)
            }
        }
    }
}

/** The dragged item following the finger. */
@Composable
private fun DragOverlay(ui: BoardUi, s: DragSession, geometry: BoardGeometry, axes: BoardAxes, vertical: Boolean, zoom: Float) {
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val topLeft = s.pointer - s.grab
    val item = s.item
    if (item is DragItem.Marker) {
        Box(
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
        return
    }
    if (item is DragItem.Timeline) {
        // The header cell, following the finger across the timelines.
        val headerPx = with(density) { BoardDims.header(vertical).toPx() }
        Surface(
            Modifier
                .offset { axes.screen(0f, axes.board(topLeft).y).round() }
                .size(with(density) { axes.size(headerPx, geometry.rowHeight).toDpSize() }),
            color = colors.primaryContainer,
            shadowElevation = 8.dp,
            shape = RoundedCornerShape(8.dp),
        ) {
            Box(Modifier.padding(horizontal = 8.dp), contentAlignment = Alignment.CenterStart) {
                Text(ui.timelines.find { it.id == item.id }?.name.orEmpty(), style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        return
    }
    val cardSize = with(density) { axes.size(geometry.slotWidth, geometry.rowHeight).toDpSize() }
    val padding = cardPadding(vertical, zoom)
    for ((m, shift) in s.members) {
        val node = ui.nodes[m] ?: continue
        NodeCard(
            node,
            color = ui.nodeColor(m),
            modifier = Modifier
                .offset { (topLeft + shift).round() }
                .size(cardSize)
                .padding(padding),
            lifted = true,
        )
    }
}

private fun cardPadding(vertical: Boolean, zoom: Float) = PaddingValues(
    start = BoardDims.cardPadding,
    top = BoardDims.cardPadding,
    end = if (vertical) BoardDims.groupStrip * zoom else BoardDims.cardPadding,
    bottom = if (vertical) BoardDims.cardPadding else BoardDims.groupStrip * zoom,
)

/** The board itself: grid, markers, group bars and labels, node cards and the timeline header. */
@Composable
private fun BoardContent(
    ui: BoardUi,
    shown: BoardState,
    geometry: BoardGeometry,
    spans: List<GroupSpan>,
    slots: IntRange,
    rowOf: Map<String, Int>,
    dragged: Set<String>,
    draggedMarker: String?,
    blockedBy: String?,
    selected: Set<String>,
    draggedGroup: DragItem.Group?,
    draggedTimeline: String?,
    sx: Float,
    sy: Float,
    view: Offset,
    headerDp: Dp,
    headerPx: Float,
    stripPx: Float,
    vertical: Boolean,
    axes: BoardAxes,
    zoom: Float,
    onTap: (BoardHit) -> Unit,
    onDrop: (DragItem, DragTarget) -> Unit,
) {
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    fun at(along: Float, across: Float) = axes.screen(headerPx + along, across)

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
        val barH = 3.dp.toPx() * zoom
        for (span in spans) {
            if (span.last < slots.first || span.first > slots.last) continue
            val color = ui.groupColor(span.groupId)?.let { Color(it) } ?: colors.tertiary
            val faded = ui.isHidden(ui.groupTags[span.groupId])
            val start = geometry.x(span.first) - sx + BoardDims.cardPadding.toPx()
            val end = geometry.x(span.last) + geometry.width(span.last) - sx - BoardDims.cardPadding.toPx()
            val y = geometry.y(span.row + 1) - sy - stripPx * barOffsetFraction(span.depth)
            drawRoundRect(
                color.copy(alpha = if (faded || span.groupId == draggedGroup?.id) 0.3f else 1f),
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
                .markerSemantics(ui, markerId, onTap, onDrop),
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
    val padding = cardPadding(vertical, zoom)
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
                    .padding(padding)
                    .nodeSemantics(ui, nodeId, onTap, onDrop),
                ghost = ghost,
                selected = nodeId in selected,
            )
        }
    }

    // Pinned timeline header: a column on the left, or a row along the top when vertical.
    val headerCell = with(density) { axes.size(headerPx, geometry.rowHeight).toDpSize() }
    val colorEdge = with(density) { axes.size(4.dp.toPx(), geometry.rowHeight).toDpSize() }
    val byId = ui.timelines.associateBy { it.id }
    Box(Modifier.band(vertical, headerDp).background(colors.surfaceContainer)) {
        // In the previewed order, so a dragged timeline's row moves as it would land.
        for ((r, id) in shown.timelines.withIndex()) {
            val t = byId[id] ?: continue
            Box(
                Modifier
                    .offset { axes.screen(0f, geometry.y(r) - sy).round() }
                    .size(headerCell)
                    .background(if (id == draggedTimeline) colors.secondaryContainer else Color.Transparent)
                    .padding(horizontal = 8.dp)
                    .timelineSemantics(ui, id, onTap, onDrop),
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
        // Add a timeline after the last one.
        Box(
            Modifier
                .offset { axes.screen(0f, geometry.contentHeight - sy).round() }
                .size(with(density) { axes.size(headerPx, BoardDims.addTimeline.toPx()).toDpSize() })
                .semantics(mergeDescendants = true) { onClick("Add timeline") { onTap(BoardHit.AddTimeline); true } },
            contentAlignment = Alignment.Center,
        ) {
            Icon(Icons.Default.Add, "Add timeline", tint = colors.primary)
        }
    }
}

/** Accessibility for a timeline header: edit on activation, reorder, tray and delete actions. */
private fun Modifier.timelineSemantics(
    ui: BoardUi,
    timelineId: String,
    onTap: (BoardHit) -> Unit,
    onDrop: (DragItem, DragTarget) -> Unit,
): Modifier = semantics(mergeDescendants = true) {
    val i = ui.board.timelines.indexOf(timelineId)
    val item = DragItem.Timeline(timelineId)
    stateDescription = "Timeline ${i + 1} of ${ui.board.timelines.size}"
    onClick("Edit") { onTap(BoardHit.Timeline(timelineId)); true }
    customActions = buildList {
        if (i > 0) add(CustomAccessibilityAction("Move earlier") { onDrop(item, DragTarget.TimelineAt(i - 1)); true })
        if (i < ui.board.timelines.lastIndex) add(CustomAccessibilityAction("Move later") { onDrop(item, DragTarget.TimelineAt(i + 1)); true })
        add(CustomAccessibilityAction("Move nodes to tray") { onDrop(item, DragTarget.Tray(ui.tray.size)); true })
        add(CustomAccessibilityAction("Delete with its nodes") { onDrop(item, DragTarget.Trash); true })
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
        add(CustomAccessibilityAction("Move to tray") { onDrop(DragItem.Node(nodeId), DragTarget.Tray(ui.tray.size)); true })
        add(CustomAccessibilityAction("Delete") { onDrop(DragItem.Node(nodeId), DragTarget.Trash); true })
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
        is DragItem.Timeline -> 0 to board.timelines.indexOf(item.id)
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
    addPx: Float,
): BoardHit? {
    val cy = p.y + vp.scrollAcross
    val row = g.rowAt(cy)
    if (p.x < headerPx) {
        if (row == null && cy >= g.contentHeight && cy < g.contentHeight + addPx) return BoardHit.AddTimeline
        return row?.let { BoardHit.Timeline(ui.board.timelines[it]) }
    }
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
