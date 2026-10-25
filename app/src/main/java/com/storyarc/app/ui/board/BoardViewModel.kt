package com.storyarc.app.ui.board

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storyarc.app.data.db.MarkerEntity
import com.storyarc.app.data.db.NodeEntity
import com.storyarc.app.data.db.NodeGroupEntity
import com.storyarc.app.data.db.TagEntity
import com.storyarc.app.data.db.TimelineEntity
import com.storyarc.app.data.db.ViewEntity
import com.storyarc.app.data.repo.StoryRepository
import com.storyarc.app.data.repo.StoryRepository.ColorTarget
import com.storyarc.app.data.repo.StoryRepository.TagTarget
import com.storyarc.app.data.repo.ViewRows
import com.storyarc.board.Block
import com.storyarc.board.BoardState
import com.storyarc.board.Drop
import com.storyarc.board.Item
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Everything the board screen draws for one view. Tag lists are ordered, primary first.
 * [nodes] and [groups] include the tray's; [tray] lists its items in order.
 */
data class BoardUi(
    val view: ViewEntity,
    val board: BoardState,
    val timelines: List<TimelineEntity>,
    val nodes: Map<String, NodeEntity>,
    val markers: Map<String, MarkerEntity>,
    val groups: Map<String, NodeGroupEntity>,
    val tags: Map<String, TagEntity> = emptyMap(),
    val nodeTags: Map<String, List<String>> = emptyMap(),
    val markerTags: Map<String, List<String>> = emptyMap(),
    val groupTags: Map<String, List<String>> = emptyMap(),
    val hiddenTags: Set<String> = emptySet(),
    val tray: List<Block> = emptyList(),
) {
    fun isPlaced(nodeId: String) = nodeId in board.placements

    fun isOnBoard(groupId: String) = groupId in board.groups

    /** The item's own color, else its primary tag's color, else null (theme default). */
    fun colorOf(own: Int?, tagIds: List<String>?): Int? = own ?: tagIds?.firstOrNull()?.let { tags[it]?.color }

    fun nodeColor(id: String) = colorOf(nodes[id]?.color, nodeTags[id])
    fun markerColor(id: String) = colorOf(markers[id]?.color, markerTags[id])
    fun groupColor(id: String) = colorOf(groups[id]?.color, groupTags[id])

    /** Filtered out by the view's hidden tags (drawn faintly; structure is unaffected). */
    fun isHidden(tagIds: List<String>?) = hiddenTags.isNotEmpty() && tagIds.orEmpty().any { it in hiddenTags }
}

@OptIn(ExperimentalCoroutinesApi::class)
class BoardViewModel(
    private val repo: StoryRepository,
    val projectId: String,
    val viewId: String,
) : ViewModel() {
    val undo = repo.undoManager(projectId).state

    val ui: StateFlow<BoardUi?> = repo.board.observeViewChanges(viewId)
        .mapLatest { load() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val projectTags = repo.tags.observeProjectTags(projectId)
    val projectNotes = repo.worlds.observeProjectNotes(projectId)

    private suspend fun load(): BoardUi? {
        val view = repo.projects.view(viewId) ?: return null
        val rows = ViewRows.load(repo.board, viewId)
        val timelines = repo.board.timelinesInView(viewId).associateBy { it.id }
        fun <T> List<T>.ordered(target: (T) -> String, tag: (T) -> String, ordinal: (T) -> Int) =
            groupBy(target).mapValues { (_, v) -> v.sortedBy(ordinal).map(tag) }
        return BoardUi(
            view = view,
            board = rows.toBoard(),
            tray = rows.toTray(),
            timelines = rows.timelines.sortedBy { it.ordinal }.mapNotNull { timelines[it.timelineId] },
            nodes = repo.board.nodesInView(viewId).associateBy { it.id },
            markers = rows.markers.associateBy { it.id },
            groups = rows.groups.associateBy { it.id },
            tags = repo.tags.projectTags(projectId).associateBy { it.id },
            nodeTags = repo.tags.nodeTagsInView(viewId).ordered({ it.targetId }, { it.tagId }, { it.ordinal }),
            markerTags = repo.tags.markerTagsInView(viewId).ordered({ it.targetId }, { it.tagId }, { it.ordinal }),
            groupTags = repo.tags.groupTagsInView(viewId).ordered({ it.targetId }, { it.tagId }, { it.ordinal }),
            hiddenTags = repo.board.hiddenTags(viewId).map { it.tagId }.toSet(),
        )
    }

    private fun launch(block: suspend StoryRepository.() -> Unit) {
        viewModelScope.launch { repo.block() }
    }

    fun undo() = launch { undo(projectId) }
    fun redo() = launch { redo(projectId) }

    /** Creates an empty node at [drop] and reports its id so the editor can open. */
    fun addNode(drop: Drop, onCreated: (String) -> Unit) = launch { onCreated(addNode(viewId, "", drop)) }

    fun editNodeText(nodeId: String, text: String) = launch { setNodeText(nodeId, text) }

    /** Starts a fresh undo step for the next edit (e.g. when an editor opens). */
    fun breakCoalescing() = repo.undoManager(projectId).breakCoalescing()

    fun move(item: Item, drop: Drop) = launch { move(viewId, item, drop) }

    /** Creates an empty node in the tray and reports its id. */
    fun addNodeToTray(onCreated: (String) -> Unit) = launch { onCreated(addNodeToTray(viewId)) }

    fun discardNewNode(nodeId: String) = launch { discardNewNode(viewId, nodeId) }

    /** Discards the new node if it is still blank, then reports whether it was discarded. */
    fun finishNewNode(nodeId: String, onDone: (Boolean) -> Unit) = launch { onDone(discardNewNodeIfBlank(viewId, nodeId)) }

    fun moveToTray(item: Item, index: Int? = null) = launch { moveToTray(viewId, item, index) }

    fun placeFromTray(item: Item, drop: Drop) = launch { placeFromTray(viewId, item, drop) }

    fun copyNode(nodeId: String) = launch { copyNode(viewId, nodeId) }

    fun copyGroup(groupId: String) = launch { copyGroup(viewId, groupId) }

    fun deleteGroupWithNodes(groupId: String) = launch { deleteGroupWithNodes(viewId, groupId) }

    fun removeNodeFromView(nodeId: String) = launch { removeNodeFromView(viewId, nodeId) }

    fun deleteNodeEverywhere(nodeId: String) = launch { deleteNodeEverywhere(nodeId) }

    suspend fun isLastPlacement(nodeId: String) = repo.isLastPlacement(viewId, nodeId)

    fun addMarker(label: String, beforeSlot: Int) = launch { addMarker(viewId, label, beforeSlot) }

    fun editMarker(markerId: String, label: String) = launch { setMarkerLabel(viewId, markerId, label) }

    fun moveMarker(markerId: String, beforeSlot: Int) = launch { moveMarker(viewId, markerId, beforeSlot) }

    fun removeMarker(markerId: String) = launch { removeMarker(viewId, markerId) }

    fun createGroup(nodeIds: List<String>, label: String) = launch { createGroup(viewId, nodeIds, label) }

    fun editGroup(groupId: String, label: String) = launch { setGroupLabel(viewId, groupId, label) }

    fun ungroup(groupId: String) = launch { ungroup(viewId, groupId) }

    fun setTags(target: TagTarget, tagIds: List<String>) = launch { setTags(projectId, target, tagIds) }

    fun setColor(target: ColorTarget, color: Int?) = launch { setColor(projectId, target, color) }

    /** Creates a project tag and reports its id. */
    fun createTag(name: String, onCreated: (String) -> Unit) = launch { onCreated(createTag(projectId, name)) }

    fun setTagHidden(tagId: String, hidden: Boolean) = launch { setTagHidden(viewId, tagId, hidden) }
}
