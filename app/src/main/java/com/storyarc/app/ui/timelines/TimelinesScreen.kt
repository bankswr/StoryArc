package com.storyarc.app.ui.timelines

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material3.TextButton
import androidx.compose.ui.graphics.Color
import com.storyarc.app.data.repo.StoryRepository
import com.storyarc.app.ui.common.AppAlertDialog
import com.storyarc.app.ui.common.ColorPicker
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.storyarc.app.data.db.TimelineEntity
import com.storyarc.app.ui.common.ConfirmDialog
import com.storyarc.app.ui.common.TextInputDialog
import com.storyarc.app.ui.common.UndoRedoActions
import com.storyarc.app.ui.common.appContainer
import kotlinx.coroutines.launch

/** Add, rename, reorder and remove the timelines of one view. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimelinesScreen(projectId: String, viewId: String, onBack: () -> Unit) {
    val repo = appContainer().repository
    val timelines by repo.board.observeTimelinesInView(viewId).collectAsStateWithLifecycle(emptyList())
    val counts by repo.board.observeNodeCounts(viewId).collectAsStateWithLifecycle(emptyList())
    val undo by repo.undoManager(projectId).state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var adding by rememberSaveable { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<TimelineEntity?>(null) }
    var removing by remember { mutableStateOf<TimelineEntity?>(null) }
    var coloring by remember { mutableStateOf<TimelineEntity?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Timelines") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
                actions = { UndoRedoActions(undo, { scope.launch { repo.undo(projectId) } }, { scope.launch { repo.redo(projectId) } }) },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Default.Add, null) }, text = { Text("Add timeline") })
        },
    ) { padding ->
        if (timelines.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("No timelines yet", style = MaterialTheme.typography.bodyLarge)
            }
        }
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            itemsIndexed(timelines, key = { _, t -> t.id }) { i, t ->
                val n = counts.find { it.timelineId == t.id }?.count ?: 0
                ListItem(
                    headlineContent = { Text(t.name) },
                    supportingContent = { Text(if (n == 1) "1 node" else "$n nodes") },
                    trailingContent = {
                        Row {
                            IconButton(onClick = { coloring = t }) {
                                Icon(Icons.Default.Palette, "Color", tint = t.color?.let { Color(it) } ?: MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            IconButton(onClick = { scope.launch { repo.moveTimeline(viewId, t.id, i - 1) } }, enabled = i > 0) {
                                Icon(Icons.Default.KeyboardArrowUp, "Move up")
                            }
                            IconButton(onClick = { scope.launch { repo.moveTimeline(viewId, t.id, i + 1) } }, enabled = i < timelines.lastIndex) {
                                Icon(Icons.Default.KeyboardArrowDown, "Move down")
                            }
                            IconButton(onClick = { removing = t }) { Icon(Icons.Default.Delete, "Remove") }
                        }
                    },
                    modifier = Modifier.clickable { renaming = t }.animateItem(),
                )
                HorizontalDivider()
            }
        }
    }

    if (adding) {
        TextInputDialog("New timeline", confirmLabel = "Add", onDismiss = { adding = false }) { name ->
            adding = false
            scope.launch { repo.addTimeline(viewId, name) }
        }
    }
    renaming?.let { t ->
        TextInputDialog("Rename timeline", initial = t.name, onDismiss = { renaming = null }) { name ->
            renaming = null
            scope.launch { repo.renameTimeline(t.id, name) }
        }
    }
    coloring?.let { t ->
        AppAlertDialog(
            onDismissRequest = { coloring = null },
            title = { Text("Color for ${t.name}") },
            text = {
                ColorPicker(t.color, { c ->
                    coloring = null
                    scope.launch { repo.setColor(projectId, StoryRepository.ColorTarget.Timeline(t.id), c) }
                })
            },
            confirmButton = { TextButton(onClick = { coloring = null }) { Text("Close") } },
        )
    }
    removing?.let { t ->
        ConfirmDialog(
            "Remove \"${t.name}\"?",
            "Its nodes are removed from this view. Nodes not used in any other view are deleted. You can undo this.",
            "Remove",
            onDismiss = { removing = null },
        ) { scope.launch { repo.removeTimelineFromView(viewId, t.id) } }
    }
}
