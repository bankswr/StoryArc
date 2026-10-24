package com.storyarc.app.ui.world

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Link
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.storyarc.app.data.repo.StoryRepository.TagTarget
import com.storyarc.app.ui.common.AppAlertDialog
import com.storyarc.app.ui.common.ConfirmDialog
import com.storyarc.app.ui.common.EditorSection
import com.storyarc.app.ui.common.TagChips
import com.storyarc.app.ui.common.TagLabel
import com.storyarc.app.ui.common.TagPickerDialog
import com.storyarc.app.ui.common.UndoRedoActions
import com.storyarc.app.ui.common.appContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A communal note: title, category, body, tags, links and back-links. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoteScreen(projectId: String, noteId: String, onBack: () -> Unit, onOpenNote: (String) -> Unit) {
    val repo = appContainer().repository
    val note by repo.worlds.observeNote(noteId).collectAsStateWithLifecycle(null)
    val current = note ?: return
    val worldId = current.worldId
    val categories by repo.worlds.observeCategories(worldId).collectAsStateWithLifecycle(emptyList())
    val worldNotes by repo.worlds.observeNotes(worldId).collectAsStateWithLifecycle(emptyList())
    val linksFrom by repo.worlds.observeLinksFrom(noteId).collectAsStateWithLifecycle(emptyList())
    val linksTo by repo.worlds.observeLinksTo(noteId).collectAsStateWithLifecycle(emptyList())
    val referencing by repo.worlds.observeTagsLinking(noteId).collectAsStateWithLifecycle(emptyList())
    val noteTags by repo.worlds.observeNoteTags(noteId).collectAsStateWithLifecycle(emptyList())
    val allTags by repo.tags.observeProjectTags(projectId).collectAsStateWithLifecycle(emptyList())
    val undo by repo.undoManager(projectId).state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var title by remember(noteId) { mutableStateOf(current.title) }
    var body by remember(noteId) { mutableStateOf(current.body) }
    var categoryMenu by remember { mutableStateOf(false) }
    var linking by remember { mutableStateOf(false) }
    var pickingTags by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    LaunchedEffect(noteId) { repo.undoManager(projectId).breakCoalescing() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Note") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    UndoRedoActions(undo, { scope.launch { repo.undo(projectId) } }, { scope.launch { repo.redo(projectId) } })
                    IconButton(onClick = { deleting = true }) { Icon(Icons.Default.Delete, "Delete note") }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            OutlinedTextField(
                title,
                { title = it; if (it.isNotBlank()) scope.launch { repo.editNote(projectId, noteId, title = it) } },
                singleLine = true,
                label = { Text("Title") },
                modifier = Modifier.fillMaxWidth(),
            )
            Box {
                AssistChip(
                    onClick = { categoryMenu = true },
                    label = { Text(categories.find { it.id == current.categoryId }?.name ?: "No category") },
                )
                DropdownMenu(categoryMenu, onDismissRequest = { categoryMenu = false }) {
                    DropdownMenuItem(text = { Text("No category") }, onClick = {
                        categoryMenu = false
                        scope.launch { repo.editNote(projectId, noteId, clearCategory = true) }
                    })
                    categories.forEach { c ->
                        DropdownMenuItem(text = { Text(c.name) }, onClick = {
                            categoryMenu = false
                            scope.launch { repo.editNote(projectId, noteId, categoryId = c.id) }
                        })
                    }
                }
            }
            OutlinedTextField(
                body,
                { body = it; scope.launch { repo.editNote(projectId, noteId, body = it) } },
                label = { Text("Notes") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp),
            )
            EditorSection("Tags") {
                TagChips(noteTags.map { it.tagId }, allTags.associateBy { it.id }, onEdit = { pickingTags = true })
            }
            EditorSection("Links") {
                linksFrom.forEach { n ->
                    ListItem(
                        headlineContent = { Text(n.title) },
                        leadingContent = { Icon(Icons.Default.Link, null) },
                        trailingContent = {
                            IconButton(onClick = { scope.launch { repo.unlinkNotes(projectId, noteId, n.id) } }) {
                                Icon(Icons.Default.Close, "Remove link")
                            }
                        },
                        modifier = Modifier.clickable { onOpenNote(n.id) },
                    )
                }
                TextButton(onClick = { linking = true }) { Text("Link to another note") }
            }
            if (linksTo.isNotEmpty()) {
                EditorSection("Linked from") {
                    linksTo.forEach { n ->
                        ListItem(headlineContent = { Text(n.title) }, modifier = Modifier.clickable { onOpenNote(n.id) })
                    }
                }
            }
            EditorSection("Tags pointing here") {
                referencing.forEach { t -> ListItem(headlineContent = { TagLabel(t) }, leadingContent = { Icon(Icons.AutoMirrored.Filled.Label, null) }) }
                TextButton(onClick = {
                    scope.launch {
                        val id = repo.createTag(projectId, current.title, worldId = worldId)
                        repo.tags.tag(id)?.let { repo.updateTag(projectId, it.copy(linkedNoteId = noteId)) }
                    }
                }) { Text("Create a tag for this note") }
                Text(
                    "Tag nodes on the board with it to reference this note from the timeline.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }

    if (linking) {
        val candidates = worldNotes.filter { n -> n.id != noteId && linksFrom.none { it.id == n.id } }
        AppAlertDialog(
            onDismissRequest = { linking = false },
            title = { Text("Link to") },
            text = {
                if (candidates.isEmpty()) Text("No other notes in this world library.")
                LazyColumn(Modifier.heightIn(max = 360.dp)) {
                    items(candidates, key = { it.id }) { n ->
                        ListItem(headlineContent = { Text(n.title) }, modifier = Modifier.clickable {
                            linking = false
                            scope.launch { repo.linkNotes(projectId, noteId, n.id) }
                        })
                    }
                }
            },
            confirmButton = { TextButton(onClick = { linking = false }) { Text("Cancel") } },
        )
    }
    if (pickingTags) {
        TagPickerDialog(
            allTags,
            noteTags.map { it.tagId },
            onDismiss = { pickingTags = false },
            onCreate = { name, done -> scope.launch { done(repo.createTag(projectId, name, worldId = worldId)) } },
            onConfirm = { ids -> pickingTags = false; scope.launch { repo.setTags(projectId, TagTarget.Note(noteId), ids) } },
        )
    }
    if (deleting) {
        ConfirmDialog(
            "Delete \"${current.title}\"?",
            "Links to and from it are removed, and tags pointing here lose the reference. You can undo this.",
            "Delete",
            onDismiss = { deleting = false },
        ) {
            scope.launch {
                repo.deleteNote(projectId, noteId)
                withContext(Dispatchers.Main) { onBack() }
            }
        }
    }
}
