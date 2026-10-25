package com.storyarc.app.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.storyarc.app.data.db.StoryDatabase
import com.storyarc.app.data.repo.StoryRepository.TagTarget
import com.storyarc.board.Drop
import com.storyarc.board.Item
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrayTest {
    private lateinit var db: StoryDatabase
    private lateinit var repo: StoryRepository
    private var ids = 0
    private lateinit var p: String
    private lateinit var v: String
    private lateinit var a: String
    private lateinit var b: String

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StoryDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = StoryRepository(db, clock = { 1000L }, newId = { "id${ids++}" })
        p = repo.createProject("Saga")
        v = repo.projects.views(p).single().id
        a = repo.addTimeline(v, "A")
        b = repo.addTimeline(v, "B")
    }

    @After
    fun tearDown() = db.close()

    private suspend fun layout() = ViewRows.load(repo.board, v).toLayout()

    @Test
    fun newNodeGoesToTrayAndPlaces() = runBlocking {
        val n = repo.addNodeToTray(v)
        assertEquals(listOf(Item.Node(n)), layout().tray.map { it.item })
        assertTrue(layout().board.placements.isEmpty())
        assertTrue(repo.board.nodesInView(v).any { it.id == n })
        repo.placeFromTray(v, Item.Node(n), Drop.Onto(b, 0))
        assertEquals(b, layout().board.placements.getValue(n).timelineId)
        assertTrue(layout().tray.isEmpty())
    }

    @Test
    fun blankNewNodeIsRetractedWithoutTrace() = runBlocking {
        repo.addNode(v, "keep", Drop.Onto(a, 0))
        val before = layout()
        val undoBefore = repo.undoManager(p).state.value
        val n = repo.addNodeToTray(v)
        repo.discardNewNode(v, n)
        assertNull(repo.projects.node(n))
        assertEquals(before, layout())
        assertEquals(undoBefore, repo.undoManager(p).state.value)
    }

    @Test
    fun editedNewNodeIsDeletedUndoably() = runBlocking {
        val n = repo.addNodeToTray(v)
        repo.setNodeText(n, "x")
        repo.setNodeText(n, "")
        repo.discardNewNode(v, n)
        assertNull(repo.projects.node(n))
        repo.undo(p)
        assertEquals("", repo.projects.node(n)?.text)
    }

    @Test
    fun groupRoundTripsThroughTray() = runBlocking {
        val x = repo.addNode(v, "x", Drop.Onto(a, 0))
        val y = repo.addNode(v, "y", Drop.Onto(a, 1))
        repo.addNode(v, "z", Drop.Onto(b, 2))
        val outer = repo.createGroup(v, listOf(x, y), "Outer")
        val inner = repo.createGroup(v, listOf(y), "Inner")
        repo.moveToTray(v, Item.GroupItem(outer))
        val tray = layout().tray.single()
        assertEquals(mapOf(outer to null, inner to outer), tray.groups)
        assertNull(repo.board.groups(v).single { it.id == outer }.timelineId)
        assertEquals("Outer", repo.board.groups(v).single { it.id == outer }.label)

        repo.placeFromTray(v, Item.GroupItem(outer), Drop.Onto(b, 0))
        val board = layout().board
        assertEquals(listOf(inner, outer), board.groupChain(y))
        assertEquals(b, repo.board.groups(v).single { it.id == inner }.timelineId)
    }

    @Test
    fun trayReorders() = runBlocking {
        val n1 = repo.addNodeToTray(v, "1")
        val n2 = repo.addNodeToTray(v, "2")
        val n3 = repo.addNodeToTray(v, "3")
        repo.moveToTray(v, Item.Node(n1), 3)
        assertEquals(listOf(n2, n3, n1), layout().tray.map { it.nodes.keys.single() })
        repo.moveToTray(v, Item.Node(n1), 0)
        assertEquals(listOf(n1, n2, n3), layout().tray.map { it.nodes.keys.single() })
    }

    @Test
    fun copyNodeIsIndependentWithTags() = runBlocking {
        val n = repo.addNode(v, "Storm", Drop.Onto(a, 0))
        val tag = repo.createTag(p, "Danger")
        repo.setTags(p, TagTarget.Node(n), listOf(tag))
        val c = repo.copyNode(v, n)
        assertNotEquals(n, c)
        assertEquals("Storm", repo.projects.node(c)?.text)
        assertEquals(listOf(tag), repo.tags.nodeTags(c).map { it.tagId })
        assertEquals(listOf(Item.Node(c)), layout().tray.map { it.item })
        repo.setNodeText(c, "Calm")
        assertEquals("Storm", repo.projects.node(n)?.text)
    }

    @Test
    fun copyGroupKeepsShapeLabelsAndTags() = runBlocking {
        val x = repo.addNode(v, "x", Drop.Onto(a, 0))
        repo.addNode(v, "w", Drop.Onto(b, 1))
        val y = repo.addNode(v, "y", Drop.Onto(a, 2))
        val g = repo.createGroup(v, listOf(x, y), "Voyage")
        val tag = repo.createTag(p, "Act I")
        repo.setTags(p, TagTarget.Group(g), listOf(tag))
        val copy = repo.copyGroup(v, g)
        val block = layout().tray.single()
        assertEquals(copy, block.root)
        assertEquals(listOf(0, 2), block.nodes.values.sorted())
        assertEquals(setOf("x", "y"), block.nodes.keys.map { repo.projects.node(it)!!.text }.toSet())
        assertTrue(block.nodes.keys.none { it == x || it == y })
        assertEquals("Voyage", repo.board.groups(v).single { it.id == copy }.label)
        assertEquals(listOf(tag), repo.tags.groupTags(copy).map { it.tagId })
        // The original is untouched.
        assertEquals(listOf(x, y), layout().board.nodesOf(g))
    }

    @Test
    fun deleteGroupWithNodesEverywhereAndUndo() = runBlocking {
        val x = repo.addNode(v, "x", Drop.Onto(a, 0))
        val y = repo.addNode(v, "y", Drop.Onto(a, 1))
        val g = repo.createGroup(v, listOf(x, y))
        val other = repo.createView(p, "Other", from = v, import = StoryRepository.ViewImport.TIMELINES_AND_NODES)
        val before = ViewRows.load(repo.board, v) to ViewRows.load(repo.board, other)
        repo.deleteGroupWithNodes(v, g)
        assertNull(repo.projects.node(x))
        assertTrue(repo.board.placements(other).isEmpty())
        assertTrue(repo.board.groups(other).isEmpty())
        repo.undo(p)
        assertEquals(before, ViewRows.load(repo.board, v) to ViewRows.load(repo.board, other))
    }

    @Test
    fun removingFromViewCountsTrayElsewhere() = runBlocking {
        val n = repo.addNode(v, "x", Drop.Onto(a, 0))
        val other = repo.createView(p, "Other", from = v, import = StoryRepository.ViewImport.TIMELINES_AND_NODES)
        repo.moveToTray(other, Item.Node(n))
        assertFalse(repo.isLastPlacement(v, n))
        repo.removeNodeFromView(v, n)
        assertEquals("x", repo.projects.node(n)?.text)
        assertTrue(repo.isLastPlacement(other, n))
        repo.removeNodeFromView(other, n)
        assertNull(repo.projects.node(n))
    }

    @Test
    fun deletingViewDeletesTrayOnlyNodes() = runBlocking {
        val other = repo.createView(p, "Other")
        val n = repo.addNodeToTray(other, "loose")
        repo.deleteView(other)
        assertNull(repo.projects.node(n))
        repo.undo(p)
        assertEquals(listOf(Item.Node(n)), ViewRows.load(repo.board, other).toTray().map { it.item })
    }

    @Test
    fun blankCheckKeepsNodesWithContent() = runBlocking {
        val typed = repo.addNodeToTray(v)
        repo.setNodeText(typed, "x")
        assertFalse(repo.discardNewNodeIfBlank(v, typed))
        val tagged = repo.addNodeToTray(v)
        repo.setTags(p, TagTarget.Node(tagged), listOf(repo.createTag(p, "T")))
        assertFalse(repo.discardNewNodeIfBlank(v, tagged))
        val blank = repo.addNodeToTray(v)
        assertTrue(repo.discardNewNodeIfBlank(v, blank))
        assertNull(repo.projects.node(blank))
        assertEquals(2, layout().tray.size)
    }
}
