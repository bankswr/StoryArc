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
import com.storyarc.app.data.db.CategoryEntity
import com.storyarc.app.data.db.NoteEntity
import com.storyarc.app.data.db.NoteLinkEntity
import com.storyarc.app.data.db.GroupTagEntity
import com.storyarc.app.data.db.MarkerTagEntity
import com.storyarc.app.data.db.NodeTagEntity
import com.storyarc.app.data.db.NoteTagEntity
import com.storyarc.app.data.db.TagEntity
import com.storyarc.app.data.db.ViewHiddenTagEntity
import com.storyarc.app.domain.undo.diffRows
import com.storyarc.app.data.db.WorldEntity
import com.storyarc.app.data.db.deleteAny
import com.storyarc.app.data.db.rowKey
import com.storyarc.app.data.db.upsertAny
import com.storyarc.app.domain.undo.Change
import com.storyarc.app.domain.undo.ChangeSet
import com.storyarc.app.domain.undo.UndoManager
import com.storyarc.board.Block
import com.storyarc.board.BoardState
import com.storyarc.board.extract
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
    val tags = db.tags()
    val worlds = db.worlds()

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

    /** Drops every project's undo history, after bulk changes it can't describe. */
    fun clearAllUndo() = synchronized(undoManagers) { undoManagers.values.forEach { it.clear() } }

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
     * Runs a layout operation (board and tray) on a view and records the resulting row changes
     * plus [extra] changes (content rows created or removed alongside). Tag rows of markers and
     * groups the operation deletes are removed too.
     */
    private suspend fun layoutEdit(
        viewId: String,
        label: String,
        extra: List<Change> = emptyList(),
        newMarkers: List<MarkerEntity> = emptyList(),
        newGroups: List<NodeGroupEntity> = emptyList(),
        op: (Layout) -> Layout,
    ) {
        val before = ViewRows.load(board, viewId)
        val after = before.withLayout(op(before.toLayout()), newMarkers, newGroups)
        perform(projectOf(viewId), ChangeSet(label, extra + layoutChanges(before, after)))
    }

    /** Row changes from [before] to [after], plus tag rows of markers and groups that went away. */
    private suspend fun layoutChanges(before: ViewRows, after: ViewRows): List<Change> {
        val goneMarkers = before.markers.map { it.id } - after.markers.map { it.id }.toSet()
        val goneGroups = before.groups.map { it.id } - after.groups.map { it.id }.toSet()
        val tagDeletes = (board.markerTags(goneMarkers) + board.groupTags(goneGroups)).map { Change(it, null) }
        return before.diffTo(after) + tagDeletes
    }

    private suspend fun boardEdit(
        viewId: String,
        label: String,
        extra: List<Change> = emptyList(),
        newMarkers: List<MarkerEntity> = emptyList(),
        newGroups: List<NodeGroupEntity> = emptyList(),
        op: (BoardState) -> BoardState,
    ) = layoutEdit(viewId, label, extra, newMarkers, newGroups) { it.copy(board = op(it.board)) }

    suspend fun addTimeline(viewId: String, name: String, index: Int? = null): String = lock.withLock {
        val t = TimelineEntity(newId(), projectOf(viewId), name)
        boardEdit(viewId, "Add timeline", extra = listOf(Change(null, t))) { it.addTimeline(t.id, index ?: it.timelines.size) }
        t.id
    }

    suspend fun renameTimeline(timelineId: String, name: String) = lock.withLock {
        val old = requireNotNull(projects.timeline(timelineId))
        perform(old.projectId, ChangeSet("Rename timeline", listOf(Change(old, old.copy(name = name)))), "timeline:$timelineId")
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

    /**
     * Removes a timeline from a view and moves its nodes and groups to the view's tray. The
     * timeline itself is deleted if no other view uses it.
     */
    suspend fun moveTimelineToTray(viewId: String, timelineId: String) = lock.withLock {
        val extra = buildList {
            if (board.viewTimelinesOf(timelineId).all { it.viewId == viewId }) add(Change(requireNotNull(projects.timeline(timelineId)), null))
        }
        layoutEdit(viewId, "Timeline to tray", extra) { it.timelineToTray(timelineId) }
    }

    /**
     * Deletes a timeline from every view, with every node placed on it in any view (those nodes
     * are deleted everywhere, as from the trash).
     */
    suspend fun deleteTimelineEverywhere(timelineId: String) = lock.withLock {
        val timeline = requireNotNull(projects.timeline(timelineId))
        val views = board.viewTimelinesOf(timelineId).map { it.viewId }
        val nodeIds = views.flatMap { v -> board.placements(v).filter { it.timelineId == timelineId }.map { it.nodeId } }.toSet()
        val changes = buildList {
            for (v in (views + nodeIds.flatMap { board.viewsOfNode(it) }).distinct()) {
                val before = ViewRows.load(board, v)
                val without = before.toLayout().without(nodeIds)
                val after = if (v in views) without.copy(board = without.board.removeTimeline(timelineId)) else without
                addAll(layoutChanges(before, before.withLayout(after)))
            }
            nodeIds.forEach { n -> addAll(board.nodeTags(n).map { Change(it, null) }) }
            nodeIds.forEach { n -> add(Change(requireNotNull(projects.node(n)), null)) }
            add(Change(timeline, null))
        }
        perform(timeline.projectId, ChangeSet("Delete timeline", changes))
    }

    /** Deletes of a node and its tags if [viewId] is the only view holding it (placed or in the tray). */
    private suspend fun orphanDeletes(nodeId: String, viewId: String): List<Change> {
        if (board.viewsOfNode(nodeId).any { it != viewId }) return emptyList()
        return board.nodeTags(nodeId).map { Change(it, null) } + Change(requireNotNull(projects.node(nodeId)), null)
    }

    suspend fun addNode(viewId: String, text: String, drop: Drop): String = lock.withLock {
        val node = NodeEntity(newId(), projectOf(viewId), text)
        boardEdit(viewId, "Add node", extra = listOf(Change(null, node))) { it.addNode(node.id, drop) }
        node.id
    }

    /** Sets a node's text; consecutive edits of the same node coalesce into one undo step. */
    suspend fun setNodeText(nodeId: String, text: String) = lock.withLock {
        val old = requireNotNull(projects.node(nodeId))
        perform(old.projectId, ChangeSet("Edit node", listOf(Change(old, old.copy(text = text)))), "node:$nodeId")
    }

    /** Creates an empty-texted node at the end of [viewId]'s tray. */
    suspend fun addNodeToTray(viewId: String, text: String = ""): String = lock.withLock {
        val node = NodeEntity(newId(), projectOf(viewId), text)
        layoutEdit(viewId, "Add node", extra = listOf(Change(null, node))) { it.append(Block(mapOf(node.id to 0))) }
        node.id
    }

    /**
     * Withdraws a node that was just added: if creating it is the latest undo step, that step
     * is reverted without a trace; otherwise the node is deleted normally.
     */
    suspend fun discardNewNode(viewId: String, nodeId: String) = lock.withLock { discard(viewId, nodeId) }

    /**
     * Discards a just-added node if it has no text, tags or color; returns whether it did. Runs
     * after edits already requested, so a quick "type, then done" keeps the text.
     */
    suspend fun discardNewNodeIfBlank(viewId: String, nodeId: String): Boolean = lock.withLock {
        val node = projects.node(nodeId) ?: return@withLock true
        if (node.text.isNotBlank() || node.color != null || board.nodeTags(nodeId).isNotEmpty()) return@withLock false
        discard(viewId, nodeId)
        true
    }

    private suspend fun discard(viewId: String, nodeId: String) {
        val retracted = undoManager(projectOf(viewId)).retract { cs ->
            cs.changes.any { it.before == null && (it.after as? NodeEntity)?.id == nodeId }
        }
        if (!retracted) deleteNodes(setOf(nodeId), "Delete node")
    }

    /** True if [viewId] is the only view holding the node, so removing it there deletes it. */
    suspend fun isLastPlacement(viewId: String, nodeId: String): Boolean =
        board.viewsOfNode(nodeId).all { it == viewId }

    /** Takes a node out of the view entirely (board or tray), deleting it if no other view holds it. */
    suspend fun removeNodeFromView(viewId: String, nodeId: String) = lock.withLock {
        layoutEdit(viewId, "Remove node", orphanDeletes(nodeId, viewId)) { it.without(setOf(nodeId)) }
    }

    /** Moves a node or group from the board into the tray, or reorders it within the tray. */
    suspend fun moveToTray(viewId: String, item: Item, index: Int? = null) = lock.withLock {
        layoutEdit(viewId, "Move to tray") { it.toTray(item, index) }
    }

    /** Places a tray item (a node, or a group by its top group) on the board. */
    suspend fun placeFromTray(viewId: String, item: Item, drop: Drop) = lock.withLock {
        layoutEdit(viewId, "Place") { it.place(item, drop) }
    }

    /** Deletes a node from every view. */
    suspend fun deleteNodeEverywhere(nodeId: String) = lock.withLock { deleteNodes(setOf(nodeId), "Delete node") }

    /** Deletes a group and every node in it, from every view. */
    suspend fun deleteGroupWithNodes(viewId: String, groupId: String) = lock.withLock {
        val layout = ViewRows.load(board, viewId).toLayout()
        val nodes = if (groupId in layout.board.groups) layout.board.nodesOf(groupId)
        else requireNotNull(layout.tray.blockWithGroup(groupId)) { "unknown group $groupId" }.part(groupId).nodes.keys
        deleteNodes(nodes.toSet(), "Delete group")
    }

    private suspend fun deleteNodes(nodeIds: Set<String>, label: String) {
        val nodes = nodeIds.map { requireNotNull(projects.node(it)) { "unknown node $it" } }
        val changes = buildList {
            for (v in nodeIds.flatMap { board.viewsOfNode(it) }.distinct()) {
                val before = ViewRows.load(board, v)
                addAll(layoutChanges(before, before.withLayout(before.toLayout().without(nodeIds))))
            }
            nodeIds.forEach { n -> addAll(board.nodeTags(n).map { Change(it, null) }) }
            nodes.forEach { add(Change(it, null)) }
        }
        perform(nodes.first().projectId, ChangeSet(label, changes))
    }

    /** Copies a node (text, color, tags) as a new, independent node at the end of the tray. */
    suspend fun copyNode(viewId: String, nodeId: String): String = lock.withLock {
        val src = requireNotNull(projects.node(nodeId))
        val copy = src.copy(id = newId())
        val extra = listOf(Change(null, copy)) + board.nodeTags(nodeId).map { Change(null, it.copy(targetId = copy.id)) }
        layoutEdit(viewId, "Copy node", extra) { it.append(Block(mapOf(copy.id to 0))) }
        copy.id
    }

    /**
     * Copies a group with all its nodes and nested groups (labels, colors, tags, spacing) to the
     * end of the tray. Returns the new top group's id.
     */
    suspend fun copyGroup(viewId: String, groupId: String): String = lock.withLock {
        val rows = ViewRows.load(board, viewId)
        val layout = rows.toLayout()
        val src = if (groupId in layout.board.groups) layout.board.extract(Item.GroupItem(groupId)).second
        else requireNotNull(layout.tray.blockWithGroup(groupId)) { "unknown group $groupId" }.part(groupId)
        val nodeIds = src.nodes.keys.associateWith { newId() }
        val groupIds = src.groups.keys.associateWith { newId() }
        val block = Block(
            src.nodes.entries.associate { (n, off) -> nodeIds.getValue(n) to off },
            src.groups.entries.associate { (g, parent) -> groupIds.getValue(g) to parent?.let(groupIds::getValue) },
            src.membership.entries.associate { (n, g) -> nodeIds.getValue(n) to groupIds.getValue(g) },
        )
        val groupRows = rows.groups.associateBy { it.id }
        val newGroups = groupIds.map { (old, new) -> groupRows.getValue(old).copy(id = new, timelineId = null) }
        val extra = buildList {
            nodeIds.forEach { (old, new) ->
                add(Change(null, requireNotNull(projects.node(old)).copy(id = new)))
                board.nodeTags(old).forEach { add(Change(null, it.copy(targetId = new))) }
            }
            board.groupTags(groupIds.keys.toList()).forEach { add(Change(null, it.copy(targetId = groupIds.getValue(it.targetId)))) }
        }
        layoutEdit(viewId, "Copy group", extra, newGroups = newGroups) { it.append(block) }
        groupIds.getValue(groupId)
    }

    suspend fun move(viewId: String, item: Item, drop: Drop) = lock.withLock {
        boardEdit(viewId, "Move") { it.move(item, drop) }
    }

    suspend fun addMarker(viewId: String, label: String, beforeSlot: Int): String = lock.withLock {
        val m = MarkerEntity(newId(), viewId, beforeSlot, label)
        boardEdit(viewId, "Add marker", newMarkers = listOf(m)) { it.addMarker(m.id, beforeSlot) }
        m.id
    }

    suspend fun setMarkerLabel(viewId: String, markerId: String, label: String) = lock.withLock {
        val old = requireNotNull(board.markers(viewId).find { it.id == markerId })
        perform(projectOf(viewId), ChangeSet("Edit marker", listOf(Change(old, old.copy(label = label)))), "marker:$markerId")
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

    suspend fun setGroupLabel(viewId: String, groupId: String, label: String) = lock.withLock {
        val old = requireNotNull(board.groups(viewId).find { it.id == groupId })
        perform(projectOf(viewId), ChangeSet("Edit group", listOf(Change(old, old.copy(label = label)))), "group:$groupId")
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
                    // The tray is not imported; only what is on the board.
                    val all = ViewRows.load(board, from)
                    val src = all.copy(groups = all.groups.filter { it.timelineId != null })
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
            rows.tray.forEach { t ->
                add(Change(t, null))
                addAll(orphanDeletes(t.nodeId, viewId))
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

    // --- Tags and color (undoable; recorded on [projectId]'s undo stack) ---

    /** Something tags can be assigned to. */
    sealed interface TagTarget {
        val id: String
        data class Node(override val id: String) : TagTarget
        data class Marker(override val id: String) : TagTarget
        data class Group(override val id: String) : TagTarget
        data class Note(override val id: String) : TagTarget
    }

    /** Creates a tag owned by [worldId] if given, otherwise by the project. */
    suspend fun createTag(projectId: String, name: String, color: Int? = null, worldId: String? = null): String = lock.withLock {
        val tag = TagEntity(newId(), if (worldId == null) projectId else null, worldId, name, color)
        perform(projectId, ChangeSet.insert("Create tag", tag))
        tag.id
    }

    suspend fun updateTag(projectId: String, tag: TagEntity) = lock.withLock {
        require((tag.projectId == null) != (tag.worldId == null)) { "a tag has exactly one owner" }
        val old = requireNotNull(tags.tag(tag.id))
        perform(projectId, ChangeSet("Edit tag", listOf(Change(old, tag))), "tag:${tag.id}")
    }

    /** Deletes a tag and every assignment and filter entry that uses it. */
    suspend fun deleteTag(projectId: String, tagId: String) = lock.withLock {
        val tag = requireNotNull(tags.tag(tagId))
        val uses: List<Any> = tags.nodeTagsOf(tagId) + tags.markerTagsOf(tagId) + tags.groupTagsOf(tagId) +
            tags.noteTagsOf(tagId) + tags.hiddenOf(tagId)
        perform(projectId, ChangeSet("Delete tag", uses.map { Change(it, null) } + Change(tag, null)))
    }

    /** Replaces [target]'s tags with [tagIds], in order; the first is the primary tag. */
    suspend fun setTags(projectId: String, target: TagTarget, tagIds: List<String>) = lock.withLock {
        val ids = tagIds.distinct()
        val changes = when (target) {
            is TagTarget.Node -> diffRows(tags.nodeTags(target.id), ids.mapIndexed { i, t -> NodeTagEntity(target.id, t, i) }, ::rowKey)
            is TagTarget.Marker -> diffRows(tags.markerTags(target.id), ids.mapIndexed { i, t -> MarkerTagEntity(target.id, t, i) }, ::rowKey)
            is TagTarget.Group -> diffRows(tags.groupTags(target.id), ids.mapIndexed { i, t -> GroupTagEntity(target.id, t, i) }, ::rowKey)
            is TagTarget.Note -> diffRows(tags.noteTags(target.id), ids.mapIndexed { i, t -> NoteTagEntity(target.id, t, i) }, ::rowKey)
        }
        perform(projectId, ChangeSet("Change tags", changes))
    }

    /** Something with an override color. */
    sealed interface ColorTarget {
        data class Node(val id: String) : ColorTarget
        data class Marker(val viewId: String, val id: String) : ColorTarget
        data class Group(val viewId: String, val id: String) : ColorTarget
        data class Timeline(val id: String) : ColorTarget
    }

    /** Sets or clears (null) an item's override color. */
    suspend fun setColor(projectId: String, target: ColorTarget, color: Int?) = lock.withLock {
        val change = when (target) {
            is ColorTarget.Node -> requireNotNull(projects.node(target.id)).let { Change(it, it.copy(color = color)) }
            is ColorTarget.Marker -> requireNotNull(board.markers(target.viewId).find { it.id == target.id }).let { Change(it, it.copy(color = color)) }
            is ColorTarget.Group -> requireNotNull(board.groups(target.viewId).find { it.id == target.id }).let { Change(it, it.copy(color = color)) }
            is ColorTarget.Timeline -> requireNotNull(projects.timeline(target.id)).let { Change(it, it.copy(color = color)) }
        }
        perform(projectId, ChangeSet("Change color", listOf(change)))
    }

    /** Hides or shows items carrying [tagId] in one view. */
    suspend fun setTagHidden(viewId: String, tagId: String, hidden: Boolean) = lock.withLock {
        val row = ViewHiddenTagEntity(viewId, tagId)
        val exists = board.hiddenTags(viewId).any { it.tagId == tagId }
        val change = when {
            hidden && !exists -> Change(null, row)
            !hidden && exists -> Change(row, null)
            else -> return@withLock
        }
        perform(projectOf(viewId), ChangeSet(if (hidden) "Hide tag" else "Show tag", listOf(change)))
    }

    // --- World libraries ---

    /** Creates a world library linked to [projectId]. */
    suspend fun createWorld(projectId: String, name: String): String = lock.withLock {
        val world = WorldEntity(newId(), name)
        perform(projectId, ChangeSet.insert("Create world library", world, ProjectWorldEntity(projectId, world.id)))
        world.id
    }

    suspend fun renameWorld(projectId: String, worldId: String, name: String) = lock.withLock {
        val old = requireNotNull(worlds.world(worldId))
        perform(projectId, ChangeSet("Rename world library", listOf(Change(old, old.copy(name = name)))))
    }

    suspend fun linkWorld(projectId: String, worldId: String) = lock.withLock {
        if (worlds.links(worldId).any { it.projectId == projectId }) return@withLock
        perform(projectId, ChangeSet.insert("Link world library", ProjectWorldEntity(projectId, worldId)))
    }

    /**
     * Unlinks a world library from a project, removing the project's uses of the world's tags
     * (assignments on its nodes, markers and groups, and its views' filters).
     */
    suspend fun unlinkWorld(projectId: String, worldId: String) = lock.withLock {
        val link = worlds.links(worldId).find { it.projectId == projectId } ?: return@withLock
        val worldTags = tags.projectTags(projectId).filter { it.worldId == worldId }.map { it.id }.toSet()
        val viewIds = projects.views(projectId).map { it.id }
        val uses = buildList<Any> {
            for (v in viewIds) {
                addAll(tags.nodeTagsInView(v).filter { it.tagId in worldTags })
                addAll(tags.markerTagsInView(v).filter { it.tagId in worldTags })
                addAll(tags.groupTagsInView(v).filter { it.tagId in worldTags })
                addAll(board.hiddenTags(v).filter { it.tagId in worldTags })
            }
        }.distinct()
        // Project tags that point at the world's notes lose the reference.
        val noteIds = worlds.notes(worldId).map { it.id }
        val relinks = tags.projectTags(projectId).filter { it.projectId == projectId && it.linkedNoteId in noteIds }
            .map { Change(it, it.copy(linkedNoteId = null)) }
        perform(projectId, ChangeSet("Unlink world library", uses.map { Change(it, null) } + relinks + Change(link, null)))
    }

    /**
     * Permanently deletes a world library from every project, with its notes and tags. Not
     * undoable; all undo history is cleared because it may refer to the deleted rows.
     */
    suspend fun deleteWorld(worldId: String) = lock.withLock {
        db.withTransaction {
            worlds.unlinkTagsFromWorldNotes(worldId)
            worlds.deleteWorld(worldId)
        }
        clearAllUndo()
    }

    suspend fun createCategory(projectId: String, worldId: String, name: String, parentId: String? = null): String = lock.withLock {
        val c = CategoryEntity(newId(), worldId, parentId, name, worlds.categories(worldId).size)
        perform(projectId, ChangeSet.insert("Create category", c))
        c.id
    }

    suspend fun renameCategory(projectId: String, worldId: String, categoryId: String, name: String) = lock.withLock {
        val old = requireNotNull(worlds.categories(worldId).find { it.id == categoryId })
        perform(projectId, ChangeSet("Rename category", listOf(Change(old, old.copy(name = name)))))
    }

    /** Deletes a category; its notes and subcategories move up to its parent. */
    suspend fun deleteCategory(projectId: String, worldId: String, categoryId: String) = lock.withLock {
        val all = worlds.categories(worldId)
        val c = requireNotNull(all.find { it.id == categoryId })
        val changes = buildList {
            all.filter { it.parentId == categoryId }.forEach { add(Change(it, it.copy(parentId = c.parentId))) }
            worlds.notes(worldId).filter { it.categoryId == categoryId }.forEach { add(Change(it, it.copy(categoryId = c.parentId))) }
            add(Change(c, null))
        }
        perform(projectId, ChangeSet("Delete category", changes))
    }

    suspend fun createNote(projectId: String, worldId: String, title: String, categoryId: String? = null): String = lock.withLock {
        val n = NoteEntity(newId(), worldId, categoryId, title, "")
        perform(projectId, ChangeSet.insert("Create note", n))
        n.id
    }

    /** Edits a note's title, body or category; text edits coalesce per note and field. */
    suspend fun editNote(projectId: String, noteId: String, title: String? = null, body: String? = null, categoryId: String? = null, clearCategory: Boolean = false) =
        lock.withLock {
            val old = requireNotNull(worlds.note(noteId))
            val new = old.copy(
                title = title ?: old.title,
                body = body ?: old.body,
                categoryId = if (clearCategory) null else categoryId ?: old.categoryId,
            )
            val key = when {
                title != null -> "note-title:$noteId"
                body != null -> "note-body:$noteId"
                else -> null
            }
            perform(projectId, ChangeSet("Edit note", listOf(Change(old, new))), key)
        }

    /** Deletes a note with its links and tags; tags that referenced it lose the reference. */
    suspend fun deleteNote(projectId: String, noteId: String) = lock.withLock {
        val note = requireNotNull(worlds.note(noteId))
        val changes = buildList {
            worlds.linksOf(noteId).forEach { add(Change(it, null)) }
            worlds.noteTagsOf(listOf(noteId)).forEach { add(Change(it, null)) }
            worlds.tagsLinking(listOf(noteId)).forEach { add(Change(it, it.copy(linkedNoteId = null))) }
            add(Change(note, null))
        }
        perform(projectId, ChangeSet("Delete note", changes))
    }

    suspend fun linkNotes(projectId: String, fromNoteId: String, toNoteId: String) = lock.withLock {
        if (fromNoteId == toNoteId || worlds.linksOf(fromNoteId).any { it.fromNoteId == fromNoteId && it.toNoteId == toNoteId }) return@withLock
        perform(projectId, ChangeSet.insert("Link notes", NoteLinkEntity(fromNoteId, toNoteId)))
    }

    suspend fun unlinkNotes(projectId: String, fromNoteId: String, toNoteId: String) = lock.withLock {
        val link = worlds.linksOf(fromNoteId).find { it.fromNoteId == fromNoteId && it.toNoteId == toNoteId } ?: return@withLock
        perform(projectId, ChangeSet("Unlink notes", listOf(Change(link, null))))
    }
}
