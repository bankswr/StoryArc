package com.storyarc.app.data.repo

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.storyarc.app.data.db.StoryDatabase
import com.storyarc.board.Drop
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Scenes timeline designation and manuscript text. */
@RunWith(AndroidJUnit4::class)
class SceneRepositoryTest {
    private lateinit var db: StoryDatabase
    private lateinit var repo: StoryRepository
    private var ids = 0
    private lateinit var p: String
    private lateinit var v: String
    private lateinit var scenes: String
    private lateinit var mara: String

    @Before
    fun setUp() = runBlocking {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), StoryDatabase::class.java)
            .allowMainThreadQueries().build()
        repo = StoryRepository(db, clock = { 1000L }, newId = { "id${ids++}" })
        p = repo.createProject("Book")
        v = repo.projects.views(p).single().id
        scenes = repo.addTimeline(v, "Scenes")
        mara = repo.addTimeline(v, "Mara")
    }

    @After
    fun tearDown() = db.close()

    private suspend fun scenesTimelines() = repo.projects.timelines(p).filter { it.isScenes }.map { it.id }

    @Test
    fun oneScenesTimelinePerProject() = runBlocking {
        repo.setScenesTimeline(p, scenes)
        assertEquals(listOf(scenes), scenesTimelines())
        repo.setScenesTimeline(p, mara)
        assertEquals(listOf(mara), scenesTimelines())
        repo.undo(p)
        assertEquals(listOf(scenes), scenesTimelines())
        repo.setScenesTimeline(p, null)
        assertEquals(emptyList<String>(), scenesTimelines())
    }

    @Test
    fun anEditingSessionIsOneUndoStep() = runBlocking {
        val n = repo.addNode(v, "Arrival", Drop.Onto(scenes, 0))
        val before = repo.sceneText(n)
        repo.saveSceneDraft(n, "It was")
        repo.saveSceneDraft(n, "It was a dark night.")
        repo.finishSceneEdit(n, before)
        assertEquals(5, repo.sceneText(n)!!.words)
        assertEquals("Edit scene", repo.undoManager(p).state.value.undoLabel)
        repo.undo(p)
        assertNull(repo.sceneText(n))
        repo.redo(p)
        assertEquals("It was a dark night.", repo.sceneText(n)!!.body)

        // A second session; clearing the text removes the row.
        val second = repo.sceneText(n)
        repo.saveSceneDraft(n, "")
        repo.finishSceneEdit(n, second)
        assertNull(repo.sceneText(n))
        repo.undo(p)
        assertEquals("It was a dark night.", repo.sceneText(n)!!.body)
    }

    @Test
    fun anUnchangedSessionAddsNoStep() = runBlocking {
        val n = repo.addNode(v, "Arrival", Drop.Onto(scenes, 0))
        repo.saveSceneDraft(n, "Text")
        repo.finishSceneEdit(n, null)
        val label = repo.undoManager(p).state.value
        val before = repo.sceneText(n)
        repo.saveSceneDraft(n, "Text")
        repo.finishSceneEdit(n, before)
        assertEquals(label, repo.undoManager(p).state.value)
    }

    @Test
    fun deletingAndUndoingKeepsTheText() = runBlocking {
        val n = repo.addNode(v, "Arrival", Drop.Onto(scenes, 0))
        repo.saveSceneDraft(n, "Prose survives.")
        repo.finishSceneEdit(n, null)
        repo.deleteNodeEverywhere(n)
        assertNull(repo.sceneText(n))
        repo.undo(p)
        assertEquals("Prose survives.", repo.sceneText(n)!!.body)
        // Deleting the whole timeline too.
        repo.deleteTimelineEverywhere(scenes)
        assertNull(repo.sceneText(n))
        repo.undo(p)
        assertEquals("Prose survives.", repo.sceneText(n)!!.body)
    }

    @Test
    fun copiesCarryTheText() = runBlocking {
        val n = repo.addNode(v, "Arrival", Drop.Onto(scenes, 0))
        repo.saveSceneDraft(n, "Copied prose.")
        repo.finishSceneEdit(n, null)
        val copy = repo.copyNode(v, n)
        assertEquals("Copied prose.", repo.sceneText(copy)!!.body)
        // Independent of the original.
        repo.saveSceneDraft(copy, "Changed.")
        assertEquals("Copied prose.", repo.sceneText(n)!!.body)
    }

    @Test
    fun boardWordCounts() = runBlocking {
        val a = repo.addNode(v, "A", Drop.Onto(scenes, 0))
        val b = repo.addNode(v, "B", Drop.Onto(scenes, 1))
        repo.saveSceneDraft(a, "one two three")
        repo.saveSceneDraft(b, "four five")
        assertEquals(mapOf(a to 3, b to 2), repo.board.sceneWordsInView(v).associate { it.nodeId to it.words })
    }
}
