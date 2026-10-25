package com.storyarc.app.data.repo

import com.storyarc.app.data.db.BoardDao
import com.storyarc.app.data.db.MarkerEntity
import com.storyarc.app.data.db.NodeGroupEntity
import com.storyarc.app.data.db.PlacementEntity
import com.storyarc.app.data.db.TrayEntity
import com.storyarc.app.data.db.ViewTimelineEntity
import com.storyarc.app.data.db.rowKey
import com.storyarc.app.domain.undo.Change
import com.storyarc.app.domain.undo.diffRows
import com.storyarc.board.Block
import com.storyarc.board.BoardState
import com.storyarc.board.Cell
import com.storyarc.board.Group

/** A view's board plus its tray of unplaced items, in tray order. */
data class Layout(val board: BoardState, val tray: List<Block>)

/** The layout rows of one view, convertible to and from a [Layout]. [groups] includes tray groups. */
data class ViewRows(
    val viewId: String,
    val timelines: List<ViewTimelineEntity>,
    val placements: List<PlacementEntity>,
    val markers: List<MarkerEntity>,
    val groups: List<NodeGroupEntity>,
    val tray: List<TrayEntity> = emptyList(),
) {
    fun toBoard() = BoardState(
        timelines = timelines.sortedBy { it.ordinal }.map { it.timelineId },
        placements = placements.associate { it.nodeId to Cell(it.timelineId, it.slot) },
        markers = markers.associate { it.id to it.slot },
        groups = groups.mapNotNull { g -> g.timelineId?.let { g.id to Group(g.id, it, g.parentId) } }.toMap(),
        membership = placements.mapNotNull { p -> p.groupId?.let { p.nodeId to it } }.toMap(),
    )

    /** Tray items in order. A tray group belongs to the item whose nodes it contains. */
    fun toTray(): List<Block> {
        val parents = groups.filter { it.timelineId == null }.associate { it.id to it.parentId }
        return tray.groupBy { it.ordinal }.toSortedMap().values.map { rows ->
            val membership = rows.mapNotNull { r -> r.groupId?.let { r.nodeId to it } }.toMap()
            val inside = membership.values.flatMap { g -> generateSequence(g) { parents[it] }.toList() }.toSet()
            Block(rows.associate { it.nodeId to it.offset }, inside.associateWith { parents[it] }, membership)
        }
    }

    fun toLayout() = Layout(toBoard(), toTray())

    /**
     * Rows reflecting [layout]. Markers and groups it introduces must be supplied in
     * [newMarkers] / [newGroups] so their non-layout fields (label, color) are known.
     */
    fun withLayout(
        layout: Layout,
        newMarkers: List<MarkerEntity> = emptyList(),
        newGroups: List<NodeGroupEntity> = emptyList(),
    ): ViewRows {
        val board = layout.board
        val markerRows = (markers + newMarkers).associateBy { it.id }
        val groupRows = (groups + newGroups).associateBy { it.id }
        val trayGroups = layout.tray.flatMap { b -> b.groups.map { (id, parent) -> groupRows.getValue(id).copy(timelineId = null, parentId = parent) } }
        return copy(
            timelines = board.timelines.mapIndexed { i, t -> ViewTimelineEntity(viewId, t, i) },
            placements = board.placements.map { (n, c) -> PlacementEntity(viewId, n, c.timelineId, c.slot, board.membership[n]) },
            markers = board.markers.map { (id, s) -> markerRows.getValue(id).copy(slot = s) },
            groups = board.groups.values.map { g -> groupRows.getValue(g.id).copy(timelineId = g.timelineId, parentId = g.parentId) } + trayGroups,
            tray = layout.tray.flatMapIndexed { i, b -> b.nodes.map { (n, off) -> TrayEntity(viewId, n, i, off, b.membership[n]) } },
        )
    }

    fun withBoard(
        board: BoardState,
        newMarkers: List<MarkerEntity> = emptyList(),
        newGroups: List<NodeGroupEntity> = emptyList(),
    ) = withLayout(Layout(board, toTray()), newMarkers, newGroups)

    fun diffTo(new: ViewRows): List<Change> =
        diffRows(timelines, new.timelines, ::rowKey) + diffRows(groups, new.groups, ::rowKey) +
            diffRows(placements, new.placements, ::rowKey) + diffRows(markers, new.markers, ::rowKey) +
            diffRows(tray, new.tray, ::rowKey)

    companion object {
        suspend fun load(dao: BoardDao, viewId: String) = ViewRows(
            viewId, dao.viewTimelines(viewId), dao.placements(viewId), dao.markers(viewId), dao.groups(viewId), dao.tray(viewId),
        )
    }
}
