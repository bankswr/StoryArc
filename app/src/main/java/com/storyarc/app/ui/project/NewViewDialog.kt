package com.storyarc.app.ui.project

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.storyarc.app.data.db.ViewEntity
import com.storyarc.app.data.repo.StoryRepository.ViewImport
import com.storyarc.app.ui.common.AppAlertDialog

/** Name a new view and choose what to import from an existing one. */
@Composable
fun NewViewDialog(views: List<ViewEntity>, onDismiss: () -> Unit, onCreate: (String, String?, ViewImport) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var import by rememberSaveable { mutableStateOf(ViewImport.NOTHING) }
    var source by rememberSaveable { mutableStateOf(views.firstOrNull()?.id) }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New view") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { name = it }, singleLine = true, label = { Text("Name") }, modifier = Modifier.fillMaxWidth())
                Text("Start with", style = MaterialTheme.typography.labelLarge)
                Column(Modifier.selectableGroup()) {
                    Choice("Nothing", import == ViewImport.NOTHING) { import = ViewImport.NOTHING }
                    Choice("Timelines", import == ViewImport.TIMELINES) { import = ViewImport.TIMELINES }
                    Choice("Timelines and nodes", import == ViewImport.TIMELINES_AND_NODES) { import = ViewImport.TIMELINES_AND_NODES }
                }
                if (import != ViewImport.NOTHING && views.size > 1) {
                    Text("From", style = MaterialTheme.typography.labelLarge)
                    Column(Modifier.selectableGroup()) {
                        views.forEach { v -> Choice(v.name, source == v.id) { source = v.id } }
                    }
                }
                if (import == ViewImport.TIMELINES_AND_NODES) {
                    Text(
                        "Imported nodes are shared: editing one changes it in every view, but each view arranges them independently.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onCreate(name.trim(), source, import) }, enabled = name.isNotBlank()) { Text("Create") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun Choice(label: String, selected: Boolean, onSelect: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected, onClick = onSelect, role = Role.RadioButton).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected, onClick = null)
        Text(label, Modifier.padding(start = 8.dp))
    }
}
