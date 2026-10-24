package com.storyarc.app.ui

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.storyarc.app.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationFlowTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun createProjectAndAddTimeline() {
        compose.onNodeWithText("New project", useUnmergedTree = true).performClick()
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("Saga")
        compose.onNodeWithText("Create").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Main")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Main").performClick()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Timelines").performClick()
        compose.onNodeWithText("Add timeline", useUnmergedTree = true).performClick()
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("Mara")
        compose.onNodeWithText("Add").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Mara")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("0 nodes").assertExists()
        compose.onNodeWithContentDescription("Undo Add timeline").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Mara")).fetchSemanticsNodes().isEmpty() }
    }

    @Test
    /** Text dialogs over the board must lay out stably (an unconstrained field once looped). */
    fun boardDialogAcceptsInput() {
        compose.onNodeWithText("New project", useUnmergedTree = true).performClick()
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("Saga")
        compose.onNodeWithText("Create").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Main")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Main").performClick()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Timelines").performClick()
        compose.onNodeWithText("Add timeline", useUnmergedTree = true).performClick()
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("Mara")
        compose.onNodeWithText("Add").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(androidx.compose.ui.test.hasText("Mara")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Add marker").performClick()
        compose.onNode(androidx.compose.ui.test.hasSetTextAction()).performTextInput("Day")
    }

    @Test
    fun createViewImportingTimelinesAndSwitch() {
        compose.onNodeWithText("New project", useUnmergedTree = true).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Saga")
        compose.onNodeWithText("Create").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Main")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Main").performClick()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Timelines").performClick()
        compose.onNodeWithText("Add timeline", useUnmergedTree = true).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Mara")
        compose.onNodeWithText("Add").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithContentDescription("Back").performClick()

        compose.onNodeWithText("New view").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Reading order")
        compose.onNodeWithText("Timelines").performClick()
        compose.onNodeWithText("Create").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Reading order")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Mara")).fetchSemanticsNodes().isNotEmpty() }

        compose.onNodeWithText("Reading order").performClick()
        compose.onNode(hasText("Main") and hasClickAction()).performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Main")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Reading order").assertDoesNotExist()
    }

    @Test
    fun worldNoteWithReferencingTag() {
        compose.onNodeWithText("New project", useUnmergedTree = true).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Saga")
        compose.onNodeWithText("Create").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("World libraries")).fetchSemanticsNodes().isNotEmpty() }
        // The default world library carries the project's name.
        compose.onNode(hasText("Saga") and hasClickAction()).performClick()
        compose.onNodeWithText("New note", useUnmergedTree = true).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Bloodbinding")
        compose.onNodeWithText("Create").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Notes")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(hasSetTextAction() and hasText("Notes")).performTextInput("Costs a memory.")
        compose.onNodeWithText("Create a tag for this note").performScrollTo().performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Bloodbinding")).fetchSemanticsNodes().size >= 2 }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Costs a memory.")).fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Tags").performClick()
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("refers to Bloodbinding", substring = true)).fetchSemanticsNodes().isNotEmpty() }
    }
}
