package com.storyarc.app.ui.common

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.storyarc.app.data.backup.Backup
import com.storyarc.app.data.backup.BackupFormatException
import com.storyarc.app.data.backup.ImportMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** File name for a backup: `StoryArc-<label>-<date>.zip`. */
fun backupFileName(label: String): String {
    val safe = label.replace(Regex("[^A-Za-z0-9 _-]"), "").trim().replace(' ', '-').ifEmpty { "backup" }
    return "StoryArc-$safe-" + SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) + ".zip"
}

/**
 * Returns a function that asks where to save, then writes a backup of [projectIds] (with
 * their world libraries), or of everything when null.
 */
@Composable
fun rememberBackupExporter(projectIds: () -> List<String>?): (String) -> Unit {
    val context = LocalContext.current
    val backups = appContainer().backups
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<List<String>?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        val ids = pending
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openOutputStream(uri)!!.use { backups.export(it, ids) }
                }
            }
            Toast.makeText(context, if (result.isSuccess) "Backup saved" else "Backup failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
        }
    }
    return { fileLabel ->
        pending = projectIds()
        launcher.launch(backupFileName(fileLabel))
    }
}

/** Picks a backup file, summarises it, asks how to handle conflicts, and imports it. */
@Composable
fun rememberRestore(): () -> Unit {
    val context = LocalContext.current
    val backups = appContainer().backups
    val scope = rememberCoroutineScope()
    var loaded by remember { mutableStateOf<Pair<Backup, Int>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            try {
                val backup = withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)!!.use { backups.read(it) } }
                val (p, w) = backups.conflicts(backup)
                loaded = backup to (p.size + w.size)
            } catch (e: BackupFormatException) {
                error = e.message
            } catch (e: Exception) {
                error = "Couldn't read that file: ${e.message}"
            }
        }
    }

    fun run(backup: Backup, mode: ImportMode) {
        loaded = null
        scope.launch {
            val result = runCatching { backups.import(backup, mode) }
            Toast.makeText(context, if (result.isSuccess) "Backup restored" else "Restore failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
        }
    }

    loaded?.let { (backup, conflicts) ->
        val names = backup.projects.joinToString { it.project.name }.ifEmpty { "no projects" }
        AppAlertDialog(
            onDismissRequest = { loaded = null },
            title = { Text("Restore backup") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Contains: $names, and ${backup.worlds.size} world librar${if (backup.worlds.size == 1) "y" else "ies"}.")
                    if (conflicts > 0) {
                        Text(
                            "$conflicts of these already exist on this device. Replace them with the backup's version, or import the projects as copies (existing world libraries are kept).",
                        )
                    }
                    Text("Undo history is cleared after a restore.")
                }
            },
            confirmButton = {
                TextButton(onClick = { run(backup, ImportMode.REPLACE) }) { Text(if (conflicts > 0) "Replace" else "Restore") }
            },
            dismissButton = {
                Column {
                    if (conflicts > 0) TextButton(onClick = { run(backup, ImportMode.COPY) }) { Text("Import as copies") }
                    TextButton(onClick = { loaded = null }) { Text("Cancel") }
                }
            },
        )
    }
    error?.let { msg ->
        AppAlertDialog(
            onDismissRequest = { error = null },
            title = { Text("Can't restore") },
            text = { Text(msg) },
            confirmButton = { TextButton(onClick = { error = null }) { Text("OK") } },
        )
    }
    return { launcher.launch(arrayOf("application/zip", "application/octet-stream")) }
}
