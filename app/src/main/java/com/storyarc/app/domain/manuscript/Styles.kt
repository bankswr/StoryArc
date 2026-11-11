package com.storyarc.app.domain.manuscript

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** Inline character styles a scene's prose can carry. */
@Serializable
enum class Style { BOLD, ITALIC }

/** [style] applied to the characters `start until end` of a text. */
@Serializable
data class StyleRange(val start: Int, val end: Int, val style: Style) {
    init {
        require(start in 0..end) { "bad range $start..$end" }
    }
}

/** Encoding of style ranges as stored in `scene_text.styles`. */
object StyleCodec {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(StyleRange.serializer())

    fun encode(ranges: List<StyleRange>): String = json.encodeToString(serializer, ranges)

    /** Decodes [text]; unreadable input yields no styles rather than failing. */
    fun decode(text: String?): List<StyleRange> =
        if (text.isNullOrBlank()) emptyList() else runCatching { json.decodeFromString(serializer, text) }.getOrDefault(emptyList())
}
