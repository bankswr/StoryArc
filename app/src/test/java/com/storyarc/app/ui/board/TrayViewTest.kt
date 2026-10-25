package com.storyarc.app.ui.board

import com.storyarc.app.data.db.NodeEntity
import com.storyarc.app.data.db.NodeGroupEntity
import com.storyarc.app.data.db.TagEntity
import com.storyarc.app.data.db.ViewEntity
import com.storyarc.board.Block
import com.storyarc.board.BoardState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TrayViewTest {
    private fun node(id: String, text: String) = NodeEntity(id, "p", text)
    private val ui = BoardUi(
        view = ViewEntity("v", "p", "Main", 0),
        board = BoardState(),
        timelines = emptyList(),
        nodes = listOf(node("a", "storm"), node("b", "Arrival"), node("c", ""), node("d", "dawn"), node("e", "ember")).associateBy { it.id },
        markers = emptyMap(),
        groups = mapOf("g" to NodeGroupEntity("g", "v", null, null, "Voyage")),
        tags = listOf(TagEntity("t1", "p", null, "Place"), TagEntity("t2", "p", null, "Danger")).associateBy { it.id },
        nodeTags = mapOf("a" to listOf("t2"), "b" to listOf("t1"), "e" to listOf("t1")),
        groupTags = mapOf("g" to listOf("t2")),
    )
    private val single = { id: String -> Block(mapOf(id to 0)) }
    private val group = Block(mapOf("d" to 0, "e" to 1), mapOf("g" to null), mapOf("d" to "g", "e" to "g"))
    private val tray = listOf(single("a"), single("b"), single("c"), group)

    private fun names(view: TrayView) = ui.arrangeTray(tray, view).map { ui.trayLabel(it).ifEmpty { "-" } }

    @Test
    fun manualKeepsStoredOrder() = assertEquals(listOf("storm", "Arrival", "-", "Voyage"), names(TrayView()))

    @Test
    fun alphabeticalPutsBlankLast() =
        assertEquals(listOf("Arrival", "storm", "Voyage", "-"), names(TrayView(TraySort.Alphabetical)))

    @Test
    fun byTagPullsSamePrimaryTagTogether() =
        // Danger (storm, Voyage), Place (Arrival), untagged last.
        assertEquals(listOf("storm", "Voyage", "Arrival", "-"), names(TrayView(TraySort.ByTag)))

    @Test
    fun filtersByTagAndText() {
        // A group matches tags of its nodes too.
        assertEquals(listOf("Arrival", "Voyage"), names(TrayView(tagId = "t1")))
        assertEquals(listOf("-"), names(TrayView(tagId = TrayView.UNTAGGED)))
        assertEquals(listOf("Voyage"), names(TrayView(query = "EMB")))
        assertEquals(listOf("storm"), names(TrayView(query = "sto", tagId = "t2")))
        assertTrue(TrayView(TraySort.ByTag).isFiltered.not())
        assertTrue(!TrayView(query = "x").isManual)
    }
}
