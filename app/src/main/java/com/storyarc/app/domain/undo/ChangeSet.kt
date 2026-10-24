package com.storyarc.app.domain.undo

/** One row-level change. `before == null` is an insert, `after == null` a delete. */
data class Change(val before: Any?, val after: Any?) {
    init {
        require(before != null || after != null) { "empty change" }
    }

    fun inverse() = Change(after, before)
}

/** An atomic, invertible edit. [label] describes it for undo/redo UI. */
data class ChangeSet(val label: String, val changes: List<Change>) {
    val isEmpty get() = changes.isEmpty()

    fun inverse() = ChangeSet(label, changes.asReversed().map(Change::inverse))

    /** Folds [later] into this set so the result goes from this set's start to [later]'s end. */
    fun mergedWith(later: ChangeSet, key: (Any) -> Any): ChangeSet {
        val merged = LinkedHashMap<Any, Change>()
        for (c in changes + later.changes) {
            val k = key(c.before ?: c.after!!)
            val prev = merged[k]
            merged[k] = if (prev == null) c else Change(prev.before, c.after)
        }
        return ChangeSet(label, merged.values.filter { it.before != it.after })
    }

    companion object {
        fun insert(label: String, vararg rows: Any) = ChangeSet(label, rows.map { Change(null, it) })
    }
}

/** Builds the changes that turn [old] into [new], matching rows by [key]. */
fun <T : Any> diffRows(old: Collection<T>, new: Collection<T>, key: (T) -> Any): List<Change> {
    val before = old.associateBy(key)
    val after = new.associateBy(key)
    return buildList {
        before.forEach { (k, row) -> if (k !in after) add(Change(row, null)) }
        after.forEach { (k, row) -> if (before[k] != row) add(Change(before[k], row)) }
    }
}
