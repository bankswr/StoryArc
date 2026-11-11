package com.storyarc.app.domain.manuscript

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/** One chapter: a scene's card name as its [title], and its prose. */
data class Chapter(val title: String, val body: String, val styles: List<StyleRange> = emptyList())

/** A whole manuscript, ready to write out. [author] adds a title page and running header. */
data class Manuscript(val title: String, val chapters: List<Chapter>, val author: String? = null) {
    val words: Int get() = chapters.sumOf { Stats.words(it.body) }
}

/** A scene as assembly sees it: its card text and its manuscript text (if any). */
data class SceneSource(val cardText: String, val body: String?, val styles: String?)

object Assembler {
    /**
     * Builds the manuscript from [scenes], already in reading order. Each scene is a chapter
     * titled with the first line of its card text; a scene without text keeps its heading.
     */
    fun assemble(title: String, scenes: List<SceneSource>, author: String? = null): Manuscript = Manuscript(
        title = title.trim().ifEmpty { "Untitled" },
        chapters = scenes.map { s ->
            Chapter(chapterTitle(s.cardText), s.body.orEmpty(), StyleCodec.decode(s.styles))
        },
        author = author?.trim()?.takeIf { it.isNotEmpty() },
    )

    fun chapterTitle(cardText: String): String =
        cardText.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: "Untitled"

    /**
     * `<project name>_yyyy_mm_dd.odt`, with whitespace runs turned into underscores and
     * characters that file systems reject removed.
     */
    fun fileName(projectName: String, date: LocalDate): String {
        val safe = projectName.trim()
            .replace(Regex("""\s+"""), "_")
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), "")
            .trim('.', '_')
            .ifEmpty { "Manuscript" }
        return safe + "_" + date.format(DateTimeFormatter.ofPattern("yyyy_MM_dd")) + ".odt"
    }
}
