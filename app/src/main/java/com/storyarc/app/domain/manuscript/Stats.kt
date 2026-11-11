package com.storyarc.app.domain.manuscript

/** Word and page counts for manuscript text. */
object Stats {
    /**
     * Words in [text]: whitespace-separated runs containing at least one letter or digit, so
     * stray punctuation such as a lone dash or a `#` scene break doesn't count. Hyphenated and
     * apostrophe words count once.
     */
    fun words(text: CharSequence): Int {
        var count = 0
        var counted = false
        for (c in text) {
            if (c.isWhitespace()) {
                counted = false
            } else {
                if (!counted && c.isLetterOrDigit()) {
                    count++
                    counted = true
                }
            }
        }
        return count
    }

    /** Standard manuscript pages for [words] at [wordsPerPage]; any text at all is at least one page. */
    fun pages(words: Int, wordsPerPage: Int): Int =
        if (words <= 0) 0 else (words + wordsPerPage.coerceAtLeast(1) - 1) / wordsPerPage.coerceAtLeast(1)

    /** "1,234 words · 5 pages" style summary. */
    fun summary(words: Int, wordsPerPage: Int): String {
        val pages = pages(words, wordsPerPage)
        return "%,d word%s · %,d page%s".format(words, if (words == 1) "" else "s", pages, if (pages == 1) "" else "s")
    }
}
