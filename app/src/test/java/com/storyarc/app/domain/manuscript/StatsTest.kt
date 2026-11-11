package com.storyarc.app.domain.manuscript

import org.junit.Assert.assertEquals
import org.junit.Test

class StatsTest {
    @Test
    fun countsWords() {
        assertEquals(0, Stats.words(""))
        assertEquals(0, Stats.words("   \n\t "))
        assertEquals(4, Stats.words("It was a night."))
        assertEquals(3, Stats.words("  Mara's well-worn\nboots "))
        // Lone punctuation is not a word.
        assertEquals(2, Stats.words("Wait — what"))
        assertEquals(2, Stats.words("one\n\n#\n\ntwo"))
        assertEquals(3, Stats.words("“Hello,” she said."))
        assertEquals(2, Stats.words("1999 arrived"))
    }

    @Test
    fun countsPages() {
        assertEquals(0, Stats.pages(0, 250))
        assertEquals(1, Stats.pages(1, 250))
        assertEquals(1, Stats.pages(250, 250))
        assertEquals(2, Stats.pages(251, 250))
        assertEquals("1 word · 1 page", Stats.summary(1, 250))
        assertEquals("1,000 words · 4 pages", Stats.summary(1000, 250))
    }
}
