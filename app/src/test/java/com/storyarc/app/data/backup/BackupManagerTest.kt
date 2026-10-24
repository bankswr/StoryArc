package com.storyarc.app.data.backup

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.storyarc.app.data.db.StoryDatabase
import com.storyarc.app.data.repo.StoryRepository
import com.storyarc.app.data.repo.StoryRepository.TagTarget
import com.storyarc.board.Drop
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class BackupManagerTest {
    private lateinit var db: StoryDatabase
    private lateinit var repo: StoryRepository
    private lateinit var backups: BackupManager
    private var ids = 0

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StoryDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = StoryRepository(db, clock = { 1000L }, newId = { "id${ids++}" })
        backups = BackupManager(db, repo, "test", clock = { 2000L }, newId = { "copy${ids++}" })
    }

    @After
    fun tearDown() = db.close()

    private val tables = listOf(
        "project", "world", "project_world", "board_view", "timeline", "view_timeline", "node", "node_group",
        "placement", "marker", "category", "note", "note_link", "tag", "node_tag", "marker_tag", "group_tag",
        "note_tag", "view_hidden_tag",
    )

    private fun snapshot(): Map<String, List<String>> = tables.associateWith { t ->
        db.openHelper.readableDatabase.query("SELECT * FROM $t").use { c ->
            buildList {
                while (c.moveToNext()) add((0 until c.columnCount).joinToString("|") { "${c.getColumnName(it)}=${c.getString(it)}" })
            }.sorted()
        }
    }

    private fun count(table: String) = snapshot().getValue(table).size

    private fun fkProblems() = db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { it.count }

    /** A project using every kind of row. */
    private suspend fun seed(): String {
        val p = repo.createProject("Saga")
        val v = repo.projects.views(p).single().id
        val t = repo.addTimeline(v, "Mara")
        val a = repo.addNode(v, "Leaves", Drop.Onto(t, 0))
        val b = repo.addNode(v, "Storm", Drop.Onto(t, 1))
        val g = repo.createGroup(v, listOf(a, b), "Voyage")
        val m = repo.addMarker(v, "Day 2", 1)
        val w = repo.createWorld(p, "Lore")
        val cat = repo.createCategory(p, w, "Magic")
        val n1 = repo.createNote(p, w, "Blood", cat)
        val n2 = repo.createNote(p, w, "History")
        repo.linkNotes(p, n1, n2)
        val tag = repo.createTag(p, "Magic", 0xFF00FF00.toInt(), worldId = w)
        repo.updateTag(p, repo.tags.tag(tag)!!.copy(linkedNoteId = n1))
        val pov = repo.createTag(p, "POV")
        repo.setTags(p, TagTarget.Node(a), listOf(tag, pov))
        repo.setTags(p, TagTarget.Marker(m), listOf(pov))
        repo.setTags(p, TagTarget.Group(g), listOf(tag))
        repo.setTags(p, TagTarget.Note(n2), listOf(pov))
        repo.setTagHidden(v, pov, true)
        repo.createView(p, "Second", from = v, import = StoryRepository.ViewImport.TIMELINES_AND_NODES)
        return p
    }

    private suspend fun exported(projectIds: List<String>? = null): ByteArray =
        ByteArrayOutputStream().also { backups.export(it, projectIds) }.toByteArray()

    @Test
    fun roundTripIntoEmptyDatabase() = runBlocking {
        seed()
        val before = snapshot()
        val bytes = exported()
        for (t in tables.reversed()) db.openHelper.writableDatabase.execSQL("DELETE FROM $t")
        assertEquals(0, count("node"))
        backups.import(backups.read(ByteArrayInputStream(bytes)), ImportMode.REPLACE)
        assertEquals(before, snapshot())
        assertEquals(0, fkProblems())
    }

    @Test
    fun replaceRestoresEarlierState() = runBlocking {
        val p = seed()
        val before = snapshot()
        val bytes = exported(listOf(p))
        val v = repo.projects.views(p).first().id
        repo.addNode(v, "after backup", Drop.Onto(repo.board.viewTimelines(v).first().timelineId, 0))
        val lore = db.backup().worlds().single { it.name == "Lore" }.id
        repo.deleteNote(p, repo.worlds.notes(lore).first().id)
        backups.import(backups.read(ByteArrayInputStream(bytes)), ImportMode.REPLACE)
        assertEquals(before, snapshot())
        assertEquals(0, fkProblems())
        assertTrue(!repo.undoManager(p).state.value.canUndo)
    }

    @Test
    fun copyImportsBesideOriginalAndReusesWorlds() = runBlocking {
        val p = seed()
        val bytes = exported(listOf(p))
        val worldsBefore = count("world")
        backups.import(backups.read(ByteArrayInputStream(bytes)), ImportMode.COPY)
        assertEquals(2, count("project"))
        assertEquals(worldsBefore, count("world"))
        assertEquals(2 * 2, count("board_view"))
        val copy = db.backup().projects().single { it.id != p }
        assertEquals("Saga (copy)", copy.name)
        assertNotEquals(db.backup().nodes(p).map { it.id }.toSet(), db.backup().nodes(copy.id).map { it.id }.toSet())
        // The copy's nodes still carry the shared world tag and its own copied project tag.
        assertEquals(db.backup().nodeTags(p).size, db.backup().nodeTags(copy.id).size)
        assertEquals(0, fkProblems())
    }

    @Test
    fun rejectsNonBackups() {
        assertThrows(BackupFormatException::class.java) { backups.read(ByteArrayInputStream("nope".toByteArray())) }
    }
}
