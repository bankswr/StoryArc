package com.storyarc.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.storyarc.app.BuildConfig
import com.storyarc.app.data.db.StoryDatabase
import com.storyarc.app.ui.common.appContainer
import com.storyarc.app.ui.common.rememberBackupExporter
import com.storyarc.app.ui.common.rememberRestore
import androidx.compose.foundation.clickable
import com.storyarc.app.ui.project.SectionHeader
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val settingsRepo = appContainer().settingsRepository
    val settings by appContainer().settings.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var delay by remember { mutableFloatStateOf(settings.barrierDelayMs.toFloat()) }
    LaunchedEffect(settings.barrierDelayMs) { delay = settings.barrierDelayMs.toFloat() }
    val exportAll = rememberBackupExporter { null }
    val restore = rememberRestore()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SectionHeader("Board")
            ListItem(
                headlineContent = { Text("Layout") },
                supportingContent = {
                    Column {
                        Text(if (settings.verticalBoard) "Timelines are columns; order runs top to bottom." else "Timelines are rows; order runs left to right.")
                        SingleChoiceSegmentedButtonRow(Modifier.padding(top = 8.dp)) {
                            listOf("Horizontal" to false, "Vertical" to true).forEachIndexed { i, (label, value) ->
                                SegmentedButton(
                                    selected = settings.verticalBoard == value,
                                    onClick = { scope.launch { settingsRepo.setVerticalBoard(value) } },
                                    shape = SegmentedButtonDefaults.itemShape(i, 2),
                                ) { Text(label) }
                            }
                        }
                    }
                },
            )
            SectionHeader("Dragging")
            ListItem(
                headlineContent = { Text("Pause at markers") },
                supportingContent = { Text("A dragged item stops at a marker and crosses only after a short hold.") },
                trailingContent = {
                    Switch(settings.barrierDelayEnabled, { scope.launch { settingsRepo.setBarrierDelayEnabled(it) } })
                },
            )
            if (settings.barrierDelayEnabled) {
                ListItem(
                    headlineContent = { Text("Hold time: ${delay.toLong()} ms") },
                    supportingContent = {
                        Slider(
                            value = delay,
                            onValueChange = { delay = it },
                            onValueChangeFinished = { scope.launch { settingsRepo.setBarrierDelayMs(delay.toLong()) } },
                            valueRange = 200f..1500f,
                            steps = 12,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    },
                )
            }
            SectionHeader("Backups")
            ListItem(
                headlineContent = { Text("Back up everything") },
                supportingContent = { Text("All projects and world libraries, as one .zip file") },
                modifier = Modifier.clickable { exportAll("everything") },
            )
            ListItem(
                headlineContent = { Text("Restore from backup") },
                supportingContent = { Text("Projects can also be backed up one at a time from the project list") },
                modifier = Modifier.clickable { restore() },
            )
            SectionHeader("About")
            ListItem(
                headlineContent = { Text("App version") },
                supportingContent = { Text("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})") },
            )
            ListItem(
                headlineContent = { Text("Database schema") },
                supportingContent = { Text("Version ${StoryDatabase.SCHEMA_VERSION}") },
            )
        }
    }
}
