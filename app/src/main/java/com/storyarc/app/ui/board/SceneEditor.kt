@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.storyarc.app.ui.board

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.input.TextFieldLineLimits
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FormatBold
import androidx.compose.material.icons.filled.FormatItalic
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import com.storyarc.app.domain.manuscript.Style
import com.storyarc.app.domain.manuscript.StyleCodec
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconToggleButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.storyarc.app.data.db.NoteEntity
import com.storyarc.app.data.repo.StoryRepository.ColorTarget
import com.storyarc.app.data.repo.StoryRepository.TagTarget
import com.storyarc.app.domain.manuscript.Stats
import com.storyarc.app.ui.common.ConfirmDialog
import com.storyarc.board.Item
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.drop

/** Side panels of the scene editor. */
enum class ScenePanel { SameTime, World, Details }

/** Below this width the panels open as bottom sheets instead of a side pane. */
private val SidePaneMinWidth = 840.dp

/**
 * Full-screen manuscript editor for scene [nodeId]: card name, prose, live word and page
 * counts, and panels for what happens at the same time, the world library and the card's
 * tags and color. Prose saves as it is typed; closing the editor makes the whole session one
 * board undo step.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SceneEditor(
    vm: BoardViewModel,
    ui: BoardUi,
    nodeId: String,
    wordsPerPage: Int,
    onClose: () -> Unit,
) {
    val node = ui.nodes[nodeId]
    // The text as it was when the session began, kept across configuration changes.
    var loaded by rememberSaveable(nodeId) { mutableStateOf(false) }
    var beforeBody by rememberSaveable(nodeId) { mutableStateOf<String?>(null) }
    var beforeStyles by rememberSaveable(nodeId) { mutableStateOf("[]") }
    val prose = rememberSaveable(nodeId, saver = RichProse.Saver) { RichProse(TextFieldState()) }
    LaunchedEffect(nodeId) {
        if (loaded) return@LaunchedEffect
        val row = vm.sceneText(nodeId)
        beforeBody = row?.body
        beforeStyles = row?.styles ?: "[]"
        prose.load(row?.body.orEmpty(), StyleCodec.decode(row?.styles))
        vm.breakCoalescing()
        loaded = true
    }
    fun save() = vm.saveSceneDraft(nodeId, prose.state.text.toString(), StyleCodec.encode(prose.styles))
    // Undo points as the text or styles change; autosave shortly after a pause.
    LaunchedEffect(nodeId, loaded) {
        if (!loaded) return@LaunchedEffect
        snapshotFlow { prose.state.text.toString() to prose.styles }.drop(1).collectLatest {
            prose.record()
            delay(500)
            save()
        }
    }
    val isLoaded by rememberUpdatedState(loaded)
    DisposableEffect(nodeId) {
        // Leaving composition for any reason (including rotation) saves what is typed.
        onDispose { if (isLoaded) save() }
    }
    fun finish() {
        if (loaded) {
            save()
            vm.finishSceneEdit(nodeId, beforeBody?.let { vm.sceneRow(nodeId, it, beforeStyles) })
        }
    }
    fun close() { finish(); onClose() }
    BackHandler { close() }

    var panel by rememberSaveable { mutableStateOf<ScenePanel?>(null) }
    var openNote by rememberSaveable { mutableStateOf<String?>(null) }
    var more by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    val words by remember { derivedStateOf { Stats.words(prose.state.text) } }

    Scaffold(
        modifier = Modifier.imePadding(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        node?.text?.lineSequence()?.firstOrNull()?.takeIf { it.isNotBlank() } ?: "Untitled scene",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                navigationIcon = { IconButton(onClick = ::close) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Close scene") } },
                actions = {
                    IconButton(onClick = { prose.undo() }, enabled = prose.canUndo) {
                        Icon(Icons.AutoMirrored.Filled.Undo, "Undo typing")
                    }
                    IconButton(onClick = { prose.redo() }, enabled = prose.canRedo) {
                        Icon(Icons.AutoMirrored.Filled.Redo, "Redo typing")
                    }
                    PanelToggle(panel, ScenePanel.SameTime, Icons.Default.Schedule, "At the same time") { panel = it }
                    PanelToggle(panel, ScenePanel.World, Icons.AutoMirrored.Filled.MenuBook, "World library") { panel = it }
                    PanelToggle(panel, ScenePanel.Details, Icons.Default.Info, "Tags and color") { panel = it }
                    Box {
                        IconButton(onClick = { more = true }) { Icon(Icons.Default.MoreVert, "More") }
                        DropdownMenu(more, onDismissRequest = { more = false }) {
                            DropdownMenuItem(text = { Text("Copy to tray") }, onClick = { more = false; finish(); vm.copyNode(nodeId) })
                            if (ui.isPlaced(nodeId)) {
                                DropdownMenuItem(text = { Text("Move to tray") }, onClick = { more = false; finish(); vm.moveToTray(Item.Node(nodeId)); onClose() })
                            }
                            DropdownMenuItem(text = { Text("Delete everywhere") }, onClick = { more = false; confirmDelete = true })
                        }
                    }
                },
            )
        },
        bottomBar = {
            Column {
                FormatBar(prose)
                StatsBar(ui, nodeId, words, wordsPerPage)
            }
        },
    ) { padding ->
        BoxWithConstraints(Modifier.fillMaxSize().padding(padding)) {
            val wide = maxWidth >= SidePaneMinWidth
            Row(Modifier.fillMaxSize()) {
                Column(Modifier.weight(1f).fillMaxHeight()) {
                    if (node == null || !loaded) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                    } else {
                        CardNameField(vm, nodeId, node.text)
                        ProseField(prose, Modifier.weight(1f))
                    }
                }
                val p = panel
                if (wide && p != null) {
                    VerticalDivider()
                    Surface(Modifier.width(360.dp).fillMaxHeight(), tonalElevation = 1.dp) {
                        PanelContent(vm, ui, nodeId, p, openNote, { openNote = it }, onShowNote = { panel = ScenePanel.World; openNote = it }, onClosePanel = { panel = null })
                    }
                }
            }
            val p = panel
            if (!wide && p != null) {
                ModalBottomSheet(onDismissRequest = { panel = null }) {
                    Box(Modifier.fillMaxWidth().heightIn(min = 240.dp)) {
                        PanelContent(vm, ui, nodeId, p, openNote, { openNote = it }, onShowNote = { panel = ScenePanel.World; openNote = it }, onClosePanel = { panel = null })
                    }
                }
            }
        }
    }
    if (confirmDelete) {
        ConfirmDialog("Delete scene?", "The scene and its text are removed from every view. You can undo this.", "Delete", onDismiss = { confirmDelete = false }) {
            finish()
            vm.deleteNodeEverywhere(nodeId)
            onClose()
        }
    }
}

@Composable
private fun PanelToggle(current: ScenePanel?, which: ScenePanel, icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onChange: (ScenePanel?) -> Unit) {
    IconToggleButton(checked = current == which, onCheckedChange = { onChange(if (it) which else null) }) { Icon(icon, label) }
}

@Composable
private fun CardNameField(vm: BoardViewModel, nodeId: String, initial: String) {
    var name by remember(nodeId) { mutableStateOf(initial) }
    OutlinedTextField(
        value = name,
        onValueChange = { name = it; vm.editNodeText(nodeId, it) },
        label = { Text("Card name (chapter title)") },
        maxLines = 2,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

/** Bold and italic toggles for the selection, or for what is typed next. */
@Composable
private fun FormatBar(prose: RichProse) {
    Surface(tonalElevation = 1.dp) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconToggleButton(prose.isActive(Style.BOLD), { prose.toggle(Style.BOLD) }) { Icon(Icons.Default.FormatBold, "Bold") }
            IconToggleButton(prose.isActive(Style.ITALIC), { prose.toggle(Style.ITALIC) }) { Icon(Icons.Default.FormatItalic, "Italic") }
        }
    }
}

/**
 * The manuscript text area. Ctrl+B and Ctrl+I toggle styles; Ctrl+Z and Ctrl+Shift+Z / Ctrl+Y
 * go through [RichProse]'s undo so styles stay in step with the text.
 */
@Composable
fun ProseField(prose: RichProse, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val state = prose.state
    BasicTextField(
        state = state,
        inputTransformation = prose.input,
        outputTransformation = prose.output,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 20.dp, vertical = 8.dp)
            .testTag("prose")
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown || !e.isCtrlPressed) return@onPreviewKeyEvent false
                when (e.key) {
                    Key.B -> prose.toggle(Style.BOLD)
                    Key.I -> prose.toggle(Style.ITALIC)
                    Key.Z -> if (e.isShiftPressed) prose.redo() else prose.undo()
                    Key.Y -> prose.redo()
                    else -> return@onPreviewKeyEvent false
                }
                true
            },
        textStyle = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Serif, fontSize = 18.sp, lineHeight = 30.sp, color = colors.onSurface),
        cursorBrush = SolidColor(colors.primary),
        lineLimits = TextFieldLineLimits.MultiLine(),
        decorator = { inner ->
            Box {
                if (state.text.isEmpty()) {
                    Text(
                        "Write the scene…",
                        style = MaterialTheme.typography.bodyLarge.copy(fontFamily = FontFamily.Serif, fontSize = 18.sp),
                        color = colors.outline,
                    )
                }
                inner()
            }
        },
    )
}

@Composable
private fun StatsBar(ui: BoardUi, nodeId: String, words: Int, wordsPerPage: Int) {
    // The whole manuscript with this scene's live count in place of its saved one.
    val total = if (ui.isScene(nodeId)) ui.manuscriptWords() - (ui.sceneWords[nodeId] ?: 0) + words else null
    Surface(tonalElevation = 2.dp) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(Stats.summary(words, wordsPerPage), style = MaterialTheme.typography.labelLarge, modifier = Modifier.testTag("sceneStats"))
            if (total != null) {
                Text(
                    "Manuscript: " + Stats.summary(total, wordsPerPage),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun PanelContent(
    vm: BoardViewModel,
    ui: BoardUi,
    nodeId: String,
    panel: ScenePanel,
    openNote: String?,
    onOpenNote: (String?) -> Unit,
    onShowNote: (String) -> Unit,
    onClosePanel: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (panel) {
                    ScenePanel.SameTime -> "At the same time"
                    ScenePanel.World -> "World library"
                    ScenePanel.Details -> "Tags and color"
                },
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClosePanel) { Icon(Icons.Default.Close, "Close panel") }
        }
        HorizontalDivider()
        when (panel) {
            ScenePanel.SameTime -> SameTimePanel(ui, nodeId)
            ScenePanel.World -> WorldPanel(vm, ui, nodeId, openNote, onOpenNote)
            ScenePanel.Details -> Column(
                Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                val node = ui.nodes[nodeId] ?: return@Column
                TagsAndColor(vm, ui, ui.nodeTags[nodeId].orEmpty(), node.color, TagTarget.Node(nodeId), ColorTarget.Node(nodeId), onShowNote)
            }
        }
    }
}

/** What overlaps the scene on other timelines, and the marker it falls under. */
@Composable
private fun SameTimePanel(ui: BoardUi, nodeId: String) {
    val cell = ui.board.placements[nodeId]
    var preview by remember { mutableStateOf<String?>(null) }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(vertical = 8.dp)) {
        if (cell == null) {
            Text("This scene is in the tray, so nothing is at the same time.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
            return@Column
        }
        val marker = ui.board.markers.filterValues { it < cell.slot }.maxByOrNull { it.value }?.key
        marker?.let { m ->
            ListItem(
                overlineContent = { Text("After marker") },
                headlineContent = { Text(ui.markers[m]?.label.orEmpty()) },
            )
        }
        val concurrent = ui.board.concurrentWith(nodeId)
        if (concurrent.isEmpty()) {
            Text("Nothing on other timelines overlaps this scene.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        }
        val names = ui.timelines.associate { it.id to it.name }
        concurrent.groupBy { ui.board.placements.getValue(it).timelineId }.forEach { (t, ids) ->
            Text(
                names[t].orEmpty(),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 12.dp),
            )
            ids.forEach { id ->
                val n = ui.nodes[id] ?: return@forEach
                ListItem(
                    headlineContent = { Text(n.text.ifBlank { "Untitled" }, maxLines = 3, overflow = TextOverflow.Ellipsis) },
                    supportingContent = ui.nodeTags[id]?.mapNotNull { ui.tags[it]?.name }?.takeIf { it.isNotEmpty() }?.let { tags -> { Text(tags.joinToString(" · ")) } },
                    modifier = Modifier.clickable { preview = id },
                )
            }
        }
    }
    preview?.let { id ->
        val n = ui.nodes[id]
        com.storyarc.app.ui.common.AppAlertDialog(
            onDismissRequest = { preview = null },
            confirmButton = { TextButton(onClick = { preview = null }) { Text("Close") } },
            title = { Text(ui.timelines.find { it.id == ui.board.placements[id]?.timelineId }?.name.orEmpty()) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(n?.text?.ifBlank { "Untitled" }.orEmpty(), style = MaterialTheme.typography.bodyLarge)
                    val tags = ui.nodeTags[id].orEmpty().mapNotNull { ui.tags[it]?.name }
                    if (tags.isNotEmpty()) Text("Tags: " + tags.joinToString(", "), style = MaterialTheme.typography.bodySmall)
                    ui.sceneWords[id]?.let { Text("Has manuscript text (%,d words)".format(it), style = MaterialTheme.typography.bodySmall) }
                }
            },
        )
    }
}

/** Browse, search and edit the project's world library notes; notes linked from the scene's tags come first. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun WorldPanel(vm: BoardViewModel, ui: BoardUi, nodeId: String, openNote: String?, onOpenNote: (String?) -> Unit) {
    val notes by vm.projectNotes.collectAsStateWithLifecycle(emptyList())
    val open = openNote?.let { id -> notes.find { it.id == id } }
    if (open != null) {
        NoteEditor(vm, open, onBack = { onOpenNote(null) })
        return
    }
    var query by rememberSaveable { mutableStateOf("") }
    val linked = ui.nodeTags[nodeId].orEmpty().mapNotNull { ui.tags[it]?.linkedNoteId }.distinct()
        .mapNotNull { id -> notes.find { it.id == id } }
    val q = query.trim()
    val matches = notes.filter { q.isEmpty() || it.title.contains(q, true) || it.body.contains(q, true) }
        .sortedBy { it.title.lowercase() }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState())) {
        OutlinedTextField(
            query, { query = it },
            singleLine = true,
            placeholder = { Text("Search notes") },
            modifier = Modifier.fillMaxWidth().padding(16.dp),
        )
        if (linked.isNotEmpty() && q.isEmpty()) {
            Text("From this scene's tags", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(horizontal = 16.dp))
            linked.forEach { NoteRow(it) { onOpenNote(it.id) } }
            Text("All notes", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 12.dp))
        }
        if (notes.isEmpty()) Text("The project's world libraries have no notes yet.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        else if (matches.isEmpty()) Text("No notes match.", Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        matches.forEach { NoteRow(it) { onOpenNote(it.id) } }
    }
}

@Composable
private fun NoteRow(note: NoteEntity, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(note.title.ifBlank { "Untitled note" }) },
        supportingContent = note.body.takeIf { it.isNotBlank() }?.let { b -> { Text(b, maxLines = 2, overflow = TextOverflow.Ellipsis) } },
        leadingContent = { Icon(Icons.AutoMirrored.Filled.Notes, null) },
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NoteEditor(vm: BoardViewModel, note: NoteEntity, onBack: () -> Unit) {
    var title by remember(note.id) { mutableStateOf(note.title) }
    var body by remember(note.id) { mutableStateOf(note.body) }
    LaunchedEffect(note.id) { vm.breakCoalescing() }
    Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow { OutlinedButton(onClick = onBack) { Text("All notes") } }
        OutlinedTextField(title, { title = it; vm.editNote(note.id, title = it) }, singleLine = true, label = { Text("Title") }, modifier = Modifier.fillMaxWidth())
        OutlinedTextField(body, { body = it; vm.editNote(note.id, body = it) }, label = { Text("Note") }, modifier = Modifier.fillMaxWidth().heightIn(min = 200.dp))
    }
}
