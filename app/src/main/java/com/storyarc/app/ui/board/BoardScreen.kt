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
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Close
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
fun BoardScreen(projectId: String, viewId: String, onBack: () -> Unit, onTimelines: () -> Unit) {
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
                title = { Text(ui?.view?.name ?: "") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    UndoRedoActions(undo, vm::undo, vm::redo)
                    IconButton(onClick = { addingMarker = true }, enabled = ui?.timelines?.isNotEmpty() == true) {
                        Icon(Icons.Default.Bookmark, "Add marker")
                    }
                    IconButton(onClick = { selection = emptyList() }, enabled = ui?.board?.placements?.isNotEmpty() == true) {
                        Icon(Icons.Default.Checklist, "Select nodes")
                    }
                    IconButton(onClick = onTimelines) { Icon(Icons.AutoMirrored.Filled.List, "Timelines") }
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
            NodeEditorSheet(vm, node.id, current, onDismiss = { editingNode = null }, onSelect = { editingNode = null; selection = listOf(it) })
        }
    }
    editingMarker?.let { id ->
        val marker = current?.markers?.get(id)
        if (marker == null) {
            LaunchedEffect(id) { editingMarker = null }
        } else {
            MarkerDialog(
                label = marker.label,
                onDismiss = { editingMarker = null },
                onSave = { vm.editMarker(marker, it); editingMarker = null },
                onDelete = { vm.removeMarker(marker.id); editingMarker = null },
            )
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
            GroupDialog(
                label = group.label,
                onDismiss = { editingGroup = null },
                onSave = { vm.editGroup(group, it); editingGroup = null },
                onUngroup = { vm.ungroup(group.id); editingGroup = null },
            )
        }
    }
    if (addingMarker && current != null) {
        TextInputDialog("New marker", confirmLabel = "Add", onDismiss = { addingMarker = false }) { label ->
            addingMarker = false
            // Insert before the first slot visible at the left edge of the board.
            vm.addMarker(label, viewport.firstVisibleSlot)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NodeEditorSheet(vm: BoardViewModel, nodeId: String, ui: BoardUi, onDismiss: () -> Unit, onSelect: (String) -> Unit) {
    val node = ui.nodes.getValue(nodeId)
    var text by remember(nodeId) { mutableStateOf(node.text) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmLastRemove by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(nodeId) { vm.breakCoalescing() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; vm.editNodeText(node, it) },
                label = { Text("Note") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        if (vm.isLastPlacement(nodeId)) confirmLastRemove = true else { vm.removeNodeFromView(nodeId); onDismiss() }
                    }
                }) { Text("Remove from view") }
                TextButton(onClick = { confirmDelete = true }) { Text("Delete everywhere") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = { onSelect(nodeId) }) { Text("Select for grouping") }
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog("Delete node?", "The node is removed from every view. You can undo this.", "Delete", onDismiss = { confirmDelete = false }) {
            vm.deleteNodeEverywhere(nodeId); onDismiss()
        }
    }
    if (confirmLastRemove) {
        ConfirmDialog(
            "Delete node?",
            "This view is the only one using this node, so removing it deletes it. You can undo this.",
            "Delete",
            onDismiss = { confirmLastRemove = false },
        ) { vm.removeNodeFromView(nodeId); onDismiss() }
    }
}

@Composable
private fun MarkerDialog(label: String, onDismiss: () -> Unit, onSave: (String) -> Unit, onDelete: () -> Unit) {
    var value by remember { mutableStateOf(label) }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Marker") },
        text = { OutlinedTextField(value, { value = it }, singleLine = true, label = { Text("Label") }, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onSave(value.trim()) }, enabled = value.isNotBlank()) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = onDelete) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun GroupDialog(label: String, onDismiss: () -> Unit, onSave: (String) -> Unit, onUngroup: () -> Unit) {
    var value by remember { mutableStateOf(label) }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Group") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(value, { value = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                Text("Long-press the group's bar to move the whole group.", style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = { TextButton(onClick = { onSave(value.trim()) }) { Text("Save") } },
        dismissButton = {
            Row {
                TextButton(onClick = onUngroup) { Text("Ungroup") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}
