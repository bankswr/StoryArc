package com.storyarc.app.ui.board

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material3.Icon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import com.storyarc.board.Item
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.storyarc.app.data.repo.StoryRepository.ColorTarget
import com.storyarc.app.data.repo.StoryRepository.TagTarget
import com.storyarc.app.ui.common.ColorPicker
import com.storyarc.app.ui.common.ConfirmDialog
import com.storyarc.app.ui.common.EditorSection
import com.storyarc.app.ui.common.TagChips
import com.storyarc.app.ui.common.TagDot
import com.storyarc.app.ui.common.TagPickerDialog
import kotlinx.coroutines.launch

/** Tags (with picker) and override color for one board item. */
@Composable
private fun TagsAndColor(
    vm: BoardViewModel,
    ui: BoardUi,
    assigned: List<String>,
    color: Int?,
    target: TagTarget,
    colorTarget: ColorTarget,
    onOpenNote: (String) -> Unit,
) {
    val all by vm.projectTags.collectAsStateWithLifecycle(emptyList())
    var picking by remember { mutableStateOf(false) }
    EditorSection("Tags") { TagChips(assigned, ui.tags, onEdit = { picking = true }) }
    val references = assigned.mapNotNull { ui.tags[it]?.linkedNoteId }.distinct()
    if (references.isNotEmpty()) {
        val notes by vm.projectNotes.collectAsStateWithLifecycle(emptyList())
        EditorSection("References") {
            references.forEach { id ->
                val note = notes.find { it.id == id } ?: return@forEach
                ListItem(
                    headlineContent = { Text(note.title) },
                    supportingContent = note.body.takeIf { it.isNotBlank() }?.let { b -> { Text(b, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.Notes, null) },
                    modifier = Modifier.clickable { onOpenNote(id) },
                )
            }
        }
    }
    EditorSection(if (color == null && assigned.isNotEmpty()) "Color (from primary tag)" else "Color") {
        ColorPicker(color, { vm.setColor(colorTarget, it) }, noneLabel = "Use primary tag color")
    }
    if (picking) {
        TagPickerDialog(
            all,
            assigned,
            onDismiss = { picking = false },
            onCreate = { name, done -> vm.createTag(name, done) },
            onConfirm = { vm.setTags(target, it); picking = false },
        )
    }
}

/**
 * A node's text, tags and color. With [autoFocus] the text field takes focus (and the keyboard
 * opens). [onTextChange] sees each edit before it is saved.
 */
@Composable
fun NodeFields(
    vm: BoardViewModel,
    nodeId: String,
    ui: BoardUi,
    onOpenNote: (String) -> Unit,
    autoFocus: Boolean = false,
    onTextChange: (String) -> Unit = {},
) {
    val node = ui.nodes[nodeId] ?: return
    var text by remember(nodeId) { mutableStateOf(node.text) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(nodeId) {
        vm.breakCoalescing()
        if (autoFocus) focus.requestFocus()
    }
    OutlinedTextField(
        value = text,
        onValueChange = { text = it; onTextChange(it); vm.editNodeText(nodeId, it) },
        label = { Text("Note") },
        modifier = Modifier.fillMaxWidth().heightIn(min = 120.dp).focusRequester(focus),
    )
    TagsAndColor(vm, ui, ui.nodeTags[nodeId].orEmpty(), node.color, TagTarget.Node(nodeId), ColorTarget.Node(nodeId), onOpenNote)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun NodeEditorSheet(
    vm: BoardViewModel,
    nodeId: String,
    ui: BoardUi,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onOpenNote: (String) -> Unit,
    onCopied: () -> Unit = {},
) {
    var confirmDelete by remember { mutableStateOf(false) }
    val placed = ui.isPlaced(nodeId)
    // Whether another view also holds the node, so removing it here does not delete it.
    var shared by remember(nodeId) { mutableStateOf(false) }
    LaunchedEffect(nodeId) { shared = !vm.isLastPlacement(nodeId) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (!placed) Text("In the tray. Long-press it there to place it on the board.", style = MaterialTheme.typography.bodySmall)
            NodeFields(vm, nodeId, ui, onOpenNote)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.copyNode(nodeId); onCopied(); onDismiss() }) { Text("Copy") }
                if (placed) {
                    OutlinedButton(onClick = { vm.moveToTray(Item.Node(nodeId)); onDismiss() }) { Text("Move to tray") }
                } else if (shared) {
                    OutlinedButton(onClick = { vm.removeNodeFromView(nodeId); onDismiss() }) { Text("Remove from view") }
                }
                TextButton(onClick = { confirmDelete = true }) { Text("Delete everywhere") }
            }
            if (placed) TextButton(onClick = { onSelect(nodeId) }) { Text("Select for grouping") }
        }
    }
    if (confirmDelete) {
        ConfirmDialog("Delete node?", "The node is removed from every view. You can undo this.", "Delete", onDismiss = { confirmDelete = false }) {
            vm.deleteNodeEverywhere(nodeId); onDismiss()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarkerSheet(vm: BoardViewModel, ui: BoardUi, markerId: String, onDismiss: () -> Unit, onOpenNote: (String) -> Unit) {
    val marker = ui.markers.getValue(markerId)
    var label by remember(markerId) { mutableStateOf(marker.label) }
    LaunchedEffect(markerId) { vm.breakCoalescing() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                label,
                { label = it; if (it.isNotBlank()) vm.editMarker(markerId, it.trim()) },
                singleLine = true,
                label = { Text("Marker label") },
                modifier = Modifier.fillMaxWidth(),
            )
            TagsAndColor(vm, ui, ui.markerTags[markerId].orEmpty(), marker.color, TagTarget.Marker(markerId), ColorTarget.Marker(ui.view.id, markerId), onOpenNote)
            Text("Long-press the marker to move it.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { vm.removeMarker(markerId); onDismiss() }) { Text("Delete marker") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GroupSheet(
    vm: BoardViewModel,
    ui: BoardUi,
    groupId: String,
    onDismiss: () -> Unit,
    onOpenNote: (String) -> Unit,
    onCopied: () -> Unit = {},
) {
    val group = ui.groups.getValue(groupId)
    val onBoard = ui.isOnBoard(groupId)
    var label by remember(groupId) { mutableStateOf(group.label) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(groupId) { vm.breakCoalescing() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                label,
                { label = it; vm.editGroup(groupId, it.trim()) },
                singleLine = true,
                label = { Text("Group name") },
                modifier = Modifier.fillMaxWidth(),
            )
            TagsAndColor(vm, ui, ui.groupTags[groupId].orEmpty(), group.color, TagTarget.Group(groupId), ColorTarget.Group(ui.view.id, groupId), onOpenNote)
            Text(
                if (onBoard) "Long-press the group's bar to move the whole group." else "In the tray. Long-press it there to place the whole group.",
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.copyGroup(groupId); onCopied(); onDismiss() }) { Text("Copy group") }
                if (onBoard) {
                    OutlinedButton(onClick = { vm.moveToTray(Item.GroupItem(groupId)); onDismiss() }) { Text("Move to tray") }
                    TextButton(onClick = { vm.ungroup(groupId); onDismiss() }) { Text("Ungroup") }
                }
                TextButton(onClick = { confirmDelete = true }) { Text("Delete group and nodes") }
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            "Delete group?",
            "The group and every node in it are removed from every view. You can undo this.",
            "Delete",
            onDismiss = { confirmDelete = false },
        ) { vm.deleteGroupWithNodes(groupId); onDismiss() }
    }
}

/** A timeline's name and color, with tray and delete actions. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TimelineSheet(vm: BoardViewModel, ui: BoardUi, timelineId: String, onDismiss: () -> Unit) {
    val timeline = ui.timelines.find { it.id == timelineId } ?: return
    var name by remember(timelineId) { mutableStateOf(timeline.name) }
    var confirmDelete by remember { mutableStateOf(false) }
    LaunchedEffect(timelineId) { vm.breakCoalescing() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                name,
                { name = it; if (it.isNotBlank()) vm.renameTimeline(timelineId, it.trim()) },
                singleLine = true,
                label = { Text("Timeline name") },
                modifier = Modifier.fillMaxWidth(),
            )
            EditorSection("Color") { ColorPicker(timeline.color, { vm.setColor(ColorTarget.Timeline(timelineId), it) }) }
            Text(
                "Long-press the timeline's name to reorder it, or drag it to the tray or the trash.",
                style = MaterialTheme.typography.bodySmall,
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { vm.moveTimelineToTray(timelineId); onDismiss() }) { Text("Remove, nodes to tray") }
                TextButton(onClick = { confirmDelete = true }) { Text("Delete with its nodes") }
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog(
            "Delete timeline?",
            "The timeline is deleted from every view, along with every node on it. You can undo this.",
            "Delete",
            onDismiss = { confirmDelete = false },
        ) { vm.deleteTimeline(timelineId); onDismiss() }
    }
}

/** Sort order, tag filter and search for the tray. */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TrayOptionsSheet(ui: BoardUi, view: TrayView, onChange: (TrayView) -> Unit, onDismiss: () -> Unit) {
    // Tags used by something in the tray, by name.
    val used = ui.tray.flatMap { ui.trayTags(it) }.distinct().mapNotNull { ui.tags[it] }.sortedBy { it.name.lowercase() }
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            EditorSection("Order") {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val options = listOf(TraySort.Manual to "Manual", TraySort.Alphabetical to "A–Z", TraySort.ByTag to "By tag")
                    options.forEachIndexed { i, (sort, label) ->
                        SegmentedButton(
                            selected = view.sort == sort,
                            onClick = { onChange(view.copy(sort = sort)) },
                            shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        ) { Text(label) }
                    }
                }
                Text(
                    when (view.sort) {
                        TraySort.Manual -> "The order you drag items into."
                        TraySort.Alphabetical -> "By group name or node text."
                        TraySort.ByTag -> "Items with the same primary tag sit together, A–Z within."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            EditorSection("Search") {
                OutlinedTextField(
                    view.query,
                    { onChange(view.copy(query = it)) },
                    singleLine = true,
                    placeholder = { Text("Text or group name") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            EditorSection("Tag") {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(view.tagId == null, { onChange(view.copy(tagId = null)) }, label = { Text("Any") })
                    used.forEach { tag ->
                        FilterChip(
                            view.tagId == tag.id,
                            { onChange(view.copy(tagId = if (view.tagId == tag.id) null else tag.id)) },
                            label = { Text(tag.name) },
                            leadingIcon = { TagDot(tag) },
                        )
                    }
                    FilterChip(
                        view.tagId == TrayView.UNTAGGED,
                        { onChange(view.copy(tagId = if (view.tagId == TrayView.UNTAGGED) null else TrayView.UNTAGGED)) },
                        label = { Text("No tag") },
                    )
                }
            }
            if (!view.isManual) {
                Text(
                    "Dragging within the tray reorders it only in manual order with no filter. Items dropped in now go to the end.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (view != TrayView()) TextButton(onClick = { onChange(TrayView()) }) { Text("Reset") }
        }
    }
}

/** Per-view show/hide switches for the project's tags. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FilterSheet(vm: BoardViewModel, ui: BoardUi, onDismiss: () -> Unit) {
    val all by vm.projectTags.collectAsStateWithLifecycle(emptyList())
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
            Text(
                "Show items tagged",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            if (all.isEmpty()) {
                Text("No tags yet.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            }
            all.forEach { tag ->
                val shown = tag.id !in ui.hiddenTags
                ListItem(
                    headlineContent = { Text(tag.name) },
                    leadingContent = { TagDot(tag) },
                    trailingContent = { Switch(shown, { vm.setTagHidden(tag.id, !it) }) },
                )
            }
            Text(
                "Hidden items are drawn faintly; they keep their place on the board.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}
