package com.storyarc.app.domain.manuscript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class StyledTextTest {
    private fun b(s: Int, e: Int) = StyleRange(s, e, Style.BOLD)
    private fun i(s: Int, e: Int) = StyleRange(s, e, Style.ITALIC)

    @Test
    fun normalizeMergesAndDrops() {
        assertEquals(listOf(b(0, 5), i(2, 3)), StyledText.normalize(listOf(b(3, 5), b(0, 3), i(2, 3), i(4, 4))))
        assertEquals(listOf(b(0, 4)), StyledText.normalize(listOf(b(0, 9)), length = 4))
    }

    @Test
    fun typingInsideAndAtTheEdges() {
        val r = listOf(b(2, 5))
        // Inside a bold run with bold typing: it grows.
        assertEquals(listOf(b(2, 7)), StyledText.edit(r, 3, 3, 2, setOf(Style.BOLD)))
        // Inside without bold: the run splits around the new text.
        assertEquals(listOf(b(2, 3), b(5, 7)), StyledText.edit(r, 3, 3, 2, emptySet()))
        // At the end with bold continues it; without, it doesn't.
        assertEquals(listOf(b(2, 6)), StyledText.edit(r, 5, 5, 1, setOf(Style.BOLD)))
        assertEquals(listOf(b(2, 5)), StyledText.edit(r, 5, 5, 1, emptySet()))
        // Before the run shifts it.
        assertEquals(listOf(b(3, 6)), StyledText.edit(r, 0, 0, 1, emptySet()))
    }

    @Test
    fun deletingAndReplacing() {
        val r = listOf(b(2, 6), i(0, 10))
        assertEquals(listOf(b(2, 3), i(0, 7)), StyledText.edit(r, 3, 6, 0, emptySet()))
        assertEquals(listOf(i(0, 8)), StyledText.edit(r, 1, 7, 4, setOf(Style.ITALIC)))
    }

    @Test
    fun toggling() {
        assertEquals(listOf(b(2, 5)), StyledText.toggle(emptyList(), 2, 5, Style.BOLD))
        assertEquals(listOf(b(0, 2), b(4, 8)), StyledText.toggle(listOf(b(0, 8)), 2, 4, Style.BOLD))
        // Partly bold becomes all bold.
        assertEquals(listOf(b(0, 8)), StyledText.toggle(listOf(b(0, 3)), 2, 8, Style.BOLD))
        assertTrue(StyledText.covers(listOf(b(0, 8)), 2, 4, Style.BOLD))
        assertFalse(StyledText.covers(listOf(b(0, 3)), 2, 4, Style.BOLD))
        assertEquals(setOf(Style.BOLD), StyledText.stylesBefore(listOf(b(0, 3)), 3))
        assertEquals(emptySet<Style>(), StyledText.stylesBefore(listOf(b(0, 3)), 4))
    }

    /** Per-character model: the oracle for random edits and toggles. */
    private fun toRanges(chars: List<Set<Style>>) = StyledText.normalize(
        chars.flatMapIndexed { idx, set -> set.map { StyleRange(idx, idx + 1, it) } },
    )

    @Test
    fun randomEditsMatchAPerCharacterModel() {
        repeat(500) { seed ->
            val rnd = Random(seed)
            var chars = MutableList(rnd.nextInt(0, 20)) { emptySet<Style>() }
            var ranges = emptyList<StyleRange>()
            repeat(60) { step ->
                val len = chars.size
                if (rnd.nextInt(3) == 0 && len > 0) {
                    val s = rnd.nextInt(0, len)
                    val e = rnd.nextInt(s, len + 1)
                    val style = Style.entries.random(rnd)
                    val all = s < e && (s until e).all { style in chars[it] }
                    for (k in s until e) chars[k] = if (all) chars[k] - style else chars[k] + style
                    ranges = StyledText.toggle(ranges, s, e, style)
                } else {
                    val s = rnd.nextInt(0, len + 1)
                    val e = rnd.nextInt(s, len + 1)
                    val n = rnd.nextInt(0, 4)
                    val typing = Style.entries.filter { rnd.nextBoolean() }.toSet()
                    chars = (chars.subList(0, s) + List(n) { typing } + chars.subList(e, len)).toMutableList()
                    ranges = StyledText.edit(ranges, s, e, n, typing)
                }
                assertEquals("seed $seed step $step", toRanges(chars), ranges)
                assertTrue(ranges.all { it.end <= chars.size })
            }
        }
    }
}
