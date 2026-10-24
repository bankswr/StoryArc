package com.storyarc.app.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.storyarc.app.MainActivity
import com.storyarc.app.StoryArcApp
import com.storyarc.app.data.repo.StoryRepository
import com.storyarc.app.ui.board.BoardScreen
import com.storyarc.app.ui.theme.StoryArcTheme
import com.storyarc.board.Drop
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * The board with the vertical layout setting: timelines are columns and slots run downward.
 * Layout at xxhdpi: header row 144px, node slots 264px tall, timeline columns 396px wide.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class VerticalBoardTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val slotPx = 264f
    private lateinit var repo: StoryRepository
    private lateinit var view: String

    private val container get() = (compose.activity.application as StoryArcApp).container

    /** Mara: Leaves Storm |m| Reunion — Joss: Waits . |m| Reunion2 */
    private fun open() {
        repo = container.repository
        runBlocking { container.settingsRepository.setVerticalBoard(true) }
        compose.waitUntil(5_000) { container.settings.value.verticalBoard }
        val p = runBlocking {
            val p = repo.createProject("Vertical")
            view = repo.projects.views(p).single().id
            val mara = repo.addTimeline(view, "Mara")
            val joss = repo.addTimeline(view, "Joss")
            repo.addNode(view, "Leaves", Drop.Onto(mara, 0))
            repo.addNode(view, "Storm", Drop.Onto(mara, 1))
            repo.addNode(view, "Waits", Drop.Onto(joss, 0))
            repo.addMarker(view, "Day 2", 2)
            repo.addNode(view, "Reunion", Drop.Onto(mara, 3))
            repo.addNode(view, "Reunion2", Drop.Onto(joss, 3))
            p
        }
        compose.activity.setContent { StoryArcTheme { BoardScreen(p, view, onBack = {}, onTimelines = {}) } }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Reunion2")).fetchSemanticsNodes().isNotEmpty() }
    }

    /** The settings DataStore outlives a test, so put the default back. */
    @After
    fun resetLayout() = runBlocking { container.settingsRepository.setVerticalBoard(false) }

    private fun centerOf(text: String): Offset {
        val b = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().first().boundsInRoot
        // Text starts 36px into its card on both axes; cards are 396×264px.
        return Offset(b.left - 36f + 198f, b.top - 36f + slotPx / 2)
    }

    private fun slotOf(text: String): Pair<String, Int> = runBlocking {
        val node = repo.board.nodesInView(view).single { it.text == text }
        val p = repo.board.placements(view).single { it.nodeId == node.id }
        repo.projects.timeline(p.timelineId)!!.name to p.slot
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

    private fun drag(from: Offset, to: Offset) {
        compose.onRoot().performTouchInput { down(from) }
        compose.mainClock.advanceTimeBy(800)
        compose.onRoot().performTouchInput {
            for (i in 1..12) {
                moveTo(from + (to - from) * (i / 12f))
                advanceEventTime(16)
            }
        }
        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()
    }

    @Test
    fun timelinesAreColumns() {
        open()
        val leaves = centerOf("Leaves")
        val storm = centerOf("Storm")
        val waits = centerOf("Waits")
        assertEquals("next slot is below", leaves.x, storm.x, 1f)
        assertEquals(slotPx, storm.y - leaves.y, 1f)
        assertEquals("next timeline is to the right", leaves.y, waits.y, 1f)
        assertEquals(396f, waits.x - leaves.x, 1f)
    }

    @Test
    fun dragDownMovesAlongTimeline() {
        open()
        val waits = centerOf("Waits")
        drag(waits, waits + Offset(0f, slotPx))
        eventually { assertEquals("Joss" to 1, slotOf("Waits")) }
    }

    @Test
    fun dragSidewaysChangesTimeline() {
        open()
        drag(centerOf("Waits"), centerOf("Leaves"))
        eventually {
            assertEquals("Mara" to 0, slotOf("Waits"))
            assertEquals("Mara" to 1, slotOf("Leaves"))
            assertEquals("Mara" to 2, slotOf("Storm"))
        }
    }

    /** Writes app/build/screenshots/board-vertical.png for visual review. */
    @Test
    fun screenshot() {
        open()
        runBlocking {
            val ids = repo.board.nodesInView(view).filter { it.text in setOf("Leaves", "Storm") }.map { it.id }
            repo.createGroup(view, ids, "Voyage")
        }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Voyage")).fetchSemanticsNodes().isNotEmpty() }
        val dir = File("build/screenshots").apply { mkdirs() }
        val decor = compose.activity.window.decorView
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        compose.runOnUiThread { decor.draw(Canvas(bitmap)) }
        File(dir, "board-vertical.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }
}
