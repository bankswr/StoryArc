package com.storyarc.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.click
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.storyarc.app.MainActivity
import com.storyarc.app.StoryArcApp
import com.storyarc.app.data.repo.StoryRepository
import com.storyarc.app.ui.board.BoardScreen
import com.storyarc.app.ui.theme.StoryArcTheme
import com.storyarc.board.Drop
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class SceneEditorTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var repo: StoryRepository
    private lateinit var project: String
    private lateinit var view: String
    private lateinit var scene: String

    /** Scenes: Arrival — Mara: Waits at the dock (same slot). */
    private fun open() {
        repo = (compose.activity.application as StoryArcApp).container.repository
        project = runBlocking {
            val p = repo.createProject("Book")
            view = repo.projects.views(p).single().id
            val scenes = repo.addTimeline(view, "Scenes")
            val mara = repo.addTimeline(view, "Mara")
            repo.setScenesTimeline(p, scenes)
            scene = repo.addNode(view, "Arrival", Drop.Onto(scenes, 0))
            repo.addNode(view, "Waits at the dock", Drop.Onto(mara, 0))
            p
        }
        compose.activity.setContent { StoryArcTheme { BoardScreen(project, view, onBack = {}, onTimelines = {}) } }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Arrival")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun eventually(check: () -> Unit) {
        val deadline = System.currentTimeMillis() + 5_000
        while (true) {
            try {
                return check()
            } catch (e: AssertionError) {
                if (System.currentTimeMillis() > deadline) throw e
                Thread.sleep(25)
                compose.waitForIdle()
            }
        }
    }

    private fun exists(text: String) = compose.onAllNodes(hasText(text, substring = true)).fetchSemanticsNodes().isNotEmpty()

    @Test
    fun writeASceneThenUndoTheSession() {
        open()
        // The header shows the manuscript total, the card its word count.
        assertEquals(true, exists("0 w · 0 pp"))
        compose.onNode(hasText("Arrival")).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("prose")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("prose").performTextInput("It was a dark night.")
        compose.waitUntil(5_000) { exists("5 words · 1 page") }

        compose.onNode(hasContentDescription("At the same time")).performClick()
        compose.waitUntil(5_000) { exists("Waits at the dock") }

        compose.onNode(hasContentDescription("Close scene")).performClick()
        compose.waitUntil(5_000) { exists("5 words") && !exists("5 words · 1 page") }
        eventually {
            assertEquals("It was a dark night.", runBlocking { repo.sceneText(scene) }?.body)
            assertEquals("Edit scene", repo.undoManager(project).state.value.undoLabel)
        }
        compose.waitUntil(5_000) { exists("5 w · 1 pp") }

        compose.onNode(hasContentDescription("Undo Edit scene")).performClick()
        eventually { assertNull(runBlocking { repo.sceneText(scene) }) }
    }

    @Test
    fun tappingAnEmptyCellOnTheScenesTimelineOpensTheEditor() {
        open()
        // The empty cell right of Arrival (header 104dp, columns 132dp, at xxhdpi).
        val b = compose.onAllNodes(hasText("Arrival")).fetchSemanticsNodes().first().boundsInRoot
        compose.onRoot().performTouchInput { click(androidx.compose.ui.geometry.Offset(b.left - 36f + 396f + 198f, b.top + 40f)) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasTestTag("prose")).fetchSemanticsNodes().isNotEmpty() }
        assertEquals(true, exists("Untitled scene"))
    }
}
