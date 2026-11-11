package com.storyarc.app.domain.manuscript

import java.util.Locale

/**
 * Page sizes for export: the two manuscript sizes, then common book trim sizes. Dimensions
 * are in millimetres.
 */
enum class PaperSize(val label: String, val widthMm: Double, val heightMm: Double) {
    LETTER("US Letter (8.5 × 11 in)", 215.9, 279.4),
    A4("A4 (210 × 297 mm)", 210.0, 297.0),
    TRADE_5X8("5 × 8 in", 127.0, 203.2),
    TRADE_525X8("5.25 × 8 in", 133.35, 203.2),
    DIGEST("Digest (5.5 × 8.5 in)", 139.7, 215.9),
    US_TRADE("US Trade (6 × 9 in)", 152.4, 228.6),
    ROYAL_US("Royal (6.14 × 9.21 in)", 155.956, 233.934),
    EXECUTIVE("7 × 10 in", 177.8, 254.0),
    A5("A5 (148 × 210 mm)", 148.0, 210.0),
    B_FORMAT("B-format (129 × 198 mm)", 129.0, 198.0),
    DEMY("Demy (138 × 216 mm)", 138.0, 216.0),
    ROYAL_UK("Royal UK (156 × 234 mm)", 156.0, 234.0);

    /** Margins: an inch on manuscript-sized paper, three quarters of an inch on trim sizes. */
    val marginMm: Double get() = if (widthMm >= 203.2) 25.4 else 19.05

    companion object {
        /** Letter where it is the usual paper (US, Canada and a few others), A4 elsewhere. */
        fun automatic(locale: Locale = Locale.getDefault()): PaperSize =
            if (locale.country.uppercase() in setOf("US", "CA", "MX", "PH", "CL", "CO", "VE", "PR")) LETTER else A4

        /** The stored setting: a [PaperSize] name, or null / unknown for automatic. */
        fun resolve(name: String?, locale: Locale = Locale.getDefault()): PaperSize =
            entries.find { it.name == name } ?: automatic(locale)
    }
}
