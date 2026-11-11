package com.storyarc.app.ui.board

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.storyarc.app.domain.manuscript.Assembler
import com.storyarc.app.domain.manuscript.OdtWriter
import com.storyarc.app.domain.manuscript.PaperSize
import com.storyarc.app.domain.manuscript.Stats
import com.storyarc.app.ui.common.AppAlertDialog
import com.storyarc.app.ui.common.PaperSizePicker
import com.storyarc.app.ui.common.appContainer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate

/**
 * Assembles the scenes timeline (in this view's order) into one manuscript and saves it as
 * an .odt file wherever the person picks. An optional author name adds a title page and the
 * running header.
 */
@Composable
fun AssembleDialog(vm: BoardViewModel, ui: BoardUi, wordsPerPage: Int, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val container = appContainer()
    val settings by container.settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var withAuthor by rememberSaveable { mutableStateOf(false) }
    var author by rememberSaveable { mutableStateOf(settings.authorName) }
    var projectName by remember { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) { projectName = vm.projectName() }

    val scenes = ui.scenesInOrder()
    val words = ui.manuscriptWords()
    val authorUsed = author.trim().takeIf { withAuthor && it.isNotEmpty() }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(OdtWriter.MIME_TYPE)) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val paper = PaperSize.resolve(settings.paperSize)
        scope.launch {
            val result = runCatching {
                val manuscript = vm.manuscript(authorUsed)
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)!!.use { OdtWriter.write(manuscript, paper, it) }
                }
            }
            Toast.makeText(
                context,
                if (result.isSuccess) "Manuscript saved" else "Couldn't save the manuscript: ${result.exceptionOrNull()?.message}",
                Toast.LENGTH_LONG,
            ).show()
            if (result.isSuccess) onDismiss()
        }
    }

    AppAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Assemble manuscript") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (scenes.isEmpty()) "The scenes timeline has no scenes in this view yet."
                    else "${scenes.size} chapter${if (scenes.size == 1) "" else "s"} · " + Stats.summary(words, wordsPerPage) +
                        ". Each scene is a chapter titled with its card name, in this view's order. Scenes in the tray are left out.",
                    style = MaterialTheme.typography.bodyMedium,
                )
                PaperSizePicker(settings.paperSize, { scope.launch { container.settingsRepository.setPaperSize(it) } }, Modifier.fillMaxWidth())
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Add author name", style = MaterialTheme.typography.bodyLarge)
                        Text("A title page, and “Surname / TITLE / page” at the top of each page.", style = MaterialTheme.typography.bodySmall)
                    }
                    Switch(withAuthor, { withAuthor = it })
                }
                if (withAuthor) {
                    OutlinedTextField(author, { author = it }, singleLine = true, label = { Text("Author name") }, modifier = Modifier.fillMaxWidth())
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = scenes.isNotEmpty() && projectName != null && (!withAuthor || author.isNotBlank()),
                onClick = {
                    if (authorUsed != null) scope.launch { container.settingsRepository.setAuthorName(authorUsed) }
                    launcher.launch(Assembler.fileName(projectName.orEmpty(), LocalDate.now()))
                },
            ) { Text("Save .odt") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
