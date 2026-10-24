package com.storyarc.app.ui.board

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material3.Icon
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ListItem
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NodeEditorSheet(
    vm: BoardViewModel,
    nodeId: String,
    ui: BoardUi,
    onDismiss: () -> Unit,
    onSelect: (String) -> Unit,
    onOpenNote: (String) -> Unit,
) {
    val node = ui.nodes.getValue(nodeId)
    var text by remember(nodeId) { mutableStateOf(node.text) }
    var confirmDelete by remember { mutableStateOf(false) }
    var confirmLastRemove by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(nodeId) { vm.breakCoalescing() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it; vm.editNodeText(nodeId, it) },
                label = { Text("Note") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
            )
            TagsAndColor(vm, ui, ui.nodeTags[nodeId].orEmpty(), node.color, TagTarget.Node(nodeId), ColorTarget.Node(nodeId), onOpenNote)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = {
                    scope.launch {
                        if (vm.isLastPlacement(nodeId)) confirmLastRemove = true else { vm.removeNodeFromView(nodeId); onDismiss() }
                    }
                }) { Text("Remove from view") }
                TextButton(onClick = { confirmDelete = true }) { Text("Delete everywhere") }
            }
            TextButton(onClick = { onSelect(nodeId) }) { Text("Select for grouping") }
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GroupSheet(vm: BoardViewModel, ui: BoardUi, groupId: String, onDismiss: () -> Unit, onOpenNote: (String) -> Unit) {
    val group = ui.groups.getValue(groupId)
    var label by remember(groupId) { mutableStateOf(group.label) }
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
            Text("Long-press the group's bar to move the whole group.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { vm.ungroup(groupId); onDismiss() }) { Text("Ungroup") }
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
