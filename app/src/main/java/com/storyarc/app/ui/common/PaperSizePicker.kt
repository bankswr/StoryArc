package com.storyarc.app.ui.common

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.storyarc.app.domain.manuscript.PaperSize

/** Label for a stored paper-size setting (a [PaperSize] name, or null for automatic). */
fun paperSizeLabel(name: String?): String =
    PaperSize.entries.find { it.name == name }?.label ?: "Automatic (${PaperSize.automatic().label})"

/** Dropdown of export paper sizes: automatic, the manuscript sizes, then book trim sizes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaperSizePicker(selected: String?, onSelect: (String?) -> Unit, modifier: Modifier = Modifier) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(open, { open = it }, modifier) {
        OutlinedTextField(
            value = paperSizeLabel(selected),
            onValueChange = {},
            readOnly = true,
            label = { Text("Paper size") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(open, { open = false }) {
            DropdownMenuItem(text = { Text("Automatic (${PaperSize.automatic().label})") }, onClick = { onSelect(null); open = false })
            HorizontalDivider()
            PaperSize.entries.forEachIndexed { i, size ->
                if (i == 2) HorizontalDivider()
                DropdownMenuItem(text = { Text(size.label) }, onClick = { onSelect(size.name); open = false })
            }
        }
    }
}
