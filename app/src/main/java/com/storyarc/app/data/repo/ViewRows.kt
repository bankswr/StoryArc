package com.storyarc.app.data.repo

import com.storyarc.app.data.db.BoardDao
import com.storyarc.app.data.db.MarkerEntity
import com.storyarc.app.data.db.NodeGroupEntity
import com.storyarc.app.data.db.PlacementEntity
import com.storyarc.app.data.db.ViewTimelineEntity
import com.storyarc.app.data.db.rowKey
import com.storyarc.app.domain.undo.Change
import com.storyarc.app.domain.undo.diffRows
import com.storyarc.board.BoardState
import com.storyarc.board.Cell
import com.storyarc.board.Group

/** The layout rows of one view, convertible to and from a [BoardState]. */
data class ViewRows(
    val viewId: String,
    val timelines: List<ViewTimelineEntity>,
    val placements: List<PlacementEntity>,
    val markers: List<MarkerEntity>,
    val groups: List<NodeGroupEntity>,
) {
    fun toBoard() = BoardState(
        timelines = timelines.sortedBy { it.ordinal }.map { it.timelineId },
        placements = placements.associate { it.nodeId to Cell(it.timelineId, it.slot) },
        markers = markers.associate { it.id to it.slot },
        groups = groups.associate { it.id to Group(it.id, it.timelineId, it.parentId) },
        membership = placements.mapNotNull { p -> p.groupId?.let { p.nodeId to it } }.toMap(),
    )

    /**
     * Rows reflecting [board]. Markers and groups the board introduces must be supplied in
     * [newMarkers] / [newGroups] so their non-layout fields (label, color) are known.
     */
    fun withBoard(
        board: BoardState,
        newMarkers: List<MarkerEntity> = emptyList(),
        newGroups: List<NodeGroupEntity> = emptyList(),
    ): ViewRows {
        val markerRows = (markers + newMarkers).associateBy { it.id }
        val groupRows = (groups + newGroups).associateBy { it.id }
        return copy(
            timelines = board.timelines.mapIndexed { i, t -> ViewTimelineEntity(viewId, t, i) },
            placements = board.placements.map { (n, c) -> PlacementEntity(viewId, n, c.timelineId, c.slot, board.membership[n]) },
            markers = board.markers.map { (id, s) -> markerRows.getValue(id).copy(slot = s) },
            groups = board.groups.values.map { g -> groupRows.getValue(g.id).copy(timelineId = g.timelineId, parentId = g.parentId) },
        )
    }

    fun diffTo(new: ViewRows): List<Change> =
        diffRows(timelines, new.timelines, ::rowKey) + diffRows(groups, new.groups, ::rowKey) +
            diffRows(placements, new.placements, ::rowKey) + diffRows(markers, new.markers, ::rowKey)

    companion object {
        suspend fun load(dao: BoardDao, viewId: String) = ViewRows(
            viewId, dao.viewTimelines(viewId), dao.placements(viewId), dao.markers(viewId), dao.groups(viewId),
        )
    }
}
