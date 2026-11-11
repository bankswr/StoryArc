package com.storyarc.app.ui

import android.content.Intent
import androidx.activity.compose.setContent
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.storyarc.app.MainActivity
import com.storyarc.app.StoryArcApp
import com.storyarc.app.data.repo.StoryRepository
import com.storyarc.app.domain.manuscript.Assembler
import com.storyarc.app.ui.board.BoardScreen
import com.storyarc.app.ui.theme.StoryArcTheme
import com.storyarc.board.Drop
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w891dp-h411dp-xxhdpi")
class AssembleTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private lateinit var repo: StoryRepository
    private lateinit var project: String
    private lateinit var view: String

    /** "My Novel": scenes Two (slot 1) and One (slot 0) on the scenes timeline, one in the tray. */
    private fun seed() {
        repo = (compose.activity.application as StoryArcApp).container.repository
        project = runBlocking {
            val p = repo.createProject("My Novel")
            view = repo.projects.views(p).single().id
            val scenes = repo.addTimeline(view, "Scenes")
            repo.addTimeline(view, "Mara")
            repo.setScenesTimeline(p, scenes)
            val two = repo.addNode(view, "Two", Drop.Onto(scenes, 0))
            val one = repo.addNode(view, "One\nnotes", Drop.Onto(scenes, 0))
            repo.saveSceneDraft(one, "First words here.")
            repo.saveSceneDraft(two, "Second.")
            val loose = repo.addNodeToTray(view, "Tray scene")
            repo.saveSceneDraft(loose, "Not assembled.")
            p
        }
    }

    @Test
    fun assemblesScenesInViewOrderSkippingTheTray() = runBlocking {
        seed()
        val m = repo.manuscript(view, "Ada Quill")
        assertEquals("My Novel", m.title)
        assertEquals(listOf("One" to "First words here.", "Two" to "Second."), m.chapters.map { it.title to it.body })
        assertEquals("Ada Quill", m.author)
        assertEquals(4, m.words)
    }

    @Test
    fun assembleButtonOpensDialogAndSuggestsFileName() {
        seed()
        compose.activity.setContent { StoryArcTheme { BoardScreen(project, view, onBack = {}, onTimelines = {}) } }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Two")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasContentDescription("Assemble manuscript")).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("2 chapters", substring = true)).fetchSemanticsNodes().isNotEmpty() }
        // Enabled once the project name has loaded (a Room read), which can be slow on a busy test machine.
        compose.waitUntil(15_000) {
            compose.onAllNodes(hasText("Save .odt") and androidx.compose.ui.test.isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasText("Save .odt")).performClick()
        compose.waitForIdle()
        val intent = shadowOf(compose.activity).nextStartedActivityForResult.intent
        assertEquals(Intent.ACTION_CREATE_DOCUMENT, intent.action)
        assertEquals(Assembler.fileName("My Novel", LocalDate.now()), intent.getStringExtra(Intent.EXTRA_TITLE))
        assertEquals("My_Novel_" + LocalDate.now().toString().replace('-', '_') + ".odt", intent.getStringExtra(Intent.EXTRA_TITLE))
    }
}
