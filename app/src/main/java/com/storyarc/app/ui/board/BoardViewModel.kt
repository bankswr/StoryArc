package com.storyarc.app.ui.board

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.storyarc.app.data.db.MarkerEntity
import com.storyarc.app.data.db.NodeEntity
import com.storyarc.app.data.db.NodeGroupEntity
import com.storyarc.app.data.db.TimelineEntity
import com.storyarc.app.data.db.ViewEntity
import com.storyarc.app.data.repo.StoryRepository
import com.storyarc.app.data.repo.ViewRows
import com.storyarc.board.BoardState
import com.storyarc.board.Drop
import com.storyarc.board.Item
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Everything the board screen draws for one view. */
data class BoardUi(
    val view: ViewEntity,
    val board: BoardState,
    val timelines: List<TimelineEntity>,
    val nodes: Map<String, NodeEntity>,
    val markers: Map<String, MarkerEntity>,
    val groups: Map<String, NodeGroupEntity>,
)

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

    private suspend fun load(): BoardUi? {
        val view = repo.projects.view(viewId) ?: return null
        val rows = ViewRows.load(repo.board, viewId)
        val timelines = repo.board.timelinesInView(viewId).associateBy { it.id }
        return BoardUi(
            view = view,
            board = rows.toBoard(),
            timelines = rows.timelines.sortedBy { it.ordinal }.mapNotNull { timelines[it.timelineId] },
            nodes = repo.board.nodesInView(viewId).associateBy { it.id },
            markers = rows.markers.associateBy { it.id },
            groups = rows.groups.associateBy { it.id },
        )
    }

    private fun launch(block: suspend StoryRepository.() -> Unit) {
        viewModelScope.launch { repo.block() }
    }

    fun undo() = launch { undo(projectId) }
    fun redo() = launch { redo(projectId) }

    /** Creates an empty node at [drop] and reports its id so the editor can open. */
    fun addNode(drop: Drop, onCreated: (String) -> Unit) = launch { onCreated(addNode(viewId, "", drop)) }

    fun editNodeText(node: NodeEntity, text: String) = launch { updateNode(node.copy(text = text)) }

    /** Starts a fresh undo step for the next edit (e.g. when an editor opens). */
    fun breakCoalescing() = repo.undoManager(projectId).breakCoalescing()

    fun move(item: Item, drop: Drop) = launch { move(viewId, item, drop) }

    fun removeNodeFromView(nodeId: String) = launch { removeNodeFromView(viewId, nodeId) }

    fun deleteNodeEverywhere(nodeId: String) = launch { deleteNodeEverywhere(nodeId) }

    suspend fun isLastPlacement(nodeId: String) = repo.isLastPlacement(viewId, nodeId)

    fun addMarker(label: String, beforeSlot: Int) = launch { addMarker(viewId, label, beforeSlot) }

    fun editMarker(marker: MarkerEntity, label: String) = launch { updateMarker(marker.copy(label = label)) }

    fun moveMarker(markerId: String, beforeSlot: Int) = launch { moveMarker(viewId, markerId, beforeSlot) }

    fun removeMarker(markerId: String) = launch { removeMarker(viewId, markerId) }

    fun createGroup(nodeIds: List<String>, label: String) = launch { createGroup(viewId, nodeIds, label) }

    fun editGroup(group: NodeGroupEntity, label: String) = launch { updateGroup(group.copy(label = label)) }

    fun ungroup(groupId: String) = launch { ungroup(viewId, groupId) }
}
