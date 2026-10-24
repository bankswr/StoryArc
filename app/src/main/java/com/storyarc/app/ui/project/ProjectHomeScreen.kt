package com.storyarc.app.ui.project

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Public
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.storyarc.app.data.db.ViewEntity
import com.storyarc.app.ui.common.ConfirmDialog
import com.storyarc.app.ui.common.TextInputDialog
import com.storyarc.app.ui.common.UndoRedoActions
import com.storyarc.app.ui.common.appContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectHomeScreen(projectId: String, onBack: () -> Unit, onOpenView: (String) -> Unit) {
    val repo = appContainer().repository
    val project by repo.projects.observeProject(projectId).collectAsStateWithLifecycle(null)
    val views by repo.projects.observeViews(projectId).collectAsStateWithLifecycle(emptyList())
    val worlds by repo.projects.observeWorlds(projectId).collectAsStateWithLifecycle(emptyList())
    val undo by repo.undoManager(projectId).state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var creating by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ViewEntity?>(null) }
    var deleting by remember { mutableStateOf<ViewEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(project?.name ?: "") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { UndoRedoActions(undo, { scope.launch { repo.undo(projectId) } }, { scope.launch { repo.redo(projectId) } }) },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { SectionHeader("Views") }
            items(views, key = { it.id }) { v ->
                var menu by remember { mutableStateOf(false) }
                ListItem(
                    headlineContent = { Text(v.name) },
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.ViewList, null) },
                    trailingContent = {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "View options") }
                            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = v })
                                DropdownMenuItem(
                                    text = { Text("Delete") },
                                    enabled = views.size > 1,
                                    onClick = { menu = false; deleting = v },
                                )
                            }
                        }
                    },
                    modifier = Modifier.clickable { onOpenView(v.id) },
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("New view") },
                    leadingContent = { Icon(Icons.Default.Add, null) },
                    modifier = Modifier.clickable { creating = true },
                )
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { SectionHeader("World libraries") }
            items(worlds, key = { it.id }) { w ->
                ListItem(headlineContent = { Text(w.name) }, leadingContent = { Icon(Icons.Default.Public, null) })
            }
        }
    }

    if (creating) {
        NewViewDialog(views, onDismiss = { creating = false }) { name, from, import ->
            creating = false
            scope.launch {
                val id = repo.createView(projectId, name, from, import)
                withContext(Dispatchers.Main) { onOpenView(id) }
            }
        }
    }
    renaming?.let { v ->
        TextInputDialog("Rename view", initial = v.name, onDismiss = { renaming = null }) { name ->
            renaming = null
            scope.launch { repo.renameView(v.id, name) }
        }
    }
    deleting?.let { v ->
        ConfirmDialog(
            "Delete \"${v.name}\"?",
            "Its layout is removed. Nodes and timelines not used in any other view are deleted. You can undo this.",
            "Delete",
            onDismiss = { deleting = null },
        ) { scope.launch { repo.deleteView(v.id) } }
    }
}

@Composable
fun SectionHeader(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}
