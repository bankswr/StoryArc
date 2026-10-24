package com.storyarc.app.ui.project

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Public
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.storyarc.app.ui.common.appContainer

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProjectHomeScreen(projectId: String, onBack: () -> Unit, onOpenView: (String) -> Unit) {
    val repo = appContainer().repository
    val project by repo.projects.observeProject(projectId).collectAsStateWithLifecycle(null)
    val views by repo.projects.observeViews(projectId).collectAsStateWithLifecycle(emptyList())
    val worlds by repo.projects.observeWorlds(projectId).collectAsStateWithLifecycle(emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(project?.name ?: "") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            item { SectionHeader("Views") }
            items(views, key = { it.id }) { v ->
                ListItem(
                    headlineContent = { Text(v.name) },
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.ViewList, null) },
                    modifier = Modifier.clickable { onOpenView(v.id) },
                )
            }
            item { HorizontalDivider(Modifier.padding(vertical = 8.dp)) }
            item { SectionHeader("World libraries") }
            items(worlds, key = { it.id }) { w ->
                ListItem(headlineContent = { Text(w.name) }, leadingContent = { Icon(Icons.Default.Public, null) })
            }
        }
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
