package com.storyarc.app.ui.board

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.round
import com.storyarc.board.Block
import com.storyarc.board.Drop

/**
 * The tray: a header that collapses it and the [listed] items in order. Items are positioned
 * by [tray] so the canvas can hit-test them; gestures are handled by the canvas.
 */
@Composable
fun TrayStrip(
    ui: BoardUi,
    listed: List<Block>,
    total: Int,
    filtered: Boolean,
    tray: TrayGeometry,
    highlighted: Boolean,
    draggingIndex: Int?,
    onTap: (BoardHit) -> Unit,
    onDrop: (DragItem, DragTarget) -> Unit,
) {
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val r = tray.rect
    Box(
        Modifier
            .offset { r.topLeft.round() }
            .size(with(density) { r.size.toDpSize() })
            .background(if (highlighted) colors.secondaryContainer else colors.surfaceContainerHigh)
            .clipToBounds(),
    ) {
        // Header: the title toggles the tray; the end buttons are sort/filter and collapse.
        Row(
            Modifier
                .size(with(density) { tray.header.size.toDpSize() })
                .padding(start = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            val toggle = Modifier.semantics(mergeDescendants = true) {
                onClick(if (tray.expanded) "Collapse tray" else "Expand tray") { onTap(BoardHit.TrayToggle); true }
            }
            if (tray.side && !tray.expanded) {
                Row(toggle, verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Inventory2, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                    Text("${listed.size}", style = MaterialTheme.typography.labelMedium)
                }
            } else {
                Row(toggle.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Icon(Icons.Default.Inventory2, null, Modifier.size(18.dp), tint = colors.onSurfaceVariant)
                    Text(
                        when {
                            filtered -> "Tray · ${listed.size} of $total"
                            total == 0 -> "Tray"
                            else -> "Tray · $total"
                        },
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Box(
                    Modifier.size(TrayDims.headerButton, TrayDims.header)
                        .semantics { onClick("Sort and filter tray") { onTap(BoardHit.TrayOptions); true } },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.Tune, "Sort and filter", Modifier.size(20.dp), tint = if (filtered) colors.primary else colors.onSurfaceVariant)
                }
                Box(Modifier.size(TrayDims.headerButton, TrayDims.header), contentAlignment = Alignment.Center) {
                    // Chevron points where the tray will go: toward its edge when expanded.
                    Icon(
                        if (tray.expanded) Icons.Default.ExpandMore else Icons.Default.ExpandLess,
                        null,
                        Modifier.size(20.dp).rotate(if (tray.side) -90f else 0f),
                        tint = colors.onSurfaceVariant,
                    )
                }
            }
        }
        if (tray.expanded && listed.isEmpty()) {
            Text(
                if (total > 0) "Nothing matches the filter." else "Unplaced nodes wait here. Drag a node here to take it off the board.",
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                modifier = Modifier
                    .offset { (tray.itemArea.topLeft - r.topLeft).round() }
                    .size(with(density) { tray.itemArea.size.toDpSize() })
                    .padding(12.dp),
            )
        }
        if (tray.expanded) {
            listed.forEachIndexed { i, b ->
                val rect = tray.itemRect(i)
                if (!rect.overlaps(tray.itemArea)) return@forEachIndexed
                TrayCard(
                    ui, b,
                    Modifier
                        .offset { (rect.topLeft - r.topLeft).round() }
                        .size(with(density) { rect.size.toDpSize() })
                        .alpha(if (i == draggingIndex) 0.3f else 1f)
                        .traySemantics(ui, b, onTap, onDrop),
                )
            }
        }
    }
}

/** One tray item: a node card, or a group shown as its label over its first node. */
@Composable
private fun TrayCard(ui: BoardUi, block: Block, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val group = block.root
    if (group == null) {
        val id = block.nodes.keys.single()
        val node = ui.nodes[id] ?: return
        NodeCard(node, modifier, color = ui.nodeColor(id), tags = ui.nodeTags[id].orEmpty().mapNotNull { ui.tags[it] })
        return
    }
    val accent = ui.groupColor(group)?.let { Color(it) } ?: colors.tertiary
    val first = block.nodes.entries.minBy { it.value }.key
    Surface(modifier, shape = RoundedCornerShape(10.dp), color = colors.secondaryContainer, shadowElevation = 1.dp) {
        Row {
            Box(Modifier.width(6.dp).fillMaxHeight().background(accent))
            Column(Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                Text(
                    ui.groups[group]?.label?.ifBlank { null } ?: "Group",
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${block.nodes.size} nodes · " + ui.nodes[first]?.text?.ifBlank { "Untitled" }.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

private fun Modifier.traySemantics(
    ui: BoardUi,
    block: Block,
    onTap: (BoardHit) -> Unit,
    onDrop: (DragItem, DragTarget) -> Unit,
): Modifier = semantics(mergeDescendants = true) {
    val group = block.root
    val item = group?.let { DragItem.Group(it) } ?: DragItem.Node(block.nodes.keys.single())
    stateDescription = "In tray"
    onClick("Edit") { onTap(group?.let { BoardHit.Group(it) } ?: BoardHit.Node(block.nodes.keys.single())); true }
    customActions = buildList {
        ui.board.timelines.firstOrNull()?.let { t ->
            add(CustomAccessibilityAction("Place at the end of ${ui.timelines.firstOrNull()?.name ?: "the board"}") {
                onDrop(item, DragTarget.Place(Drop.Onto(t, ui.board.slotCount))); true
            })
        }
        add(CustomAccessibilityAction("Delete") { onDrop(item, DragTarget.Trash); true })
    }
}

/** The trash target shown while dragging. */
@Composable
fun Trash(rect: Rect, active: Boolean) {
    val density = LocalDensity.current
    val colors = MaterialTheme.colorScheme
    val grow = if (active) with(density) { 6.dp.toPx() } else 0f
    val r = rect.inflate(grow)
    Box(
        Modifier
            .offset { r.topLeft.round() }
            .size(with(density) { r.size.toDpSize() })
            .background(if (active) colors.error else colors.errorContainer, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Default.Delete, "Trash", tint = if (active) colors.onError else colors.onErrorContainer)
    }
}
