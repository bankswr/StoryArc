package com.storyarc.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.storyarc.app.MainActivity
import com.storyarc.app.StoryArcApp
import com.storyarc.app.data.repo.StoryRepository
import com.storyarc.app.data.repo.ViewRows
import com.storyarc.app.ui.board.BoardScreen
import com.storyarc.app.ui.theme.StoryArcTheme
import com.storyarc.board.Drop
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The tray, the floating new node and the trash, on a portrait board (tray along the bottom).
 * Layout at xxhdpi: node columns 396px, timeline rows 264px, board cards' text 36px in.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class TrayBoardTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val slotPx = 396f
    private lateinit var repo: StoryRepository
    private lateinit var project: String
    private lateinit var view: String

    /** Mara: Leaves Storm — Joss: Waits; tray: Loose */
    private fun open() {
        repo = (compose.activity.application as StoryArcApp).container.repository
        project = runBlocking {
            val p = repo.createProject("Tray")
            view = repo.projects.views(p).single().id
            val mara = repo.addTimeline(view, "Mara")
            val joss = repo.addTimeline(view, "Joss")
            repo.addNode(view, "Leaves", Drop.Onto(mara, 0))
            repo.addNode(view, "Storm", Drop.Onto(mara, 1))
            repo.addNode(view, "Waits", Drop.Onto(joss, 0))
            repo.addNodeToTray(view, "Loose")
            p
        }
        compose.activity.setContent { StoryArcTheme { BoardScreen(project, view, onBack = {}, onTimelines = {}) } }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Loose")).fetchSemanticsNodes().isNotEmpty() }
    }

    private fun bounds(text: String) = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().first().boundsInRoot

    /** Center of a board card from its text. */
    private fun boardCard(text: String) = bounds(text).let { Offset(it.left - 36f + slotPx / 2, it.top + 40f) }

    /** Center of a tray card from its text (24px in; cards are 384px wide). */
    private fun trayCard(text: String) = bounds(text).let { Offset(it.left - 24f + 192f, it.top + 30f) }

    private fun layout() = runBlocking { ViewRows.load(repo.board, view).toLayout() }

    private fun nodeId(text: String) = runBlocking { repo.board.nodesInView(view).single { it.text == text }.id }

    private fun slotOf(text: String): Pair<String, Int>? = runBlocking {
        val p = repo.board.placements(view).singleOrNull { it.nodeId == nodeId(text) } ?: return@runBlocking null
        repo.projects.timeline(p.timelineId)!!.name to p.slot
    }

    private fun inTray(text: String) = layout().tray.any { nodeId(text) in it.nodes }

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

    private fun press(at: Offset) {
        compose.onRoot().performTouchInput { down(at) }
        compose.mainClock.advanceTimeBy(800)
    }

    private fun moveTo(from: Offset, to: Offset) {
        compose.onRoot().performTouchInput {
            for (i in 1..12) {
                moveTo(from + (to - from) * (i / 12f))
                advanceEventTime(16)
            }
        }
    }

    private fun release() {
        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()
    }

    @Test
    fun addButtonEditsThenFloatingCardIsDraggedOntoTheBoard() {
        open()
        compose.onNodeWithContentDescription("Add node").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("New node")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction()).performTextInput("Plan")
        compose.onNodeWithText("Done").performClick()
        compose.waitForIdle()
        eventually { assertEquals(1, compose.onAllNodes(hasText("Plan")).fetchSemanticsNodes().size) }
        assertTrue("new nodes start in the tray", inTray("Plan"))

        // The floating card moves on a plain drag, no long press.
        val card = bounds("Plan").let { Offset(it.left + 60f, it.top + 30f) }
        val target = boardCard("Waits") + Offset(slotPx, 0f)
        compose.onRoot().performTouchInput { down(card) }
        moveTo(card, target)
        release()
        eventually { assertEquals("Joss" to 1, slotOf("Plan")) }
    }

    @Test
    fun blankNewNodeIsDiscarded() {
        open()
        val undo = repo.undoManager(project).state.value
        compose.onNodeWithContentDescription("Add node").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("New node")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Done").performClick()
        eventually {
            assertEquals(4, runBlocking { repo.board.nodesInView(view).size })
            assertEquals(undo, repo.undoManager(project).state.value)
        }
    }

    @Test
    fun dragBoardNodeIntoTrayAndBack() {
        open()
        val storm = boardCard("Storm")
        press(storm)
        moveTo(storm, trayCard("Loose") + Offset(250f, 0f))
        release()
        eventually {
            assertNull(slotOf("Storm"))
            assertTrue(inTray("Storm"))
        }
        // Dropped after Loose.
        assertEquals(listOf(nodeId("Loose"), nodeId("Storm")), layout().tray.map { it.nodes.keys.single() })
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Storm")).fetchSemanticsNodes().isNotEmpty() }

        val tray = trayCard("Storm")
        press(tray)
        val target = boardCard("Waits") + Offset(slotPx, 0f)
        moveTo(tray, target)
        release()
        eventually { assertEquals("Joss" to 1, slotOf("Storm")) }
    }

    @Test
    fun trashDeletesAndSnackbarUndoes() {
        open()
        val waits = boardCard("Waits")
        press(waits)
        // The trash appears once the node is picked up.
        compose.onRoot().performTouchInput { moveBy(Offset(0f, 30f)); advanceEventTime(16) }
        compose.waitForIdle()
        val trash = compose.onNode(hasContentDescription("Trash")).fetchSemanticsNode().boundsInRoot.center
        moveTo(waits + Offset(0f, 30f), trash)
        release()
        eventually { assertTrue(runBlocking { repo.board.nodesInView(view).none { it.text == "Waits" } }) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Undo")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Undo").performClick()
        eventually { assertEquals("Joss" to 0, slotOf("Waits")) }
    }

    @Test
    fun copyFromEditorGoesToTray() {
        open()
        val leaves = boardCard("Leaves")
        compose.onRoot().performTouchInput { click(leaves) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Copy")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Copy").performClick()
        eventually {
            val copies = runBlocking { repo.board.nodesInView(view).filter { it.text == "Leaves" } }
            assertEquals(2, copies.size)
            assertTrue(layout().tray.any { b -> copies.any { it.id in b.nodes } })
        }
    }

    @Test
    fun trayCollapsesAndExpands() {
        open()
        compose.onNode(hasText("Tray · 1")).performClick()
        compose.waitForIdle()
        eventually { assertTrue(compose.onAllNodes(hasText("Loose")).fetchSemanticsNodes().isEmpty()) }
        compose.onNode(hasText("Tray · 1")).performClick()
        eventually { assertTrue(compose.onAllNodes(hasText("Loose")).fetchSemanticsNodes().isNotEmpty()) }
    }

    /** Landscape puts the tray along the right edge. */
    @Test
    @Config(qualifiers = "w891dp-h411dp-xxhdpi")
    fun sideTrayInLandscape() {
        open()
        val tray = bounds("Loose")
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        assertTrue("tray is at the right", tray.left > root.width * 0.7f)
        val storm = boardCard("Storm")
        press(storm)
        moveTo(storm, trayCard("Loose") + Offset(0f, 200f))
        release()
        eventually { assertTrue(inTray("Storm")) }
    }
}
