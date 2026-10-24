package com.storyarc.app.ui.world

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.storyarc.app.data.db.CategoryEntity
import com.storyarc.app.data.db.NoteEntity
import com.storyarc.app.ui.common.ConfirmDialog
import com.storyarc.app.ui.common.TextInputDialog
import com.storyarc.app.ui.common.UndoRedoActions
import com.storyarc.app.ui.common.appContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** One row of the world outline: a category header or a note, indented by depth. */
private sealed interface Row {
    val depth: Int
    data class Category(val c: CategoryEntity, override val depth: Int) : Row
    data class Note(val n: NoteEntity, override val depth: Int) : Row
}

/** Categories as a tree with their notes beneath; uncategorized notes come first. */
private fun outline(categories: List<CategoryEntity>, notes: List<NoteEntity>): List<Row> = buildList {
    val ids = categories.map { it.id }.toSet()
    notes.filter { it.categoryId == null || it.categoryId !in ids }.forEach { add(Row.Note(it, 0)) }
    fun walk(parent: String?, depth: Int) {
        categories.filter { it.parentId == parent }.forEach { c ->
            add(Row.Category(c, depth))
            notes.filter { it.categoryId == c.id }.forEach { add(Row.Note(it, depth + 1)) }
            walk(c.id, depth + 1)
        }
    }
    walk(null, 0)
}

/** A world library's categories and notes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorldScreen(projectId: String, worldId: String, onBack: () -> Unit, onOpenNote: (String) -> Unit) {
    val repo = appContainer().repository
    val world by repo.worlds.observeWorld(worldId).collectAsStateWithLifecycle(null)
    val categories by repo.worlds.observeCategories(worldId).collectAsStateWithLifecycle(emptyList())
    val notes by repo.worlds.observeNotes(worldId).collectAsStateWithLifecycle(emptyList())
    val undo by repo.undoManager(projectId).state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var newNoteIn by rememberSaveable { mutableStateOf<String?>(null) }
    var creatingNote by rememberSaveable { mutableStateOf(false) }
    var newCategoryIn by rememberSaveable { mutableStateOf<String?>(null) }
    var creatingCategory by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<CategoryEntity?>(null) }
    var deleting by remember { mutableStateOf<CategoryEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(world?.name.orEmpty(), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = {
                    UndoRedoActions(undo, { scope.launch { repo.undo(projectId) } }, { scope.launch { repo.redo(projectId) } })
                    IconButton(onClick = { newCategoryIn = null; creatingCategory = true }) { Icon(Icons.Default.CreateNewFolder, "New category") }
                },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { newNoteIn = null; creatingNote = true },
                icon = { Icon(Icons.Default.Add, null) },
                text = { Text("New note") },
            )
        },
    ) { padding ->
        val rows = outline(categories, notes)
        if (rows.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding).padding(24.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Notes here hold what isn't tied to a moment: magic systems, history before the story, places, rules.",
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            items(rows, key = { r -> if (r is Row.Category) "c" + r.c.id else "n" + (r as Row.Note).n.id }) { r ->
                val indent = Modifier.padding(start = (r.depth * 20).dp)
                when (r) {
                    is Row.Note -> ListItem(
                        headlineContent = { Text(r.n.title) },
                        supportingContent = r.n.body.takeIf { it.isNotBlank() }?.let { b ->
                            { Text(b, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        },
                        leadingContent = { Icon(Icons.AutoMirrored.Filled.Notes, null) },
                        modifier = Modifier.clickable { onOpenNote(r.n.id) }.then(indent),
                    )
                    is Row.Category -> {
                        var menu by remember { mutableStateOf(false) }
                        ListItem(
                            headlineContent = { Text(r.c.name, style = MaterialTheme.typography.titleSmall) },
                            leadingContent = { Icon(Icons.Default.Folder, null) },
                            trailingContent = {
                                Box {
                                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Category options") }
                                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                        DropdownMenuItem(text = { Text("New note here") }, onClick = { menu = false; newNoteIn = r.c.id; creatingNote = true })
                                        DropdownMenuItem(text = { Text("New subcategory") }, onClick = { menu = false; newCategoryIn = r.c.id; creatingCategory = true })
                                        DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = r.c })
                                        DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; deleting = r.c })
                                    }
                                }
                            },
                            modifier = indent,
                        )
                    }
                }
            }
        }
    }

    if (creatingNote) {
        TextInputDialog("New note", confirmLabel = "Create", onDismiss = { creatingNote = false }) { title ->
            creatingNote = false
            scope.launch {
                val id = repo.createNote(projectId, worldId, title, newNoteIn)
                withContext(Dispatchers.Main) { onOpenNote(id) }
            }
        }
    }
    if (creatingCategory) {
        TextInputDialog("New category", confirmLabel = "Create", onDismiss = { creatingCategory = false }) { name ->
            creatingCategory = false
            scope.launch { repo.createCategory(projectId, worldId, name, newCategoryIn) }
        }
    }
    renaming?.let { c ->
        TextInputDialog("Rename category", initial = c.name, onDismiss = { renaming = null }) { name ->
            renaming = null
            scope.launch { repo.renameCategory(projectId, worldId, c.id, name) }
        }
    }
    deleting?.let { c ->
        ConfirmDialog(
            "Delete \"${c.name}\"?",
            "Its notes and subcategories move up a level. You can undo this.",
            "Delete",
            onDismiss = { deleting = null },
        ) { scope.launch { repo.deleteCategory(projectId, worldId, c.id) } }
    }
}
