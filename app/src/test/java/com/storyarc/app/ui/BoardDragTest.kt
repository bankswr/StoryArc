package com.storyarc.app.ui

import androidx.activity.compose.setContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Drives long-press drags on a real board. Landscape so several columns are on screen.
 * Layout at xxhdpi: node columns 396px, marker columns 120px.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w891dp-h411dp-xxhdpi")
class BoardDragTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val slotPx = 396f
    private val markerPx = 120f
    private lateinit var repo: StoryRepository
    private lateinit var view: String

    /** Mara: Leaves Storm |m| Reunion — Joss: Waits . |m| Reunion2 */
    private fun open() {
        repo = (compose.activity.application as StoryArcApp).container.repository
        val p = runBlocking {
            val p = repo.createProject("Drag")
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

    private fun centerOf(text: String): Offset {
        val b = compose.onAllNodes(hasText(text)).fetchSemanticsNodes().first().boundsInRoot
        // Text starts 36px into its column; columns are 396px wide.
        return Offset(b.left - 36f + slotPx / 2, b.top + 40f)
    }

    private fun slotOf(text: String): Pair<String, Int> = runBlocking {
        val node = repo.board.nodesInView(view).single { it.text == text }
        val p = repo.board.placements(view).single { it.nodeId == node.id }
        repo.projects.timeline(p.timelineId)!!.name to p.slot
    }

    /** Retries [check] while the board's writes (background Room work posted back to main) settle. */
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

    private fun markerSlot() = runBlocking { repo.board.markers(view).single().slot }

    /** Touch events in one performTouchInput are batched, so the long-press wait needs the clock advanced between calls. */
    private fun longPressAndMove(from: Offset, to: Offset) {
        compose.onRoot().performTouchInput { down(from) }
        compose.mainClock.advanceTimeBy(800)
        compose.onRoot().performTouchInput {
            val steps = 12
            for (i in 1..steps) {
                moveTo(from + (to - from) * (i / steps.toFloat()))
                advanceEventTime(16)
            }
        }
    }

    private fun release() {
        compose.onRoot().performTouchInput { up() }
        compose.waitForIdle()
    }

    @Test
    fun dragOntoEmptyCellMovesNode() {
        open()
        val waits = centerOf("Waits")
        longPressAndMove(waits, waits + Offset(slotPx, 0f))
        release()
        eventually { assertEquals("Joss" to 1, slotOf("Waits")) }
    }

    @Test
    fun dragAcrossTimelinesPushesThatTimeline() {
        open()
        val waits = centerOf("Waits")
        val leaves = centerOf("Leaves")
        longPressAndMove(waits, leaves)
        release()
        eventually {
            assertEquals("Mara" to 0, slotOf("Waits"))
            assertEquals("Mara" to 1, slotOf("Leaves"))
            // Storm is pushed into a slot inserted before the marker rather than across it.
            assertEquals("Mara" to 2, slotOf("Storm"))
            assertEquals(3, markerSlot())
        }
    }

    @Test
    fun markerBlocksUntilHeld() {
        open()
        val storm = centerOf("Storm")
        val beyond = storm + Offset(slotPx / 2 + markerPx + slotPx / 2, 0f)
        longPressAndMove(storm, beyond)
        release()
        // Give a (wrong) drop time to land before checking that nothing crossed.
        Thread.sleep(200); compose.waitForIdle()
        assertTrue("quick drag must not cross", slotOf("Storm").second < markerSlot())

        longPressAndMove(centerOf("Storm"), beyond)
        // Hold against the marker: the dwell is measured in pointer event time.
        compose.onRoot().performTouchInput {
            advanceEventTime(700)
            moveBy(Offset(2f, 0f))
            advanceEventTime(16)
            moveBy(Offset(2f, 0f))
        }
        release()
        eventually { assertTrue("held drag crosses", slotOf("Storm").second > markerSlot()) }
    }

    @Test
    fun markerCanBeDragged() {
        open()
        val label = compose.onAllNodes(hasText("Day 2")).fetchSemanticsNodes().first().boundsInRoot.center
        longPressAndMove(label, centerOf("Leaves") - Offset(slotPx / 2 - 20f, 0f))
        release()
        eventually { assertEquals(0, markerSlot()) }
    }

    @Test
    fun groupSelectedNodesThenDragGroupByItsBar() {
        open()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Select nodes").performClick()
        compose.onNodeWithText("Leaves").performClick()
        compose.onNodeWithText("Storm").performClick()
        compose.onNodeWithText("2 · Ready to group").assertExists()
        compose.onNodeWithText("Group").performClick()
        compose.onNode(hasSetTextAction()).performTextReplacement("Voyage")
        compose.onNode(hasText("Group") and hasClickAction() and hasAnyAncestor(isDialog())).performClick()
        eventually { assertEquals(1, runBlocking { repo.board.groups(view) }.size) }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Voyage")).fetchSemanticsNodes().isNotEmpty() }

        // The bar sits in the strip at the bottom of the Mara row (row 264px tall, text 36px below its top).
        val leaves = centerOf("Leaves")
        val textTop = compose.onAllNodes(hasText("Leaves")).fetchSemanticsNodes().first().boundsInRoot.top
        val bar = Offset(leaves.x, textTop - 36f + 264f - 10f)
        longPressAndMove(bar, bar + Offset(0f, 264f))
        release()
        eventually {
            assertEquals("Joss" to 0, slotOf("Leaves"))
            assertEquals("Joss" to 1, slotOf("Storm"))
            assertEquals("Joss" to 2, slotOf("Waits"))
            assertEquals(3, markerSlot())
            val g = runBlocking { repo.board.groups(view) }.single()
            assertEquals("Joss", runBlocking { repo.projects.timeline(g.timelineId!!)!!.name })
        }
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun accessibilityActionsMoveWithoutDragging() {
        open()
        compose.onNode(hasText("Waits")).performCustomAccessibilityActionWithLabel("Move later")
        // Inserted as a new column just before the marker.
        eventually {
            assertEquals("Joss" to 2, slotOf("Waits"))
            assertEquals(3, markerSlot())
        }
        compose.onNode(hasText("Waits")).performCustomAccessibilityActionWithLabel("Move to Mara")
        eventually { assertEquals("Mara", slotOf("Waits").first) }
    }
}
