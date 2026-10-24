package com.storyarc.app.ui

import android.graphics.Bitmap
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasText
import android.graphics.Canvas
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.activity.compose.setContent
import com.storyarc.app.MainActivity
import com.storyarc.app.StoryArcApp
import com.storyarc.app.data.repo.StoryRepository
import com.storyarc.app.ui.board.BoardScreen
import com.storyarc.app.ui.theme.StoryArcTheme
import com.storyarc.board.Drop
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Renders key screens to PNGs under `app/build/screenshots` for visual review. Not an
 * assertion test; it fails only if rendering throws.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class BoardScreenshotTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private fun save(name: String) {
        compose.waitForIdle()
        val dir = File("build/screenshots").apply { mkdirs() }
        // captureToImage() cannot force a redraw under Robolectric; draw the window directly.
        val view = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { view.draw(Canvas(bitmap)) }
        File(dir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    /** Seeds a small story and returns (projectId, viewId). */
    private fun seed(): Pair<String, String> = runBlocking {
        val repo = (compose.activity.application as StoryArcApp).container.repository
        val p = repo.createProject("The Long Road")
        val v = repo.projects.views(p).single().id
        val mara = repo.addTimeline(v, "Mara")
        val joss = repo.addTimeline(v, "Joss")
        val phase = repo.addTimeline(v, "Act")
        repo.addNode(v, "Act I: Departure", Drop.Onto(phase, 0))
        val leaves = repo.addNode(v, "Leaves the harbour at dawn", Drop.Onto(mara, 0))
        val storm = repo.addNode(v, "Storm; ship runs aground", Drop.Onto(mara, 1))
        repo.createGroup(v, listOf(leaves, storm), "Voyage")
        repo.createGroup(v, listOf(storm), "")
        val danger = repo.createTag(p, "Danger", color = 0xFFE57373.toInt())
        val pov = repo.createTag(p, "POV", color = 0xFF64B5F6.toInt())
        repo.setTags(p, StoryRepository.TagTarget.Node(storm), listOf(danger, pov))
        repo.setColor(p, StoryRepository.ColorTarget.Timeline(mara), 0xFF64B5F6.toInt())
        repo.addNode(v, "Waits at the inn", Drop.Onto(joss, 0))
        repo.addNode(v, "", Drop.Onto(joss, 2))
        repo.addMarker(v, "Day 2", 2)
        repo.addNode(v, "Reunion", Drop.Onto(mara, 3))
        repo.addNode(v, "Reunion", Drop.Onto(joss, 3))
        p to v
    }

    @Test
    @Config(qualifiers = "w891dp-h411dp-xxhdpi")
    fun boardLandscape() {
        val (p, v) = seed()
        compose.activity.setContent { StoryArcTheme { BoardScreen(p, v, onBack = {}, onTimelines = {}) } }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Reunion")).fetchSemanticsNodes().isNotEmpty() }
        save("board-landscape")
    }

    @Test
    fun board() {
        val (p, v) = seed()
        compose.activity.setContent { StoryArcTheme { BoardScreen(p, v, onBack = {}, onTimelines = {}) } }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Waits at the inn")).fetchSemanticsNodes().isNotEmpty() }
        save("board")
    }
}
