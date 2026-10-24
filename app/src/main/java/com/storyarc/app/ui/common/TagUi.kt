package com.storyarc.app.ui.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Block
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.storyarc.app.data.db.TagEntity

/** Colors offered for tags and items. Chosen to read well with both black and white text. */
val Palette: List<Pair<String, Int>> = listOf(
    "Red" to 0xFFE57373.toInt(), "Orange" to 0xFFFFB74D.toInt(), "Amber" to 0xFFFFD54F.toInt(),
    "Lime" to 0xFFAED581.toInt(), "Green" to 0xFF66BB6A.toInt(), "Teal" to 0xFF4DB6AC.toInt(),
    "Cyan" to 0xFF4DD0E1.toInt(), "Blue" to 0xFF64B5F6.toInt(), "Indigo" to 0xFF7986CB.toInt(),
    "Purple" to 0xFFBA68C8.toInt(), "Pink" to 0xFFF06292.toInt(), "Grey" to 0xFF90A4AE.toInt(),
)

/** A row of swatches; the first clears the color ([noneLabel]). */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ColorPicker(selected: Int?, onSelect: (Int?) -> Unit, noneLabel: String = "No color") {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Swatch(null, selected == null, noneLabel) { onSelect(null) }
        Palette.forEach { (name, c) -> Swatch(c, selected == c, name) { onSelect(c) } }
    }
}

@Composable
private fun Swatch(color: Int?, selected: Boolean, label: String, onClick: () -> Unit) {
    val outline = MaterialTheme.colorScheme.outline
    Box(
        Modifier
            .size(32.dp)
            .background(color?.let { Color(it) } ?: Color.Transparent, CircleShape)
            .border(BorderStroke(if (selected) 3.dp else 1.dp, if (selected) MaterialTheme.colorScheme.primary else outline), CircleShape)
            .clickable(onClick = onClick)
            .semantics { contentDescription = label; this.selected = selected },
        contentAlignment = Alignment.Center,
    ) {
        when {
            color == null -> Icon(Icons.Default.Block, null, tint = outline, modifier = Modifier.size(18.dp))
            selected -> Icon(Icons.Default.Check, null, tint = Color.Black.copy(alpha = 0.7f), modifier = Modifier.size(18.dp))
        }
    }
}

/** Small colored dot for a tag. */
@Composable
fun TagDot(tag: TagEntity?, modifier: Modifier = Modifier) {
    Box(
        modifier
            .size(10.dp)
            .background(tag?.color?.let { Color(it) } ?: MaterialTheme.colorScheme.outlineVariant, CircleShape),
    )
}

/** The assigned tags as chips (primary marked with a star) plus an edit action. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TagChips(assigned: List<String>, tags: Map<String, TagEntity>, onEdit: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        assigned.forEachIndexed { i, id ->
            val tag = tags[id] ?: return@forEachIndexed
            AssistChip(
                onClick = onEdit,
                label = { Text(tag.name) },
                leadingIcon = { if (i == 0) Icon(Icons.Default.Star, "Primary tag", Modifier.size(16.dp)) else TagDot(tag) },
            )
        }
        AssistChip(onClick = onEdit, label = { Text(if (assigned.isEmpty()) "Add tags" else "Edit tags") }, leadingIcon = { Icon(Icons.Default.Add, null, Modifier.size(16.dp)) })
    }
}

/**
 * Choose tags in order: the first checked becomes primary, and "Make primary" moves one to
 * the front. [onCreate] makes a new project tag and reports its id.
 */
@Composable
fun TagPickerDialog(
    all: List<TagEntity>,
    initial: List<String>,
    onDismiss: () -> Unit,
    onCreate: (String, (String) -> Unit) -> Unit,
    onConfirm: (List<String>) -> Unit,
) {
    var chosen by remember { mutableStateOf(initial) }
    var newName by remember { mutableStateOf("") }
    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tags") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (all.isEmpty()) Text("No tags yet. Create one below.", style = MaterialTheme.typography.bodySmall)
                LazyColumn(Modifier.heightIn(max = 320.dp)) {
                    items(all, key = { it.id }) { tag ->
                        val on = tag.id in chosen
                        Row(
                            Modifier.fillMaxWidth().clickable { chosen = if (on) chosen - tag.id else chosen + tag.id },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(on, onCheckedChange = null, modifier = Modifier.padding(8.dp))
                            TagDot(tag)
                            Text(tag.name, Modifier.padding(start = 8.dp).weight(1f))
                            if (on) {
                                val primary = chosen.firstOrNull() == tag.id
                                IconButton(onClick = { chosen = listOf(tag.id) + (chosen - tag.id) }, enabled = !primary) {
                                    Icon(
                                        Icons.Default.Star,
                                        if (primary) "Primary tag" else "Make primary",
                                        tint = if (primary) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                                    )
                                }
                            }
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(newName, { newName = it }, singleLine = true, label = { Text("New tag") }, modifier = Modifier.weight(1f))
                    IconButton(onClick = {
                        val name = newName.trim()
                        newName = ""
                        onCreate(name) { id -> chosen = chosen + id }
                    }, enabled = newName.isNotBlank()) { Icon(Icons.Default.Add, "Create tag") }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(chosen) }) { Text("Done") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Labelled section used inside editors. */
@Composable
fun EditorSection(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

/** A filled rounded label in a tag's color. */
@Composable
fun TagLabel(tag: TagEntity, modifier: Modifier = Modifier) {
    val bg = tag.color?.let { Color(it) } ?: MaterialTheme.colorScheme.surfaceVariant
    Text(
        tag.name,
        style = MaterialTheme.typography.labelMedium,
        color = if (tag.color != null) com.storyarc.app.ui.board.readableOn(bg) else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.background(bg, RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
