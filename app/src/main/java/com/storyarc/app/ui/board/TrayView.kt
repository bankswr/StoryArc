package com.storyarc.app.ui.board

import com.storyarc.board.Block

enum class TraySort { Manual, Alphabetical, ByTag }

/**
 * How the tray lists its items. Display only: the stored order is always the manual one.
 * [tagId] keeps items carrying that tag ([UNTAGGED] for items with none); [query] matches
 * node text and group labels, ignoring case.
 */
data class TrayView(val sort: TraySort = TraySort.Manual, val tagId: String? = null, val query: String = "") {
    /** Whether the tray shows its stored order, so dragging within it can reorder. */
    val isManual get() = sort == TraySort.Manual && !isFiltered

    val isFiltered get() = tagId != null || query.isNotBlank()

    companion object {
        const val UNTAGGED = ""
    }
}

/** The tray item's display name: its group's label, else its first node's text. */
fun BoardUi.trayLabel(b: Block): String {
    val first = b.nodes.entries.minBy { it.value }.key
    return b.root?.let { groups[it]?.label }?.takeIf { it.isNotBlank() } ?: nodes[first]?.text.orEmpty()
}

/** Tags of the item: its group's, then its nodes'. The first is its primary tag. */
fun BoardUi.trayTags(b: Block): List<String> {
    val nodeTagsInOrder = b.nodes.entries.sortedBy { it.value }.flatMap { nodeTags[it.key].orEmpty() }
    return (b.root?.let { groupTags[it] }.orEmpty() + nodeTagsInOrder).distinct()
}

/** [blocks] filtered and ordered as [view] asks. Ties keep the stored order. */
fun BoardUi.arrangeTray(blocks: List<Block>, view: TrayView): List<Block> {
    val q = view.query.trim()
    val kept = blocks.filter { b ->
        val tags = trayTags(b)
        val tagOk = when (view.tagId) {
            null -> true
            TrayView.UNTAGGED -> tags.isEmpty()
            else -> view.tagId in tags
        }
        val text = b.nodes.keys.map { nodes[it]?.text.orEmpty() } + b.groups.keys.map { groups[it]?.label.orEmpty() }
        tagOk && (q.isEmpty() || text.any { it.contains(q, ignoreCase = true) })
    }
    // Blank labels sort after named ones.
    fun nameKey(b: Block) = trayLabel(b).trim().lowercase().ifEmpty { "￿" }
    return when (view.sort) {
        TraySort.Manual -> kept
        TraySort.Alphabetical -> kept.sortedBy(::nameKey)
        // Items sharing a primary tag sit together (tags by name, untagged last), A–Z within.
        TraySort.ByTag -> kept.sortedWith(
            compareBy<Block>({ b -> trayTags(b).firstOrNull()?.let { tags[it]?.name?.lowercase() } ?: "￿" }, ::nameKey),
        )
    }
}
