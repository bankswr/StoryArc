package com.storyarc.app.ui.board

import com.storyarc.app.data.db.NodeEntity
import com.storyarc.app.data.db.TagEntity
import com.storyarc.app.data.db.ViewEntity
import com.storyarc.board.BoardState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BoardUiTest {
    private val ui = BoardUi(
        view = ViewEntity("v", "p", "Main", 0),
        board = BoardState(),
        timelines = emptyList(),
        nodes = mapOf(
            "own" to NodeEntity("own", "p", "", color = 1),
            "tagged" to NodeEntity("tagged", "p", ""),
            "plain" to NodeEntity("plain", "p", ""),
        ),
        markers = emptyMap(),
        groups = emptyMap(),
        tags = mapOf("t1" to TagEntity("t1", "p", null, "Scene", color = 2), "t2" to TagEntity("t2", "p", null, "POV", color = 3)),
        nodeTags = mapOf("own" to listOf("t1"), "tagged" to listOf("t2", "t1")),
        hiddenTags = setOf("t1"),
    )

    @Test
    fun overrideThenPrimaryTagThenDefault() {
        assertEquals(1, ui.nodeColor("own"))
        assertEquals(3, ui.nodeColor("tagged"))
        assertNull(ui.nodeColor("plain"))
    }

    @Test
    fun anyHiddenTagHides() {
        assertTrue(ui.isHidden(ui.nodeTags["tagged"]))
        assertFalse(ui.isHidden(ui.nodeTags["plain"]))
    }
}
