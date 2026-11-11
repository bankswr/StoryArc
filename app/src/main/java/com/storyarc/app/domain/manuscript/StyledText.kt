package com.storyarc.app.domain.manuscript

/**
 * Operations on bold/italic [StyleRange]s over a text. Results are always normalized: per
 * style, ranges are non-empty, sorted, and neither overlap nor touch (touching ranges merge).
 */
object StyledText {

    /** [ranges] normalized (see [StyledText]) and clipped to `0..length`. */
    fun normalize(ranges: List<StyleRange>, length: Int = Int.MAX_VALUE): List<StyleRange> =
        Style.entries.flatMap { style ->
            val sorted = ranges.filter { it.style == style }
                .map { it.start.coerceIn(0, length) to it.end.coerceIn(0, length) }
                .filter { it.first < it.second }
                .sortedBy { it.first }
            val merged = ArrayList<Pair<Int, Int>>()
            for (r in sorted) {
                val last = merged.lastOrNull()
                if (last != null && r.first <= last.second) merged[merged.size - 1] = last.first to maxOf(last.second, r.second)
                else merged += r
            }
            merged.map { StyleRange(it.first, it.second, style) }
        }

    /** Whether every character in `start until end` carries [style]; false for an empty span. */
    fun covers(ranges: List<StyleRange>, start: Int, end: Int, style: Style): Boolean =
        start < end && ranges.any { it.style == style && it.start <= start && it.end >= end }

    /** Styles of the character just before [pos]: what typing at [pos] continues. */
    fun stylesBefore(ranges: List<StyleRange>, pos: Int): Set<Style> =
        if (pos <= 0) emptySet() else ranges.filter { it.start < pos && it.end >= pos }.map { it.style }.toSet()

    /**
     * Adjusts [ranges] for replacing the characters `start until oldEnd` with [newLength] new
     * ones. Surviving characters keep their styles; the new characters get exactly [typing].
     */
    fun edit(ranges: List<StyleRange>, start: Int, oldEnd: Int, newLength: Int, typing: Set<Style>): List<StyleRange> {
        require(start in 0..oldEnd && newLength >= 0)
        val removed = oldEnd - start
        val out = ArrayList<StyleRange>()
        val shift = newLength - removed
        for (r in ranges) {
            // The part before the edit stays; the part after it shifts. New text is styled separately.
            if (r.start < start) out += StyleRange(r.start, minOf(r.end, start), r.style)
            if (r.end > oldEnd) out += StyleRange(maxOf(r.start, oldEnd) + shift, r.end + shift, r.style)
        }
        if (newLength > 0) typing.forEach { out += StyleRange(start, start + newLength, it) }
        return normalize(out)
    }

    /**
     * Turns [style] on for `start until end`, or off if that span already has it throughout.
     * An empty span changes nothing.
     */
    fun toggle(ranges: List<StyleRange>, start: Int, end: Int, style: Style): List<StyleRange> {
        if (start >= end) return ranges
        if (!covers(ranges, start, end, style)) return normalize(ranges + StyleRange(start, end, style))
        return normalize(ranges.flatMap { r ->
            if (r.style != style || r.end <= start || r.start >= end) listOf(r)
            else listOfNotNull(
                StyleRange(r.start, start, style).takeIf { r.start < start },
                StyleRange(end, r.end, style).takeIf { end < r.end },
            )
        })
    }
}
