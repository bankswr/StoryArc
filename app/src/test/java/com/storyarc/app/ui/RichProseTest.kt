package com.storyarc.app.ui

import androidx.activity.compose.setContent
import com.storyarc.app.MainActivity
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.test.performClick
import androidx.compose.ui.text.TextRange
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.storyarc.app.domain.manuscript.Style
import com.storyarc.app.domain.manuscript.StyleRange
import com.storyarc.app.ui.board.ProseField
import com.storyarc.app.ui.board.RichProse
import com.storyarc.app.ui.theme.StoryArcTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RichProseTest {
    @get:Rule
    val compose = createAndroidComposeRule<MainActivity>()

    private val prose = RichProse(TextFieldState())

    private fun show() {
        prose.load("", emptyList())
        compose.activity.setContent { StoryArcTheme { ProseField(prose) } }
        compose.onNodeWithTag("prose").performClick()
    }

    private fun b(s: Int, e: Int) = StyleRange(s, e, Style.BOLD)
    private fun i(s: Int, e: Int) = StyleRange(s, e, Style.ITALIC)

    @Test
    fun selectionToggleAndTypingCarriesTheStyle() {
        show()
        compose.onNodeWithTag("prose").performTextInput("Hello world")
        compose.onNodeWithTag("prose").performTextInputSelection(TextRange(0, 5))
        compose.runOnIdle { prose.toggle(Style.BOLD) }
        assertEquals(listOf(b(0, 5)), prose.styles)
        assertTrue(prose.isActive(Style.BOLD))
        // Typing right after a bold word continues it.
        compose.onNodeWithTag("prose").performTextInputSelection(TextRange(5))
        compose.onNodeWithTag("prose").performTextInput("!")
        assertEquals(listOf(b(0, 6)), prose.styles)
        // Edits before a styled run shift it.
        compose.onNodeWithTag("prose").performTextInputSelection(TextRange(0))
        compose.onNodeWithTag("prose").performTextInput(">")
        assertEquals(">Hello! world", prose.state.text.toString())
        assertEquals(listOf(b(1, 7)), prose.styles)
    }

    @Test
    fun toggleAtTheCursorStylesWhatIsTypedNext() {
        show()
        compose.onNodeWithTag("prose").performTextInput("One ")
        compose.runOnIdle { prose.toggle(Style.ITALIC) }
        assertTrue(prose.isActive(Style.ITALIC))
        compose.onNodeWithTag("prose").performTextInput("two")
        assertEquals(listOf(i(4, 7)), prose.styles)
        // Turning it off at the end of the run: the next text is plain.
        compose.runOnIdle { prose.toggle(Style.ITALIC) }
        assertFalse(prose.isActive(Style.ITALIC))
        compose.onNodeWithTag("prose").performTextInput(" three")
        assertEquals(listOf(i(4, 7)), prose.styles)
    }

    @OptIn(ExperimentalTestApi::class)
    @Test
    fun keyboardShortcutsAndUndo() {
        show()
        compose.onNodeWithTag("prose").performTextInput("Plain")
        compose.runOnIdle { prose.record(force = true) }
        compose.onNodeWithTag("prose").performTextInputSelection(TextRange(0, 5))
        compose.onNodeWithTag("prose").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.B) } }
        compose.runOnIdle { assertEquals(listOf(b(0, 5)), prose.styles) }
        compose.onNodeWithTag("prose").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.I) } }
        compose.runOnIdle { assertEquals(listOf(b(0, 5), i(0, 5)), prose.styles) }
        // Undo steps back through the style changes, then the typing.
        compose.onNodeWithTag("prose").performKeyInput { withKeyDown(Key.CtrlLeft) { pressKey(Key.Z) } }
        compose.runOnIdle { assertEquals(listOf(b(0, 5)), prose.styles) }
        compose.runOnIdle { prose.undo() }
        compose.runOnIdle {
            assertEquals(emptyList<StyleRange>(), prose.styles)
            assertEquals("Plain", prose.state.text.toString())
        }
        compose.runOnIdle { prose.undo() }
        compose.runOnIdle { assertEquals("", prose.state.text.toString()) }
        compose.runOnIdle { prose.redo(); prose.redo(); prose.redo() }
        compose.runOnIdle {
            assertEquals("Plain", prose.state.text.toString())
            assertEquals(listOf(b(0, 5), i(0, 5)), prose.styles)
        }
    }
}
