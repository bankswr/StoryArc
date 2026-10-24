package com.storyarc.app.ui.tags

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.storyarc.app.data.db.TagEntity
import com.storyarc.app.data.db.WorldEntity
import com.storyarc.app.ui.common.AppAlertDialog
import com.storyarc.app.ui.common.ColorPicker
import com.storyarc.app.ui.common.ConfirmDialog
import com.storyarc.app.ui.common.TagLabel
import com.storyarc.app.ui.common.UndoRedoActions
import com.storyarc.app.ui.common.appContainer
import kotlinx.coroutines.launch

/** Create, edit and delete the tags a project can use (its own and its world libraries'). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TagsScreen(projectId: String, onBack: () -> Unit) {
    val repo = appContainer().repository
    val tags by repo.tags.observeProjectTags(projectId).collectAsStateWithLifecycle(emptyList())
    val worlds by repo.projects.observeWorlds(projectId).collectAsStateWithLifecycle(emptyList())
    val undo by repo.undoManager(projectId).state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var creating by rememberSaveable { mutableStateOf(false) }
    var editing by remember { mutableStateOf<TagEntity?>(null) }
    var deleting by remember { mutableStateOf<TagEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tags") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { UndoRedoActions(undo, { scope.launch { repo.undo(projectId) } }, { scope.launch { repo.redo(projectId) } }) },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("New tag") })
        },
    ) { padding ->
        if (tags.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Tags mark dimensions like Scene, POV or Day, and can point to world notes. Anything tagged can take its tag's color.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(tags, key = { it.id }) { t ->
                ListItem(
                    headlineContent = { TagLabel(t) },
                    supportingContent = { Text(ownerName(t, worlds)) },
                    modifier = Modifier.clickable { editing = t },
                )
                HorizontalDivider()
            }
        }
    }

    if (creating) {
        TagDialog(null, worlds, onDismiss = { creating = false }) { name, color, worldId ->
            creating = false
            scope.launch { repo.createTag(projectId, name, color, worldId) }
        }
    }
    editing?.let { t ->
        TagDialog(t, worlds, onDismiss = { editing = null }, onDelete = { editing = null; deleting = t }) { name, color, worldId ->
            editing = null
            scope.launch {
                repo.updateTag(projectId, t.copy(name = name, color = color, worldId = worldId, projectId = if (worldId == null) projectId else null))
            }
        }
    }
    deleting?.let { t ->
        ConfirmDialog(
            "Delete \"${t.name}\"?",
            "The tag is removed from everything that uses it. You can undo this.",
            "Delete",
            onDismiss = { deleting = null },
        ) { scope.launch { repo.deleteTag(projectId, t.id) } }
    }
}

private fun ownerName(t: TagEntity, worlds: List<WorldEntity>) =
    t.worldId?.let { id -> "World library: " + (worlds.find { it.id == id }?.name ?: "other") } ?: "This project"

/** Name, color and owner of a tag; [tag] null creates one. */
@Composable
private fun TagDialog(
    tag: TagEntity?,
    worlds: List<WorldEntity>,
    onDismiss: () -> Unit,
    onDelete: (() -> Unit)? = null,
    onSave: (String, Int?, String?) -> Unit,
) {
    var name by remember { mutableStateOf(tag?.name.orEmpty()) }
    var color by remember { mutableStateOf(tag?.color) }
    var worldId by remember { mutableStateOf(tag?.worldId) }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (tag == null) "New tag" else "Edit tag") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                ColorPicker(color, { color = it })
                if (worlds.isNotEmpty()) {
                    Text("Belongs to", style = MaterialTheme.typography.labelLarge)
                    Column(Modifier.selectableGroup()) {
                        OwnerChoice("This project only", worldId == null) { worldId = null }
                        worlds.forEach { w -> OwnerChoice("World library: ${w.name}", worldId == w.id) { worldId = w.id } }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(name.trim(), color, worldId) }, enabled = name.isNotBlank()) { Text("Save") } },
        dismissButton = {
            Row {
                if (onDelete != null) TextButton(onClick = onDelete) { Text("Delete") }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

@Composable
private fun OwnerChoice(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, onClick = onSelect, role = Role.RadioButton).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick = null)
        Text(label, Modifier.padding(start = 8.dp))
    }
}
