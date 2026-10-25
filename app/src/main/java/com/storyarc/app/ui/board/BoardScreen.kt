package com.storyarc.app.ui.board

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.ui.unit.Dp
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
    // A new node from the add button: edited in a panel with its card floating above, then
    // floating over the board until dragged somewhere.
    var floatingId by rememberSaveable { mutableStateOf<String?>(null) }
    var editingFloating by rememberSaveable { mutableStateOf(false) }
    var trayExpanded by rememberSaveable { mutableStateOf(true) }
    val views by repo.projects.observeViews(projectId).collectAsStateWithLifecycle(emptyList())
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun notify(message: String, undoable: Boolean) {
        scope.launch {
            snackbar.currentSnackbarData?.dismiss()
            val result = snackbar.showSnackbar(message, actionLabel = if (undoable) "Undo" else null, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) vm.undo()
        }
    }
    // The floating node is gone (undone, deleted, or placed from elsewhere). It counts as gone
    // only once the board has shown it, since the board reloads after the node is created.
    var floatingSeen by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(ui, floatingId) {
        val u = ui ?: return@LaunchedEffect
        val id = floatingId ?: return@LaunchedEffect
        val present = u.tray.any { it.root == null && id in it.nodes }
        if (present) floatingSeen = true
        else if (floatingSeen) { floatingId = null; editingFloating = false; floatingSeen = false }
    }
    // While the blank check runs, the node is neither edited nor drawn floating.
    var finishing by remember { mutableStateOf(false) }
    fun finishFloating() {
        val id = floatingId ?: return
        editingFloating = false
        finishing = true
        vm.finishNewNode(id) { discarded ->
            finishing = false
            if (discarded && floatingId == id) floatingId = null
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
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
    ) { padding ->
        val current = ui
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            // The tray takes the bottom edge in portrait and the right edge in landscape.
            val traySide = maxWidth > maxHeight
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
                    onDrop = { item, target, fromTray ->
                        val engineItem = item.item
                        when (target) {
                            is DragTarget.Place -> engineItem?.let { if (fromTray) vm.placeFromTray(it, target.drop) else vm.move(it, target.drop) }
                            is DragTarget.MarkerAt -> if (item is DragItem.Marker) vm.moveMarker(item.id, target.beforeSlot)
                            is DragTarget.Tray -> engineItem?.let { vm.moveToTray(it, target.index) }
                            DragTarget.Trash -> {
                                when (item) {
                                    is DragItem.Node -> vm.deleteNodeEverywhere(item.id)
                                    is DragItem.Group -> vm.deleteGroupWithNodes(item.id)
                                    is DragItem.Marker -> vm.removeMarker(item.id)
                                }
                                notify("Deleted", undoable = true)
                            }
                            DragTarget.None -> {}
                        }
                        if (target != DragTarget.None && item is DragItem.Node && item.id == floatingId) floatingId = null
                    },
                    selected = selection.orEmpty().toSet(),
                    vertical = settings.verticalBoard,
                    traySide = traySide,
                    trayExpanded = trayExpanded,
                    floating = floatingId.takeIf { !editingFloating && !finishing },
                    trayHidden = setOfNotNull(floatingId),
                    onTap = { hit ->
                        val sel = selection
                        if (sel != null) {
                            if (hit is BoardHit.Node && current.isPlaced(hit.nodeId)) selection = if (hit.nodeId in sel) sel - hit.nodeId else sel + hit.nodeId
                            if (hit == BoardHit.TrayToggle) trayExpanded = !trayExpanded
                            return@BoardCanvas
                        }
                        when (hit) {
                            is BoardHit.Node -> editingNode = hit.nodeId
                            is BoardHit.Marker -> editingMarker = hit.markerId
                            is BoardHit.Empty -> vm.addNode(Drop.Onto(hit.timelineId, hit.slot)) { editingNode = it }
                            is BoardHit.Timeline -> onTimelines()
                            is BoardHit.Group -> editingGroup = hit.groupId
                            is BoardHit.Floating -> editingFloating = true
                            BoardHit.TrayToggle -> trayExpanded = !trayExpanded
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                )
            }
            if (current != null && current.timelines.isNotEmpty() && selection == null && !editingFloating) {
                val tray = TrayDims.thickness(traySide, trayExpanded)
                FloatingActionButton(
                    onClick = {
                        vm.addNodeToTray { id -> floatingId = id; floatingSeen = false; editingFloating = true }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(end = 16.dp + if (traySide) tray else 0.dp, bottom = 16.dp + if (traySide) 0.dp else tray),
                ) { Icon(Icons.Default.Add, "Add node") }
            }
            val editing = floatingId
            if (current != null && editing != null && editingFloating && current.nodes[editing] != null) {
                BackHandler { finishFloating() }
                FloatingEditor(
                    vm, current, editing, maxHeight,
                    onDone = { finishFloating() },
                    onOpenNote = { finishFloating(); onOpenNote(it) },
                    modifier = Modifier.align(Alignment.BottomCenter),
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
                onCopied = { notify("Copied to the tray", undoable = false) },
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
            GroupSheet(
                vm, current, group.id,
                onDismiss = { editingGroup = null },
                onOpenNote = { editingGroup = null; onOpenNote(it) },
                onCopied = { notify("Copied to the tray", undoable = false) },
            )
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

/**
 * Editor for a new node: a panel along the bottom with the node's card floating above it.
 * When done, the card stays floating over the board until dragged.
 */
@Composable
private fun FloatingEditor(
    vm: BoardViewModel,
    ui: BoardUi,
    nodeId: String,
    maxHeight: Dp,
    onDone: () -> Unit,
    onOpenNote: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val node = ui.nodes[nodeId] ?: return
    // The card shows what has been typed, ahead of the saved text.
    var typed by remember(nodeId) { mutableStateOf<String?>(null) }
    Column(modifier.fillMaxWidth().imePadding()) {
        NodeCard(
            typed?.let { node.copy(text = it) } ?: node,
            color = ui.nodeColor(nodeId),
            tags = ui.nodeTags[nodeId].orEmpty().mapNotNull { ui.tags[it] },
            lifted = true,
            modifier = Modifier
                .align(Alignment.CenterHorizontally)
                .padding(bottom = 12.dp)
                .size(BoardDims.slot(false), BoardDims.row(false))
                .padding(BoardDims.cardPadding),
        )
        Surface(
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            tonalElevation = 2.dp,
            shadowElevation = 8.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier
                    .heightIn(max = maxHeight * 0.6f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp)
                    .padding(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("New node", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f).padding(top = 8.dp))
                    TextButton(onClick = onDone) { Text("Done") }
                }
                NodeFields(vm, nodeId, ui, onOpenNote, autoFocus = true, onTextChange = { typed = it })
                Text(
                    "When you're done, drag the card onto the board or into the tray.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
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
