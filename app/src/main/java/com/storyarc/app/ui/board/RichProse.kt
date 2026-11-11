@file:OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)

package com.storyarc.app.ui.board

import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.OutputTransformation
import androidx.compose.foundation.text.input.TextFieldBuffer
import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import com.storyarc.app.domain.manuscript.Style
import com.storyarc.app.domain.manuscript.StyleCodec
import com.storyarc.app.domain.manuscript.StyleRange
import com.storyarc.app.domain.manuscript.StyledText

/**
 * Prose with bold and italic on top of a plain [TextFieldState]. Every user edit reaches
 * [input], which moves the style ranges along with the text ([StyledText.edit]); [output] draws
 * them. Undo is handled here rather than by the text field, so text and styles undo together.
 */
@Stable
class RichProse(val state: TextFieldState) {
    var styles by mutableStateOf<List<StyleRange>>(emptyList())
        private set

    // Styles chosen with B/I at a collapsed cursor, applied to what is typed there next.
    private var pending by mutableStateOf<Set<Style>?>(null)
    private var pendingAt by mutableStateOf(-1)

    private val history = ArrayList<Snapshot>()
    private var index = -1
    private var lastRecord = 0L
    private var breakNext = true
    var canUndo by mutableStateOf(false)
        private set
    var canRedo by mutableStateOf(false)
        private set

    /** One undo point: text, styles and selection. */
    data class Snapshot(val text: String, val styles: List<StyleRange>, val selection: TextRange = TextRange.Zero)

    /** Replaces everything (on load) and starts a fresh history. */
    fun load(text: String, ranges: List<StyleRange>) {
        state.edit {
            replace(0, length, text)
            selection = TextRange(length)
        }
        styles = StyledText.normalize(ranges, text.length)
        pending = null
        history.clear()
        history += Snapshot(text, styles, TextRange(text.length))
        index = 0
        breakNext = true
        publish()
    }

    /** Styles new text typed at [pos] gets, given what it replaces (`pos until end`). */
    private fun typingStyles(pos: Int, end: Int, cursor: Int): Set<Style> =
        pending?.takeIf { pendingAt == cursor && pos == cursor }
            ?: if (end > pos) styles.filter { it.start <= pos && it.end > pos }.map { it.style }.toSet()
            else StyledText.stylesBefore(styles, pos)

    val input = object : InputTransformation {
        override fun TextFieldBuffer.transformInput() {
            val changes = changes
            if (changes.changeCount == 0) return
            val cursor = originalSelection.min
            var r = styles
            // Last change first, so earlier positions stay valid.
            for (i in changes.changeCount - 1 downTo 0) {
                val o = changes.getOriginalRange(i)
                val n = changes.getRange(i)
                r = StyledText.edit(r, o.min, o.max, n.length, typingStyles(o.min, o.max, cursor))
            }
            styles = r
            pending = null
        }
    }

    val output = object : OutputTransformation {
        override fun TextFieldBuffer.transformOutput() {
            for (r in styles) {
                if (r.end > length) continue
                addStyle(if (r.style == Style.BOLD) BOLD else ITALIC, r.start, r.end)
            }
        }
    }

    /** Whether B or I shows as on: for the whole selection, or for typing at the cursor. */
    fun isActive(style: Style): Boolean {
        val sel = state.selection
        return if (sel.collapsed) style in (pending?.takeIf { pendingAt == sel.min } ?: StyledText.stylesBefore(styles, sel.min))
        else StyledText.covers(styles, sel.min, sel.max, style)
    }

    /** B/I: styles the selection, or what is typed next at the cursor. */
    fun toggle(style: Style) {
        val sel = state.selection
        if (sel.collapsed) {
            val current = pending?.takeIf { pendingAt == sel.min } ?: StyledText.stylesBefore(styles, sel.min)
            pending = if (style in current) current - style else current + style
            pendingAt = sel.min
        } else {
            styles = StyledText.toggle(styles, sel.min, sel.max, style)
            breakNext = true
            record(force = true)
            breakNext = true
        }
    }

    /**
     * Records the current text and styles as an undo point. Typing within a second of the last
     * point extends it instead of adding another.
     */
    fun record(now: Long = System.currentTimeMillis(), force: Boolean = false) {
        val snap = Snapshot(state.text.toString(), styles, state.selection)
        val top = history.getOrNull(index)
        if (top != null && top.text == snap.text && top.styles == snap.styles) return
        while (history.size > index + 1) history.removeAt(history.lastIndex)
        if (!force && !breakNext && index > 0 && now - lastRecord < 1000) {
            history[index] = snap
        } else {
            history += snap
            index = history.lastIndex
            if (history.size > 500) { history.removeAt(0); index-- }
        }
        breakNext = false
        lastRecord = now
        publish()
    }

    fun undo() = restore(index - 1)

    fun redo() = restore(index + 1)

    private fun restore(to: Int) {
        val snap = history.getOrNull(to) ?: return
        index = to
        state.edit {
            replace(0, length, snap.text)
            selection = TextRange(snap.selection.min.coerceIn(0, length), snap.selection.max.coerceIn(0, length))
        }
        styles = snap.styles
        pending = null
        breakNext = true
        publish()
    }

    private fun publish() {
        canUndo = index > 0
        canRedo = index < history.lastIndex
    }

    companion object {
        /** Keeps text, styles and selection across configuration changes (undo history is not kept). */
        val Saver = androidx.compose.runtime.saveable.Saver<RichProse, List<Any>>(
            save = { listOf(it.state.text.toString(), StyleCodec.encode(it.styles), it.state.selection.start, it.state.selection.end) },
            restore = { v ->
                RichProse(TextFieldState()).apply {
                    load(v[0] as String, StyleCodec.decode(v[1] as String))
                    state.edit { selection = TextRange((v[2] as Int).coerceIn(0, length), (v[3] as Int).coerceIn(0, length)) }
                }
            },
        )

        val BOLD = SpanStyle(fontWeight = FontWeight.Bold)
        val ITALIC = SpanStyle(fontStyle = FontStyle.Italic)
    }
}
