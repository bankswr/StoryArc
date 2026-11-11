package com.storyarc.app.domain.manuscript

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.w3c.dom.Document
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.time.LocalDate
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class OdtWriterTest {
    private val text = "urn:oasis:names:tc:opendocument:xmlns:text:1.0"

    private val sample = Assembler.assemble(
        "The Long Road",
        listOf(
            SceneSource("Departure\nsecond line is ignored", "The bells rang.\n\nShe left  at dawn & didn't <look> back.\n#\nLater.", 
                StyleCodec.encode(listOf(StyleRange(4, 9, Style.ITALIC), StyleRange(4, 15, Style.BOLD)))),
            SceneSource("", null, null),
            SceneSource("Storm", "Rain.", "[]"),
        ),
    )

    private fun entries(bytes: ByteArray): List<Pair<ZipEntry, ByteArray>> = buildList {
        ZipInputStream(ByteArrayInputStream(bytes)).use { z ->
            while (true) {
                val e = z.nextEntry ?: break
                add(e to z.readBytes())
            }
        }
    }

    private fun parse(bytes: ByteArray): Document =
        DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(ByteArrayInputStream(bytes))

    private fun write(m: Manuscript, paper: PaperSize = PaperSize.LETTER) = ByteArrayOutputStream().also { OdtWriter.write(m, paper, it) }.toByteArray()

    @Test
    fun packageLayout() {
        val bytes = write(sample)
        // ODF requires "mimetype" first, uncompressed, at a fixed offset.
        assertEquals("mimetype", String(bytes, 30, 8))
        assertEquals(OdtWriter.MIME_TYPE, String(bytes, 38, OdtWriter.MIME_TYPE.length))
        val all = entries(bytes)
        assertEquals(listOf("mimetype", "META-INF/manifest.xml", "styles.xml", "content.xml", "meta.xml"), all.map { it.first.name })
        assertEquals(ZipEntry.STORED, all.first().first.method)
        // Every XML part is well formed.
        all.drop(1).forEach { (_, b) -> parse(b) }
    }

    @Test
    fun chaptersInOrderWithStyledRuns() {
        val content = parse(entries(write(sample)).single { it.first.name == "content.xml" }.second)
        val headings = content.getElementsByTagNameNS(text, "h")
        assertEquals(listOf("Departure", "Untitled", "Storm"), (0 until headings.length).map { headings.item(it).textContent })
        val paras = content.getElementsByTagNameNS(text, "p")
        val texts = (0 until paras.length).map { paras.item(it).textContent }
        // Blank lines dropped; "#" is a scene break. (The second space is a <text:s/>, which has no text content.)
        assertEquals(listOf("The bells rang.", "She left at dawn & didn't <look> back.", "#", "Later.", "Rain."), texts)
        assertEquals(1, content.getElementsByTagNameNS(text, "s").length)
        val spans = content.getElementsByTagNameNS(text, "span")
        val styled = (0 until spans.length).map { spans.item(it).attributes.getNamedItemNS(text, "style-name").nodeValue to spans.item(it).textContent }
        assertEquals(listOf("TBoldItalic" to "bells", "TBold" to " rang."), styled)
    }

    @Test
    fun titlePageAndHeaderOnlyWithAuthor() {
        val plain = String(entries(write(sample)).single { it.first.name == "styles.xml" }.second)
        assertFalse(plain.contains("THE LONG ROAD"))
        val withAuthor = sample.copy(author = "Ada Quill")
        val all = entries(write(withAuthor, PaperSize.US_TRADE))
        val styles = String(all.single { it.first.name == "styles.xml" }.second)
        assertTrue(styles.contains("Quill / THE LONG ROAD / <text:page-number"))
        assertTrue(styles.contains("fo:page-width=\"152.40mm\""))
        val content = String(all.single { it.first.name == "content.xml" }.second)
        assertTrue(content.contains(">by Ada Quill<"))
        assertTrue(content.contains("PTitleStart"))
        // Page numbering restarts at the first chapter, after the title page.
        assertTrue(content.contains("style:page-number=\"1\""))
    }

    @Test
    fun fileNames() {
        val d = LocalDate.of(2026, 11, 11)
        assertEquals("The_Long_Road_2026_11_11.odt", Assembler.fileName("The Long Road", d))
        assertEquals("A_B_C_2026_11_11.odt", Assembler.fileName("  A  B\tC ", d))
        assertEquals("WhatIf_2026_11_11.odt", Assembler.fileName("What/If?", d))
        assertEquals("Manuscript_2026_11_11.odt", Assembler.fileName(" ?? ", d))
    }

    @Test
    fun approximateWordCounts() {
        assertEquals("46 words", OdtWriter.approximateWords(46))
        assertEquals("About 46,100 words", OdtWriter.approximateWords(46_051))
    }

    /** Writes sample files for a manual LibreOffice check (`soffice --headless --convert-to pdf`). */
    @Test
    fun writesSamplesForManualCheck() {
        val dir = File("build/odt-samples").apply { mkdirs() }
        val long = sample.copy(
            author = "Ada Quill",
            chapters = sample.chapters + Chapter("A Long Chapter", (1..40).joinToString("\n\n") { "Paragraph $it. " + "The road went on and on, as roads do. ".repeat(6) }),
        )
        File(dir, "sample-letter.odt").writeBytes(write(long, PaperSize.LETTER))
        File(dir, "sample-trade.odt").writeBytes(write(sample, PaperSize.US_TRADE))
    }
}
