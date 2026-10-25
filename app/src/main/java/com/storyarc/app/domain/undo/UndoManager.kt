package com.storyarc.app.domain.undo

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

data class UndoState(val undoLabel: String? = null, val redoLabel: String? = null) {
    val canUndo get() = undoLabel != null
    val canRedo get() = redoLabel != null
}

/**
 * In-memory undo/redo over [ChangeSet]s. [apply] writes a change set atomically.
 * Consecutive edits sharing a non-null coalesce key merge into one undo step.
 */
class UndoManager(
    private val apply: suspend (ChangeSet) -> Unit,
    private val rowKey: (Any) -> Any,
    private val capacity: Int = 200,
) {
    private val mutex = Mutex()
    private val undo = ArrayDeque<ChangeSet>()
    private val redo = ArrayDeque<ChangeSet>()
    private var lastKey: String? = null
    private val _state = MutableStateFlow(UndoState())
    val state: StateFlow<UndoState> = _state.asStateFlow()

    suspend fun perform(cs: ChangeSet, coalesceKey: String? = null) = mutex.withLock {
        if (cs.isEmpty) return@withLock
        apply(cs)
        redo.clear()
        if (coalesceKey != null && coalesceKey == lastKey && undo.isNotEmpty()) {
            undo.addLast(undo.removeLast().mergedWith(cs, rowKey))
        } else {
            undo.addLast(cs)
            if (undo.size > capacity) undo.removeFirst()
        }
        lastKey = coalesceKey
        publish()
    }

    suspend fun undo() = mutex.withLock {
        val cs = undo.lastOrNull() ?: return@withLock
        apply(cs.inverse())
        redo.addLast(undo.removeLast())
        lastKey = null
        publish()
    }

    suspend fun redo() = mutex.withLock {
        val cs = redo.lastOrNull() ?: return@withLock
        apply(cs)
        undo.addLast(redo.removeLast())
        lastKey = null
        publish()
    }

    /**
     * Reverts the latest step and forgets it (no redo entry) if [matches] accepts it; for
     * withdrawing something that should leave no trace, such as a new node left blank.
     */
    suspend fun retract(matches: (ChangeSet) -> Boolean): Boolean = mutex.withLock {
        val cs = undo.lastOrNull()?.takeIf(matches) ?: return@withLock false
        apply(cs.inverse())
        undo.removeLast()
        lastKey = null
        publish()
        true
    }

    /** Ends coalescing so the next edit starts a new undo step. */
    fun breakCoalescing() {
        lastKey = null
    }

    fun clear() {
        undo.clear(); redo.clear(); lastKey = null
        publish()
    }

    private fun publish() {
        _state.value = UndoState(undo.lastOrNull()?.label, redo.lastOrNull()?.label)
    }
}
