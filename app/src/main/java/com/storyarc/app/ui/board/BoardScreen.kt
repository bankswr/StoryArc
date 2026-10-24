package com.storyarc.app.ui.board

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.foundation.clickable
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FilterList
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.storyarc.app.ui.common.AppAlertDialog
import com.storyarc.app.ui.common.ConfirmDialog
import com.storyarc.app.ui.common.TextInputDialog
import com.storyarc.app.ui.common.UndoRedoActions
import com.storyarc.app.ui.common.appContainer
import com.storyarc.board.Drop
import com.storyarc.board.Item
import com.storyarc.board.groupingProblem
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoardScreen(
    projectId: String,
    viewId: String,
    onBack: () -> Unit,
    onTimelines: () -> Unit,
    onSwitchView: (String) -> Unit = {},
    onOpenNote: (String) -> Unit = {},
) {
    val repo = appContainer().repository
    val vm = viewModel(key = viewId) { BoardViewModel(repo, projectId, viewId) }
    val ui by vm.ui.collectAsStateWithLifecycle()
    val undo by vm.undo.collectAsStateWithLifecycle()
    val viewport = rememberBoardViewport()
    val settings by appContainer().settings.collectAsStateWithLifecycle()
    var editingNode by rememberSaveable { mutableStateOf<String?>(null) }
    var editingMarker by rememberSaveable { mutableStateOf<String?>(null) }
    var addingMarker by rememberSaveable { mutableStateOf(false) }
    var editingGroup by rememberSaveable { mutableStateOf<String?>(null) }
    // Selection mode: taps toggle nodes instead of opening them.
    var selection by rememberSaveable { mutableStateOf<List<String>?>(null) }
    var namingGroup by rememberSaveable { mutableStateOf(false) }
    var filtering by rememberSaveable { mutableStateOf(false) }
    val views by repo.projects.observeViews(projectId).collectAsStateWithLifecycle(emptyList())

    Scaffold(
        topBar = {
            val sel = selection
            if (sel != null) {
                val problem = ui?.board?.groupingProblem(sel)?.takeIf { sel.isNotEmpty() }
                TopAppBar(
                    title = {
                        Text(
                            "${sel.size} · " + (problem ?: if (sel.isEmpty()) "Tap adjacent nodes on one timeline" else "Ready to group"),
                            style = MaterialTheme.typography.titleSmall,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    },
                    navigationIcon = { IconButton(onClick = { selection = null }) { Icon(Icons.Default.Close, "Cancel selection") } },
                    actions = {
                        TextButton(onClick = { namingGroup = true }, enabled = sel.isNotEmpty() && problem == null) { Text("Group") }
                    },
                )
            } else TopAppBar(
                title = { ViewSwitcher(ui?.view?.name.orEmpty(), views, viewId, onSwitchView) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    UndoRedoActions(undo, vm::undo, vm::redo)
                    var more by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { more = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(more, onDismissRequest = { more = false }) {
                            val hasTimelines = ui?.timelines?.isNotEmpty() == true
                            DropdownMenuItem(
                                text = { Text("Add marker") },
                                leadingIcon = { Icon(Icons.Default.Bookmark, null) },
                                enabled = hasTimelines,
                                onClick = { more = false; addingMarker = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Select nodes") },
                                leadingIcon = { Icon(Icons.Default.Checklist, null) },
                                enabled = ui?.board?.placements?.isNotEmpty() == true,
                                onClick = { more = false; selection = emptyList() },
                            )
                            DropdownMenuItem(
                                text = { Text(if (ui?.hiddenTags.isNullOrEmpty()) "Filter tags" else "Filter tags (active)") },
                                leadingIcon = { Icon(Icons.Default.FilterList, null) },
                                onClick = { more = false; filtering = true },
                            )
                            DropdownMenuItem(
                                text = { Text("Timelines") },
                                leadingIcon = { Icon(Icons.AutoMirrored.Filled.List, null) },
                                onClick = { more = false; onTimelines() },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            val current = ui
            if (current != null && current.timelines.isNotEmpty() && selection == null) {
                FloatingActionButton(onClick = {
                    val t = current.timelines.first().id
                    vm.addNode(Drop.Onto(t, current.board.slotCount)) { editingNode = it }
                }) { Icon(Icons.Default.Add, "Add node") }
            }
        },
    ) { padding ->
        val current = ui
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                current == null -> {}
                current.timelines.isEmpty() -> Column(
                    Modifier.align(Alignment.Center).padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("This view has no timelines yet.", style = MaterialTheme.typography.bodyLarge)
                    Button(onClick = onTimelines) { Text("Add timelines") }
                }
                else -> BoardCanvas(
                    current,
                    viewport,
                    barrier = BarrierConfig(settings.barrierDelayEnabled, settings.barrierDelayMs),
                    onDrop = { item, target ->
                        when {
                            item is DragItem.Node && target is DragTarget.Place -> vm.move(Item.Node(item.id), target.drop)
                            item is DragItem.Group && target is DragTarget.Place -> vm.move(Item.GroupItem(item.id), target.drop)
                            item is DragItem.Marker && target is DragTarget.MarkerAt -> vm.moveMarker(item.id, target.beforeSlot)
                        }
                    },
                    selected = selection.orEmpty().toSet(),
                    onTap = { hit ->
                        val sel = selection
                        if (sel != null) {
                            if (hit is BoardHit.Node) selection = if (hit.nodeId in sel) sel - hit.nodeId else sel + hit.nodeId
                            return@BoardCanvas
                        }
                        when (hit) {
                            is BoardHit.Node -> editingNode = hit.nodeId
                            is BoardHit.Marker -> editingMarker = hit.markerId
                            is BoardHit.Empty -> vm.addNode(Drop.Onto(hit.timelineId, hit.slot)) { editingNode = it }
                            is BoardHit.Timeline -> onTimelines()
                            is BoardHit.Group -> editingGroup = hit.groupId
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }

    val current = ui
    editingNode?.let { id ->
        val node = current?.nodes?.get(id)
        if (current != null && node == null) {
            LaunchedEffect(id) { editingNode = null }
        } else if (node != null) {
            NodeEditorSheet(
                vm, node.id, current,
                onDismiss = { editingNode = null },
                onSelect = { editingNode = null; selection = listOf(it) },
                onOpenNote = { editingNode = null; onOpenNote(it) },
            )
        }
    }
    editingMarker?.let { id ->
        val marker = current?.markers?.get(id)
        if (marker == null) {
            LaunchedEffect(id) { editingMarker = null }
        } else {
            MarkerSheet(vm, current, marker.id, onDismiss = { editingMarker = null }, onOpenNote = { editingMarker = null; onOpenNote(it) })
        }
    }
    if (namingGroup) {
        TextInputDialog("Group name", confirmLabel = "Group", allowBlank = true, onDismiss = { namingGroup = false }) { label ->
            namingGroup = false
            selection?.let { vm.createGroup(it, label) }
            selection = null
        }
    }
    editingGroup?.let { id ->
        val group = current?.groups?.get(id)
        if (group == null) {
            LaunchedEffect(id) { editingGroup = null }
        } else {
            GroupSheet(vm, current, group.id, onDismiss = { editingGroup = null }, onOpenNote = { editingGroup = null; onOpenNote(it) })
        }
    }
    if (filtering && current != null) {
        FilterSheet(vm, current, onDismiss = { filtering = false })
    }
    if (addingMarker && current != null) {
        TextInputDialog("New marker", confirmLabel = "Add", onDismiss = { addingMarker = false }) { label ->
            addingMarker = false
            // Insert before the first slot visible at the left edge of the board.
            vm.addMarker(label, viewport.firstVisibleSlot)
        }
    }
}

/** The view name as a title that opens a menu of the project's other views. */
@Composable
private fun ViewSwitcher(name: String, views: List<com.storyarc.app.data.db.ViewEntity>, current: String, onSwitch: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier.clickable(enabled = views.size > 1, onClickLabel = "Switch view") { open = true },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (views.size > 1) Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            views.forEach { v ->
                DropdownMenuItem(
                    text = { Text(v.name) },
                    enabled = v.id != current,
                    onClick = { open = false; onSwitch(v.id) },
                )
            }
        }
    }
}
