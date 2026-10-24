package com.storyarc.app.ui.projects

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.storyarc.app.data.db.ProjectEntity
import com.storyarc.app.ui.common.ConfirmDialog
import com.storyarc.app.ui.common.TextInputDialog
import com.storyarc.app.ui.common.appContainer
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectsScreen(onOpen: (String) -> Unit, onSettings: () -> Unit) {
    val repo = appContainer().repository
    val projects by repo.projects.observeProjects().collectAsStateWithLifecycle(emptyList())
    val scope = rememberCoroutineScope()
    var creating by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<ProjectEntity?>(null) }
    var deleting by remember { mutableStateOf<ProjectEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("StoryArc") },
                actions = { IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, "Settings") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { creating = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("New project") })
        },
    ) { padding ->
        if (projects.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No projects yet", style = MaterialTheme.typography.bodyLarge)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(projects, key = { it.id }) { p ->
                    var menu by remember { mutableStateOf(false) }
                    ListItem(
                        headlineContent = { Text(p.name) },
                        supportingContent = { Text("Edited " + DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT).format(Date(p.updatedAt))) },
                        trailingContent = {
                            Box {
                                IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "Project options") }
                                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; renaming = p })
                                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; deleting = p })
                                }
                            }
                        },
                        modifier = Modifier.clickable { onOpen(p.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (creating) {
        TextInputDialog("New project", confirmLabel = "Create", onDismiss = { creating = false }) { name ->
            creating = false
            scope.launch { onOpen(repo.createProject(name)) }
        }
    }
    renaming?.let { p ->
        TextInputDialog("Rename project", initial = p.name, onDismiss = { renaming = null }) { name ->
            renaming = null
            scope.launch { repo.renameProject(p.id, name) }
        }
    }
    deleting?.let { p ->
        ConfirmDialog(
            "Delete \"${p.name}\"?",
            "This permanently deletes the project, its views and nodes, and any world library no other project uses. It cannot be undone.",
            "Delete",
            onDismiss = { deleting = null },
        ) { scope.launch { repo.deleteProject(p.id) } }
    }
}
