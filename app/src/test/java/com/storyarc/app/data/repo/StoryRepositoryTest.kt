package com.storyarc.app.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.storyarc.app.data.db.StoryDatabase
import com.storyarc.board.Drop
import com.storyarc.board.Item
import com.storyarc.board.groupingProblem
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.random.Random

@RunWith(AndroidJUnit4::class)
class StoryRepositoryTest {
    private lateinit var db: StoryDatabase
    private lateinit var repo: StoryRepository
    private var ids = 0

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StoryDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = StoryRepository(db, clock = { 1000L }, newId = { "id${ids++}" })
    }

    @After
    fun tearDown() = db.close()

    private val tables = listOf(
        "project", "world", "project_world", "board_view", "timeline", "view_timeline", "node", "node_group",
        "placement", "marker", "tag", "node_tag", "marker_tag", "group_tag",
    )

    /** Every row of every table, rendered, for whole-database comparisons. */
    private fun snapshot(): Map<String, List<String>> = tables.associateWith { t ->
        db.openHelper.readableDatabase.query("SELECT * FROM $t").use { c ->
            buildList {
                while (c.moveToNext()) add((0 until c.columnCount).joinToString("|") { "${c.getColumnName(it)}=${c.getString(it)}" })
            }.sorted()
        }
    }.mapValues { (t, rows) -> if (t == "project") rows.map { it.replace(Regex("updatedAt=\\d+"), "") } else rows }

    private fun foreignKeyProblems(): Int =
        db.openHelper.readableDatabase.query("PRAGMA foreign_key_check").use { it.count }

    private suspend fun mainView(projectId: String) = repo.projects.views(projectId).single().id

    @Test
    fun createProjectMakesWorldAndView() = runBlocking {
        val p = repo.createProject("Saga")
        assertEquals("Main", repo.projects.views(p).single().name)
        assertEquals(1, snapshot().getValue("project_world").size)
        val fkOn = db.openHelper.readableDatabase.query("PRAGMA foreign_keys").use { it.moveToFirst(); it.getInt(0) }
        assertEquals("foreign keys enforced", 1, fkOn)
    }

    @Test
    fun boardEditsUndoAndRedo() = runBlocking {
        val p = repo.createProject("Saga")
        val v = mainView(p)
        val start = snapshot()
        val a = repo.addTimeline(v, "Mara")
        val b = repo.addTimeline(v, "Joss")
        val n1 = repo.addNode(v, "Arrives", Drop.Onto(a, 0))
        val n2 = repo.addNode(v, "Leaves", Drop.Onto(a, 1))
        repo.addNode(v, "Waits", Drop.Onto(b, 0))
        repo.addMarker(v, "Day 1", 0)
        repo.move(v, Item.Node(n1), Drop.Between(b, 3))
        val g = repo.createGroup(v, listOf(n2))
        repo.move(v, Item.GroupItem(g), Drop.Onto(b, 1))
        val end = snapshot()
        assertEquals(0, foreignKeyProblems())

        repeat(9) { repo.undo(p) }
        assertEquals(start, snapshot())
        assertFalse(repo.undoManager(p).state.value.canUndo)
        repeat(9) { repo.redo(p) }
        assertEquals(end, snapshot())
    }

    @Test
    fun ungroupWithNestedGroupKeepsChild() = runBlocking {
        val p = repo.createProject("Saga")
        val v = mainView(p)
        val t = repo.addTimeline(v, "A")
        val nodes = (0..2).map { repo.addNode(v, "n$it", Drop.Onto(t, it)) }
        val outer = repo.createGroup(v, nodes)
        val inner = repo.createGroup(v, nodes.drop(1))
        val before = snapshot()
        repo.ungroup(v, outer)
        val groups = repo.board.groups(v)
        assertEquals(listOf(inner), groups.map { it.id })
        assertNull(groups.single().parentId)
        assertEquals(2, repo.board.placements(v).count { it.groupId == inner })
        repo.undo(p)
        assertEquals(before, snapshot())
    }

    @Test
    fun removingLastPlacementDeletesNode() = runBlocking {
        val p = repo.createProject("Saga")
        val v = mainView(p)
        val t = repo.addTimeline(v, "A")
        val n = repo.addNode(v, "x", Drop.Onto(t, 0))
        assertTrue(repo.isLastPlacement(v, n))
        repo.removeNodeFromView(v, n)
        assertNull(repo.projects.node(n))
        repo.undo(p)
        assertEquals("x", repo.projects.node(n)?.text)
    }

    @Test
    fun textEditsCoalesce() = runBlocking {
        val p = repo.createProject("Saga")
        val v = mainView(p)
        val t = repo.addTimeline(v, "A")
        val n = repo.addNode(v, "", Drop.Onto(t, 0))
        val node = repo.projects.node(n)!!
        for (text in listOf("H", "He", "Hello")) repo.updateNode(node.copy(text = text))
        repo.undo(p)
        assertEquals("", repo.projects.node(n)?.text)
        repo.redo(p)
        assertEquals("Hello", repo.projects.node(n)?.text)
    }

    @Test
    fun deleteProjectRemovesExclusiveWorld() = runBlocking {
        val p = repo.createProject("Saga")
        repo.createProject("Other")
        repo.deleteProject(p)
        assertEquals(1, snapshot().getValue("world").size)
        assertEquals(1, snapshot().getValue("project").size)
    }

    @Test
    fun randomEditsUndoToStart() = runBlocking {
        repeat(6) { seed ->
            val rnd = Random(seed)
            val p = repo.createProject("P$seed")
            val v = mainView(p)
            val start = snapshot()
            val tls = listOf(repo.addTimeline(v, "A"), repo.addTimeline(v, "B"))
            repeat(50) {
                val placements = repo.board.placements(v)
                val board = ViewRows.load(repo.board, v).toBoard()
                val t = board.timelines.random(rnd)
                val drop = if (rnd.nextBoolean()) Drop.Between(t, rnd.nextInt(board.slotCount + 1)) else Drop.Onto(t, rnd.nextInt(board.slotCount + 1))
                when (rnd.nextInt(8)) {
                    0, 1 -> repo.addNode(v, "n", drop)
                    2 -> placements.randomOrNull(rnd)?.let { repo.move(v, Item.Node(it.nodeId), drop) } ?: return@repeat
                    3 -> repo.addMarker(v, "m", rnd.nextInt(board.slotCount + 1))
                    4 -> placements.randomOrNull(rnd)?.let { repo.removeNodeFromView(v, it.nodeId) } ?: return@repeat
                    5 -> {
                        val line = board.nodesOn(t)
                        if (line.size < 2) return@repeat
                        val run = line.take(2)
                        if (board.groupingProblem(run) != null) return@repeat
                        repo.createGroup(v, run)
                    }
                    6 -> repo.board.groups(v).randomOrNull(rnd)?.let {
                        if (rnd.nextBoolean()) repo.ungroup(v, it.id) else repo.move(v, Item.GroupItem(it.id), drop)
                    } ?: return@repeat
                    7 -> repo.moveTimeline(v, tls.random(rnd), rnd.nextInt(2))
                }
                assertEquals("seed $seed", 0, foreignKeyProblems())
                assertEquals(emptyList<String>(), ViewRows.load(repo.board, v).toBoard().violations())
            }
            while (repo.undoManager(p).state.value.canUndo) repo.undo(p)
            assertEquals("seed $seed", start, snapshot())
            repo.deleteProject(p)
        }
    }
}

@RunWith(AndroidJUnit4::class)
class ViewsTest {
    private lateinit var db: StoryDatabase
    private lateinit var repo: StoryRepository
    private var ids = 0

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StoryDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = StoryRepository(db, clock = { 1000L }, newId = { "id${ids++}" })
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun importedNodesAreSharedButPositionedIndependently() = runBlocking {
        val p = repo.createProject("Saga")
        val main = repo.projects.views(p).single().id
        val t = repo.addTimeline(main, "A")
        val a = repo.addNode(main, "a", Drop.Onto(t, 0))
        val b = repo.addNode(main, "b", Drop.Onto(t, 1))
        repo.createGroup(main, listOf(a, b), "pair")
        repo.addMarker(main, "Day 1", 0)

        val reading = repo.createView(p, "Reading order", from = main, import = StoryRepository.ViewImport.TIMELINES_AND_NODES)
        assertEquals(2, repo.board.placements(reading).size)
        assertEquals(1, repo.board.groups(reading).size)
        assertEquals(1, repo.board.markers(reading).size)
        assertTrue(repo.board.markers(reading).single().id != repo.board.markers(main).single().id)

        // Moving in one view leaves the other alone; editing text shows in both.
        repo.move(reading, Item.Node(b), Drop.Between(t, 1))
        assertEquals(2, repo.board.placements(main).single { it.nodeId == b }.slot)
        repo.updateNode(repo.projects.node(a)!!.copy(text = "A!"))
        assertEquals("A!", repo.board.nodesInView(reading).single { it.id == a }.text)

        // Removing from one view keeps the node; removing from the last deletes it.
        assertFalse(repo.isLastPlacement(reading, a))
        repo.removeNodeFromView(reading, a)
        assertEquals("A!", repo.projects.node(a)?.text)
        assertTrue(repo.isLastPlacement(main, a))
    }

    @Test
    fun timelinesOnlyImportAndDeleteViewWithUndo() = runBlocking {
        val p = repo.createProject("Saga")
        val main = repo.projects.views(p).single().id
        val t = repo.addTimeline(main, "A")
        repo.addNode(main, "a", Drop.Onto(t, 0))
        val second = repo.createView(p, "Second", from = main, import = StoryRepository.ViewImport.TIMELINES)
        assertEquals(listOf(t), repo.board.viewTimelines(second).map { it.timelineId })
        assertEquals(0, repo.board.placements(second).size)
        val n = repo.addNode(second, "only here", Drop.Onto(t, 0))

        repo.deleteView(second)
        assertNull(repo.projects.node(n))
        assertEquals(1, repo.projects.views(p).size)
        assertTrue(repo.projects.timeline(t) != null)
        repo.undo(p)
        assertEquals("only here", repo.projects.node(n)?.text)
        assertEquals(2, repo.projects.views(p).size)
    }
}
