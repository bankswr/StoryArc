package com.storyarc.app.data.backup

import androidx.room.withTransaction
import com.storyarc.app.data.db.BackupDao
import com.storyarc.app.data.db.CategoryEntity
import com.storyarc.app.data.db.GroupTagEntity
import com.storyarc.app.data.db.MarkerEntity
import com.storyarc.app.data.db.MarkerTagEntity
import com.storyarc.app.data.db.NodeEntity
import com.storyarc.app.data.db.NodeGroupEntity
import com.storyarc.app.data.db.NodeTagEntity
import com.storyarc.app.data.db.NoteEntity
import com.storyarc.app.data.db.NoteLinkEntity
import com.storyarc.app.data.db.NoteTagEntity
import com.storyarc.app.data.db.PlacementEntity
import com.storyarc.app.data.db.ProjectEntity
import com.storyarc.app.data.db.ProjectWorldEntity
import com.storyarc.app.data.db.StoryDatabase
import com.storyarc.app.data.db.TagEntity
import com.storyarc.app.data.db.TimelineEntity
import com.storyarc.app.data.db.ViewEntity
import com.storyarc.app.data.db.ViewHiddenTagEntity
import com.storyarc.app.data.db.ViewTimelineEntity
import com.storyarc.app.data.db.WorldEntity
import com.storyarc.app.data.db.rowKey
import com.storyarc.app.data.repo.StoryRepository
import com.storyarc.app.domain.undo.Change
import com.storyarc.app.domain.undo.ChangeSet
import com.storyarc.app.domain.undo.diffRows
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
data class Manifest(
    val formatVersion: Int,
    val appVersion: String,
    val exportedAt: Long,
    val projects: List<String>,
    val worlds: List<String>,
)

/** Every row owned by one project. */
@Serializable
data class ProjectBundle(
    val project: ProjectEntity,
    val worldLinks: List<ProjectWorldEntity>,
    val views: List<ViewEntity>,
    val timelines: List<TimelineEntity>,
    val viewTimelines: List<ViewTimelineEntity>,
    val nodes: List<NodeEntity>,
    val groups: List<NodeGroupEntity>,
    val placements: List<PlacementEntity>,
    val markers: List<MarkerEntity>,
    val tags: List<TagEntity>,
    val nodeTags: List<NodeTagEntity>,
    val markerTags: List<MarkerTagEntity>,
    val groupTags: List<GroupTagEntity>,
    val hiddenTags: List<ViewHiddenTagEntity>,
) {
    fun rows(): List<Any> = listOf(project) + worldLinks + views + timelines + viewTimelines + nodes + groups +
        placements + markers + tags + nodeTags + markerTags + groupTags + hiddenTags
}

/** Every row owned by one world library. */
@Serializable
data class WorldBundle(
    val world: WorldEntity,
    val categories: List<CategoryEntity>,
    val notes: List<NoteEntity>,
    val noteLinks: List<NoteLinkEntity>,
    val tags: List<TagEntity>,
    val noteTags: List<NoteTagEntity>,
) {
    fun rows(): List<Any> = listOf(world) + categories + notes + noteLinks + tags + noteTags
}

data class Backup(val manifest: Manifest, val projects: List<ProjectBundle>, val worlds: List<WorldBundle>)

class BackupFormatException(message: String) : Exception(message)

/** How to treat projects and world libraries in a backup that already exist on this device. */
enum class ImportMode {
    /** Make the existing ones match the backup. */
    REPLACE,

    /** Import conflicting projects as new copies; existing world libraries are kept and reused. */
    COPY,
}

/**
 * Zip-of-JSON backups. Layout: `manifest.json`, `projects/<id>.json`, `worlds/<id>.json`.
 * Rows are the Room entities themselves; [FORMAT_VERSION] guards the layout.
 */
class BackupManager(
    private val db: StoryDatabase,
    private val repo: StoryRepository,
    private val appVersion: String,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {
    private val dao: BackupDao = db.backup()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun projectBundle(p: String) = ProjectBundle(
        requireNotNull(dao.project(p)), dao.projectWorlds(p), dao.views(p), dao.timelines(p), dao.viewTimelines(p),
        dao.nodes(p), dao.groups(p), dao.placements(p), dao.markers(p), dao.projectTags(p), dao.nodeTags(p),
        dao.markerTags(p), dao.groupTags(p), dao.hiddenTags(p),
    )

    suspend fun worldBundle(w: String) = WorldBundle(
        requireNotNull(dao.world(w)), dao.categories(w), dao.notes(w), dao.noteLinks(w), dao.worldTags(w), dao.noteTags(w),
    )

    /** Writes a backup of [projectIds] (with the worlds they link), or of everything when null. */
    suspend fun export(out: OutputStream, projectIds: List<String>? = null) {
        val (projects, worlds) = db.withTransaction {
            val ids = projectIds ?: dao.projects().map { it.id }
            val projects = ids.map { projectBundle(it) }
            val worldIds = if (projectIds == null) dao.worlds().map { it.id } else projects.flatMap { b -> b.worldLinks.map { it.worldId } }.distinct()
            projects to worldIds.map { worldBundle(it) }
        }
        val manifest = Manifest(FORMAT_VERSION, appVersion, clock(), projects.map { it.project.id }, worlds.map { it.world.id })
        ZipOutputStream(out).use { zip ->
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray())
                zip.closeEntry()
            }
            put("manifest.json", json.encodeToString(Manifest.serializer(), manifest))
            projects.forEach { put("projects/${it.project.id}.json", json.encodeToString(ProjectBundle.serializer(), it)) }
            worlds.forEach { put("worlds/${it.world.id}.json", json.encodeToString(WorldBundle.serializer(), it)) }
        }
    }

    /** Parses and checks a backup without touching the database. */
    fun read(input: InputStream): Backup {
        val entries = HashMap<String, String>()
        ZipInputStream(input).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (!e.isDirectory) entries[e.name] = zip.readBytes().decodeToString()
            }
        }
        val manifest = entries["manifest.json"]?.let { runCatching { json.decodeFromString(Manifest.serializer(), it) }.getOrNull() }
            ?: throw BackupFormatException("Not a StoryArc backup (no readable manifest).")
        if (manifest.formatVersion > FORMAT_VERSION) {
            throw BackupFormatException("This backup was made by a newer version of StoryArc.")
        }
        fun <T> load(path: String, s: kotlinx.serialization.KSerializer<T>): T =
            entries[path]?.let { runCatching { json.decodeFromString(s, it) }.getOrNull() }
                ?: throw BackupFormatException("The backup is damaged ($path is missing or unreadable).")
        return Backup(
            manifest,
            manifest.projects.map { load("projects/$it.json", ProjectBundle.serializer()) },
            manifest.worlds.map { load("worlds/$it.json", WorldBundle.serializer()) },
        )
    }

    /** Which of the backup's projects and worlds already exist here. */
    suspend fun conflicts(backup: Backup): Pair<List<ProjectBundle>, List<WorldBundle>> =
        backup.projects.filter { dao.project(it.project.id) != null } to backup.worlds.filter { dao.world(it.world.id) != null }

    /**
     * Imports [backup] in one transaction. Existing rows are synced to the backup (REPLACE) or
     * left alone (COPY). References the backup can't satisfy (tags or notes it doesn't contain
     * and the device doesn't have) are dropped. Clears all undo history.
     */
    suspend fun import(backup: Backup, mode: ImportMode) {
        db.withTransaction {
            val existingWorlds = backup.worlds.filter { dao.world(it.world.id) != null }.map { it.world.id }.toSet()
            val existingProjects = backup.projects.filter { dao.project(it.project.id) != null }.map { it.project.id }.toSet()

            val worlds = when (mode) {
                ImportMode.REPLACE -> backup.worlds
                ImportMode.COPY -> backup.worlds.filter { it.world.id !in existingWorlds }
            }
            val projects = backup.projects.map { b ->
                if (mode == ImportMode.COPY && b.project.id in existingProjects) asCopy(b) else b
            }
            // Rows currently on the device that this import replaces.
            val replacedWorlds = worlds.filter { it.world.id in existingWorlds }.map { worldBundle(it.world.id) }
            val replacedProjects = projects.filter { it.project.id in existingProjects }.map { projectBundle(it.project.id) }

            val oldRows = replacedWorlds.flatMap { it.rows() } + replacedProjects.flatMap { it.rows() }
            val removedTagIds = oldRows.filterIsInstance<TagEntity>().map { it.id }.toSet()
            val removedNoteIds = oldRows.filterIsInstance<NoteEntity>().map { it.id }.toSet()
            val incoming = worlds.flatMap { it.rows() } + projects.flatMap { it.rows() }
            val tagIds = (dao.allTagIds().toSet() - removedTagIds) + incoming.filterIsInstance<TagEntity>().map { it.id }
            val noteIds = (dao.allNoteIds().toSet() - removedNoteIds) + incoming.filterIsInstance<NoteEntity>().map { it.id }
            val worldIds = dao.worlds().map { it.id }.toSet() + worlds.map { it.world.id }
            val newRows = incoming.mapNotNull { row ->
                when (row) {
                    is NodeTagEntity -> row.takeIf { it.tagId in tagIds }
                    is MarkerTagEntity -> row.takeIf { it.tagId in tagIds }
                    is GroupTagEntity -> row.takeIf { it.tagId in tagIds }
                    is NoteTagEntity -> row.takeIf { it.tagId in tagIds }
                    is ViewHiddenTagEntity -> row.takeIf { it.tagId in tagIds }
                    is ProjectWorldEntity -> row.takeIf { it.worldId in worldIds }
                    is TagEntity -> if (row.linkedNoteId != null && row.linkedNoteId !in noteIds) row.copy(linkedNoteId = null) else row
                    else -> row
                }
            }
            val changes = diffRows(oldRows, newRows, ::rowKey).toMutableList()
            // Tags elsewhere on the device that point at notes this import removes lose the reference.
            val goneNotes = removedNoteIds - noteIds
            if (goneNotes.isNotEmpty()) {
                val touched = changes.mapNotNull { (it.after as? TagEntity)?.id }.toSet()
                db.worlds().tagsLinking(goneNotes.toList()).filter { it.id !in touched }
                    .forEach { changes += Change(it, it.copy(linkedNoteId = null)) }
            }
            repo.write(ChangeSet("Import backup", changes))
        }
        repo.clearAllUndo()
    }

    /** The bundle with every project-owned id replaced, so it can sit beside the original. */
    private fun asCopy(b: ProjectBundle): ProjectBundle {
        val ids = HashMap<String, String>()
        fun m(id: String) = ids.getOrPut(id) { newId() }
        fun mo(id: String?) = id?.let { ids[it] ?: it }
        // Register every owned id first so references resolve regardless of order.
        m(b.project.id)
        (b.views.map { it.id } + b.timelines.map { it.id } + b.nodes.map { it.id } + b.groups.map { it.id } +
            b.markers.map { it.id } + b.tags.map { it.id }).forEach { m(it) }
        val p = b.project.id
        return ProjectBundle(
            project = b.project.copy(id = m(p), name = b.project.name + " (copy)", updatedAt = clock()),
            worldLinks = b.worldLinks.map { it.copy(projectId = m(p)) },
            views = b.views.map { it.copy(id = m(it.id), projectId = m(p)) },
            timelines = b.timelines.map { it.copy(id = m(it.id), projectId = m(p)) },
            viewTimelines = b.viewTimelines.map { it.copy(viewId = m(it.viewId), timelineId = m(it.timelineId)) },
            nodes = b.nodes.map { it.copy(id = m(it.id), projectId = m(p)) },
            groups = b.groups.map { it.copy(id = m(it.id), viewId = m(it.viewId), timelineId = m(it.timelineId), parentId = mo(it.parentId)) },
            placements = b.placements.map { it.copy(viewId = m(it.viewId), nodeId = m(it.nodeId), timelineId = m(it.timelineId), groupId = mo(it.groupId)) },
            markers = b.markers.map { it.copy(id = m(it.id), viewId = m(it.viewId)) },
            tags = b.tags.map { it.copy(id = m(it.id), projectId = m(p)) },
            nodeTags = b.nodeTags.map { it.copy(targetId = m(it.targetId), tagId = mo(it.tagId)!!) },
            markerTags = b.markerTags.map { it.copy(targetId = m(it.targetId), tagId = mo(it.tagId)!!) },
            groupTags = b.groupTags.map { it.copy(targetId = m(it.targetId), tagId = mo(it.tagId)!!) },
            hiddenTags = b.hiddenTags.map { it.copy(viewId = m(it.viewId), tagId = mo(it.tagId)!!) },
        )
    }

    companion object {
        const val FORMAT_VERSION = 1
    }
}
