package com.storyarc.app.data.repo

import androidx.room.withTransaction
import com.storyarc.app.data.db.MarkerEntity
import com.storyarc.app.data.db.NodeEntity
import com.storyarc.app.data.db.NodeGroupEntity
import com.storyarc.app.data.db.ProjectEntity
import com.storyarc.app.data.db.ProjectWorldEntity
import com.storyarc.app.data.db.StoryDatabase
import com.storyarc.app.data.db.TimelineEntity
import com.storyarc.app.data.db.ViewEntity
import com.storyarc.app.data.db.WorldEntity
import com.storyarc.app.data.db.deleteAny
import com.storyarc.app.data.db.rowKey
import com.storyarc.app.data.db.upsertAny
import com.storyarc.app.domain.undo.Change
import com.storyarc.app.domain.undo.ChangeSet
import com.storyarc.app.domain.undo.UndoManager
import com.storyarc.board.BoardState
import com.storyarc.board.Drop
import com.storyarc.board.Item
import com.storyarc.board.addMarker
import com.storyarc.board.addNode
import com.storyarc.board.addTimeline
import com.storyarc.board.createGroup
import com.storyarc.board.move
import com.storyarc.board.moveMarker
import com.storyarc.board.moveTimeline
import com.storyarc.board.removeMarker
import com.storyarc.board.removeNode
import com.storyarc.board.removeTimeline
import com.storyarc.board.ungroup
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

/**
 * All writes to project content. Undoable edits are built as [ChangeSet]s and recorded on the
 * project's [UndoManager]; project lifecycle operations (create/delete) are not undoable.
 */
class StoryRepository(
    private val db: StoryDatabase,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val rows = db.rows()
    val projects = db.projects()
    val board = db.board()

    private val lock = Mutex()
    private val undoManagers = HashMap<String, UndoManager>()

    fun undoManager(projectId: String): UndoManager = synchronized(undoManagers) {
        undoManagers.getOrPut(projectId) { UndoManager(::write, ::rowKey) }
    }

    /**
     * Writes a change set atomically: upserts first, then deletes, so no row is removed while
     * another row in the same set still points at it.
     */
    suspend fun write(cs: ChangeSet) = db.withTransaction {
        cs.changes.forEach { c -> c.after?.let { rows.upsertAny(it) } }
        cs.changes.asReversed().forEach { c -> if (c.after == null) rows.deleteAny(c.before!!) }
    }

    suspend fun undo(projectId: String) = lock.withLock { undoManager(projectId).undo() }

    suspend fun redo(projectId: String) = lock.withLock { undoManager(projectId).redo() }

    private suspend fun perform(projectId: String, cs: ChangeSet, coalesceKey: String? = null) {
        undoManager(projectId).perform(cs, coalesceKey)
        if (!cs.isEmpty) touch(projectId)
    }

    private suspend fun touch(projectId: String) {
        projects.project(projectId)?.let { rows.upsert(it.copy(updatedAt = clock())) }
    }

    // --- Projects (not undoable) ---

    /** Creates a project with its own default world and one empty view; returns the project id. */
    suspend fun createProject(name: String): String = lock.withLock {
        val now = clock()
        val project = ProjectEntity(newId(), name, now, now)
        val world = WorldEntity(newId(), name)
        write(
            ChangeSet.insert(
                "Create project", project, world, ProjectWorldEntity(project.id, world.id),
                ViewEntity(newId(), project.id, "Main", 0),
            )
        )
        project.id
    }

    suspend fun renameProject(projectId: String, name: String) = lock.withLock {
        projects.project(projectId)?.let { rows.upsert(it.copy(name = name, updatedAt = clock())) }
    }

    /** Deletes a project and every world library no other project links. */
    suspend fun deleteProject(projectId: String) = lock.withLock {
        db.withTransaction {
            val worlds = projects.exclusiveWorlds(projectId)
            projects.deleteProject(projectId)
            projects.deleteWorlds(worlds)
        }
        synchronized(undoManagers) { undoManagers.remove(projectId) }
    }

    // --- Board edits (undoable) ---

    private suspend fun projectOf(viewId: String) = requireNotNull(projects.view(viewId)) { "unknown view $viewId" }.projectId

    /**
     * Runs a board operation on a view and records the resulting row changes plus [extra]
     * changes (content rows created or removed alongside). Tag rows of markers and groups the
     * operation deletes are removed too.
     */
    private suspend fun boardEdit(
        viewId: String,
        label: String,
        extra: List<Change> = emptyList(),
        newMarkers: List<MarkerEntity> = emptyList(),
        newGroups: List<NodeGroupEntity> = emptyList(),
        op: (BoardState) -> BoardState,
    ) {
        val before = ViewRows.load(board, viewId)
        val after = before.withBoard(op(before.toBoard()), newMarkers, newGroups)
        val goneMarkers = before.markers.map { it.id } - after.markers.map { it.id }.toSet()
        val goneGroups = before.groups.map { it.id } - after.groups.map { it.id }.toSet()
        val tagDeletes = (board.markerTags(goneMarkers) + board.groupTags(goneGroups)).map { Change(it, null) }
        perform(projectOf(viewId), ChangeSet(label, extra + before.diffTo(after) + tagDeletes))
    }

    suspend fun addTimeline(viewId: String, name: String, index: Int? = null): String = lock.withLock {
        val t = TimelineEntity(newId(), projectOf(viewId), name)
        boardEdit(viewId, "Add timeline", extra = listOf(Change(null, t))) { it.addTimeline(t.id, index ?: it.timelines.size) }
        t.id
    }

    suspend fun updateTimeline(timeline: TimelineEntity) = lock.withLock {
        val old = requireNotNull(projects.timeline(timeline.id))
        perform(old.projectId, ChangeSet("Edit timeline", listOf(Change(old, timeline))), "timeline:${timeline.id}")
    }

    suspend fun moveTimeline(viewId: String, timelineId: String, toIndex: Int) = lock.withLock {
        boardEdit(viewId, "Reorder timelines") { it.moveTimeline(timelineId, toIndex) }
    }

    /**
     * Removes a timeline from a view along with its nodes there. Nodes left with no placement
     * anywhere, and the timeline itself if no view uses it any more, are deleted.
     */
    suspend fun removeTimelineFromView(viewId: String, timelineId: String) = lock.withLock {
        val removedNodes = board.placements(viewId).filter { it.timelineId == timelineId }.map { it.nodeId }
        val extra = buildList {
            removedNodes.forEach { addAll(orphanDeletes(it, viewId)) }
            if (board.viewTimelinesOf(timelineId).all { it.viewId == viewId }) {
                add(Change(requireNotNull(projects.timeline(timelineId)), null))
            }
        }
        boardEdit(viewId, "Remove timeline", extra) { it.removeTimeline(timelineId) }
    }

    /** Deletes of a node and its tags if [viewId] holds its only placement. */
    private suspend fun orphanDeletes(nodeId: String, viewId: String): List<Change> {
        if (board.placementsOfNode(nodeId).any { it.viewId != viewId }) return emptyList()
        return board.nodeTags(nodeId).map { Change(it, null) } + Change(requireNotNull(projects.node(nodeId)), null)
    }

    suspend fun addNode(viewId: String, text: String, drop: Drop): String = lock.withLock {
        val node = NodeEntity(newId(), projectOf(viewId), text)
        boardEdit(viewId, "Add node", extra = listOf(Change(null, node))) { it.addNode(node.id, drop) }
        node.id
    }

    suspend fun updateNode(node: NodeEntity) = lock.withLock {
        val old = requireNotNull(projects.node(node.id))
        perform(old.projectId, ChangeSet("Edit node", listOf(Change(old, node))), "node:${node.id}")
    }

    /** True if removing the node from [viewId] would delete it everywhere. */
    suspend fun isLastPlacement(viewId: String, nodeId: String): Boolean =
        board.placementsOfNode(nodeId).all { it.viewId == viewId }

    suspend fun removeNodeFromView(viewId: String, nodeId: String) = lock.withLock {
        boardEdit(viewId, "Remove node", orphanDeletes(nodeId, viewId)) { it.removeNode(nodeId) }
    }

    /** Deletes a node from every view. */
    suspend fun deleteNodeEverywhere(nodeId: String) = lock.withLock {
        val node = requireNotNull(projects.node(nodeId))
        val placements = board.placementsOfNode(nodeId)
        val changes = buildList {
            for (p in placements) {
                val before = ViewRows.load(board, p.viewId)
                val after = before.withBoard(before.toBoard().removeNode(nodeId))
                addAll(before.diffTo(after))
                val gone = before.groups.map { it.id } - after.groups.map { it.id }.toSet()
                addAll(board.groupTags(gone).map { Change(it, null) })
            }
            addAll(board.nodeTags(nodeId).map { Change(it, null) })
            add(Change(node, null))
        }
        perform(node.projectId, ChangeSet("Delete node", changes))
    }

    suspend fun move(viewId: String, item: Item, drop: Drop) = lock.withLock {
        boardEdit(viewId, "Move") { it.move(item, drop) }
    }

    suspend fun addMarker(viewId: String, label: String, beforeSlot: Int): String = lock.withLock {
        val m = MarkerEntity(newId(), viewId, beforeSlot, label)
        boardEdit(viewId, "Add marker", newMarkers = listOf(m)) { it.addMarker(m.id, beforeSlot) }
        m.id
    }

    suspend fun updateMarker(marker: MarkerEntity) = lock.withLock {
        val old = requireNotNull(board.markers(marker.viewId).find { it.id == marker.id })
        perform(projectOf(marker.viewId), ChangeSet("Edit marker", listOf(Change(old, marker.copy(slot = old.slot)))), "marker:${marker.id}")
    }

    suspend fun moveMarker(viewId: String, markerId: String, beforeSlot: Int) = lock.withLock {
        boardEdit(viewId, "Move marker") { it.moveMarker(markerId, beforeSlot) }
    }

    suspend fun removeMarker(viewId: String, markerId: String) = lock.withLock {
        boardEdit(viewId, "Remove marker") { it.removeMarker(markerId) }
    }

    suspend fun createGroup(viewId: String, nodeIds: Collection<String>, label: String = ""): String = lock.withLock {
        val id = newId()
        val timeline = requireNotNull(board.placements(viewId).find { it.nodeId == nodeIds.first() }).timelineId
        val g = NodeGroupEntity(id, viewId, timeline, null, label)
        boardEdit(viewId, "Group", newGroups = listOf(g)) { it.createGroup(id, nodeIds) }
        id
    }

    suspend fun updateGroup(group: NodeGroupEntity) = lock.withLock {
        val old = requireNotNull(board.groups(group.viewId).find { it.id == group.id })
        val edited = old.copy(label = group.label, color = group.color)
        perform(projectOf(group.viewId), ChangeSet("Edit group", listOf(Change(old, edited))), "group:${group.id}")
    }

    suspend fun ungroup(viewId: String, groupId: String) = lock.withLock {
        boardEdit(viewId, "Ungroup") { it.ungroup(groupId) }
    }

    // --- Views (undoable) ---

    /** What a new view starts with, copied from an existing view. */
    enum class ViewImport { NOTHING, TIMELINES, TIMELINES_AND_NODES }

    /**
     * Creates a view. Imported timelines and nodes are shared instances (same rows, new
     * positions); markers and groups are positional and are copied with new ids.
     */
    suspend fun createView(projectId: String, name: String, from: String? = null, import: ViewImport = ViewImport.NOTHING): String =
        lock.withLock {
            val view = ViewEntity(newId(), projectId, name, projects.views(projectId).size)
            val rows = buildList<Any> {
                add(view)
                if (from != null && import != ViewImport.NOTHING) {
                    val src = ViewRows.load(board, from)
                    src.timelines.forEach { add(it.copy(viewId = view.id)) }
                    if (import == ViewImport.TIMELINES_AND_NODES) {
                        val groupIds = src.groups.associate { it.id to newId() }
                        src.groups.forEach { add(it.copy(id = groupIds.getValue(it.id), viewId = view.id, parentId = it.parentId?.let(groupIds::getValue))) }
                        src.placements.forEach { add(it.copy(viewId = view.id, groupId = it.groupId?.let(groupIds::getValue))) }
                        board.groupTags(src.groups.map { it.id }).forEach { add(it.copy(targetId = groupIds.getValue(it.targetId))) }
                        val markerIds = src.markers.associate { it.id to newId() }
                        src.markers.forEach { add(it.copy(id = markerIds.getValue(it.id), viewId = view.id)) }
                        board.markerTags(src.markers.map { it.id }).forEach { add(it.copy(targetId = markerIds.getValue(it.targetId))) }
                    }
                }
            }
            perform(projectId, ChangeSet("Create view", rows.map { Change(null, it) }))
            view.id
        }

    suspend fun renameView(viewId: String, name: String) = lock.withLock {
        val old = requireNotNull(projects.view(viewId))
        perform(old.projectId, ChangeSet("Rename view", listOf(Change(old, old.copy(name = name)))))
    }

    /**
     * Deletes a view with its layout. Nodes and timelines used by no other view are deleted
     * too. A project always keeps at least one view.
     */
    suspend fun deleteView(viewId: String) = lock.withLock {
        val view = requireNotNull(projects.view(viewId))
        val siblings = projects.views(view.projectId)
        require(siblings.size > 1) { "a project needs at least one view" }
        val rows = ViewRows.load(board, viewId)
        val changes = buildList {
            rows.placements.forEach { p ->
                add(Change(p, null))
                addAll(orphanDeletes(p.nodeId, viewId))
            }
            rows.timelines.forEach { vt ->
                add(Change(vt, null))
                if (board.viewTimelinesOf(vt.timelineId).all { it.viewId == viewId }) {
                    add(Change(requireNotNull(projects.timeline(vt.timelineId)), null))
                }
            }
            board.markerTags(rows.markers.map { it.id }).forEach { add(Change(it, null)) }
            board.groupTags(rows.groups.map { it.id }).forEach { add(Change(it, null)) }
            rows.markers.forEach { add(Change(it, null)) }
            rows.groups.forEach { add(Change(it, null)) }
            board.hiddenTags(viewId).forEach { add(Change(it, null)) }
            add(Change(view, null))
            // Keep remaining view ordinals dense.
            siblings.filter { it.id != viewId }.sortedBy { it.ordinal }.forEachIndexed { i, v ->
                if (v.ordinal != i) add(Change(v, v.copy(ordinal = i)))
            }
        }
        perform(view.projectId, ChangeSet("Delete view", changes))
    }
}
