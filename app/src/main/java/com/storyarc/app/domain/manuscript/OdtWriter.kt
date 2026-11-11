package com.storyarc.app.domain.manuscript

import java.io.OutputStream
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Writes a [Manuscript] as an OpenDocument Text (.odt) file in standard manuscript format:
 * 12 pt Times New Roman, double-spaced, half-inch first-line indents, each chapter on a new
 * page with its title about a third of the way down, and page numbers in the header. With an
 * author, the file starts with a title page and the header reads "Surname / TITLE / page".
 *
 * The package is built by hand: `mimetype` first and uncompressed (as ODF requires), then the
 * manifest, `styles.xml`, `content.xml` and `meta.xml`.
 */
object OdtWriter {
    const val MIME_TYPE = "application/vnd.oasis.opendocument.text"

    /** Lines that stand for a scene break inside a chapter; written as a centred `#`. */
    private val sceneBreaks = setOf("#", "***", "* * *", "⁂", "~")

    fun write(manuscript: Manuscript, paper: PaperSize, out: OutputStream) {
        ZipOutputStream(out).use { zip ->
            val mime = MIME_TYPE.toByteArray()
            zip.putNextEntry(ZipEntry("mimetype").apply {
                method = ZipEntry.STORED
                size = mime.size.toLong()
                compressedSize = mime.size.toLong()
                crc = CRC32().apply { update(mime) }.value
            })
            zip.write(mime)
            zip.closeEntry()
            fun put(name: String, text: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(text.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            put("META-INF/manifest.xml", manifest())
            put("styles.xml", styles(manuscript, paper))
            put("content.xml", content(manuscript))
            put("meta.xml", meta(manuscript))
        }
    }

    private const val NS = """xmlns:office="urn:oasis:names:tc:opendocument:xmlns:office:1.0" """ +
        """xmlns:style="urn:oasis:names:tc:opendocument:xmlns:style:1.0" """ +
        """xmlns:text="urn:oasis:names:tc:opendocument:xmlns:text:1.0" """ +
        """xmlns:fo="urn:oasis:names:tc:opendocument:xmlns:xsl-fo-compatible:1.0" """ +
        """xmlns:svg="urn:oasis:names:tc:opendocument:xmlns:svg-compatible:1.0" """ +
        """xmlns:meta="urn:oasis:names:tc:opendocument:xmlns:meta:1.0" """ +
        """xmlns:dc="http://purl.org/dc/elements/1.1/" office:version="1.3""""

    private fun manifest() = """<?xml version="1.0" encoding="UTF-8"?>
<manifest:manifest xmlns:manifest="urn:oasis:names:tc:opendocument:xmlns:manifest:1.0" manifest:version="1.3">
 <manifest:file-entry manifest:full-path="/" manifest:version="1.3" manifest:media-type="$MIME_TYPE"/>
 <manifest:file-entry manifest:full-path="content.xml" manifest:media-type="text/xml"/>
 <manifest:file-entry manifest:full-path="styles.xml" manifest:media-type="text/xml"/>
 <manifest:file-entry manifest:full-path="meta.xml" manifest:media-type="text/xml"/>
</manifest:manifest>
"""

    private fun mm(v: Double) = String.format(Locale.US, "%.2fmm", v)

    /** "Surname / TITLE / " for the running header, or nothing without an author. */
    private fun headerPrefix(m: Manuscript): String {
        val author = m.author ?: return ""
        val surname = author.split(Regex("\\s+")).last()
        val short = m.title.uppercase().let { if (it.length > 40) it.take(40).trimEnd() + "…" else it }
        return xml("$surname / $short / ")
    }

    private fun styles(m: Manuscript, paper: PaperSize): String {
        val margin = paper.marginMm
        // The header sits in the top margin: page margin to the header, then the header's own height and gap.
        val headerTop = margin / 2
        val headerGap = margin / 2 - 4.5
        fun layout(name: String, header: Boolean) = """
  <style:page-layout style:name="$name">
   <style:page-layout-properties fo:page-width="${mm(paper.widthMm)}" fo:page-height="${mm(paper.heightMm)}" style:print-orientation="portrait" fo:margin-top="${mm(if (header) headerTop else margin)}" fo:margin-bottom="${mm(margin)}" fo:margin-left="${mm(margin)}" fo:margin-right="${mm(margin)}"/>
   ${if (header) """<style:header-style><style:header-footer-properties fo:min-height="4.5mm" fo:margin-bottom="${mm(headerGap)}"/></style:header-style>""" else "<style:header-style/>"}
  </style:page-layout>"""
        return """<?xml version="1.0" encoding="UTF-8"?>
<office:document-styles $NS>
 <office:font-face-decls>
  <style:font-face style:name="Times New Roman" svg:font-family="'Times New Roman'" style:font-family-generic="roman" style:font-pitch="variable"/>
 </office:font-face-decls>
 <office:styles>
  <style:default-style style:family="paragraph">
   <style:paragraph-properties fo:orphans="2" fo:widows="2"/>
   <style:text-properties style:font-name="Times New Roman" fo:font-size="12pt" fo:language="en" fo:country="US"/>
  </style:default-style>
  <style:style style:name="Standard" style:family="paragraph" style:class="text">
   <style:paragraph-properties fo:line-height="200%" fo:margin-top="0mm" fo:margin-bottom="0mm"/>
   <style:text-properties style:font-name="Times New Roman" fo:font-size="12pt"/>
  </style:style>
  <style:style style:name="Manuscript_20_Body" style:display-name="Manuscript Body" style:family="paragraph" style:parent-style-name="Standard" style:class="text">
   <style:paragraph-properties fo:text-indent="12.7mm"/>
  </style:style>
  <style:style style:name="Chapter_20_Title" style:display-name="Chapter Title" style:family="paragraph" style:parent-style-name="Standard" style:default-outline-level="1" style:class="chapter">
   <style:paragraph-properties fo:text-align="center" fo:margin-top="63.5mm" fo:margin-bottom="12.7mm" fo:break-before="page" fo:keep-with-next="always"/>
  </style:style>
  <style:style style:name="Scene_20_Break" style:display-name="Scene Break" style:family="paragraph" style:parent-style-name="Standard" style:class="text">
   <style:paragraph-properties fo:text-align="center" fo:keep-with-next="always"/>
  </style:style>
  <style:style style:name="Title_20_Page_20_Info" style:display-name="Title Page Info" style:family="paragraph" style:parent-style-name="Standard" style:class="extra">
   <style:paragraph-properties fo:line-height="100%"/>
  </style:style>
  <style:style style:name="Book_20_Title" style:display-name="Book Title" style:family="paragraph" style:parent-style-name="Standard" style:class="chapter">
   <style:paragraph-properties fo:text-align="center" fo:margin-top="76.2mm"/>
  </style:style>
  <style:style style:name="Byline" style:family="paragraph" style:parent-style-name="Standard" style:class="chapter">
   <style:paragraph-properties fo:text-align="center"/>
  </style:style>
  <style:style style:name="Header" style:family="paragraph" style:parent-style-name="Standard" style:class="extra">
   <style:paragraph-properties fo:text-align="end" fo:line-height="100%"/>
  </style:style>
 </office:styles>
 <office:automatic-styles>${layout("pmMain", header = true)}${layout("pmTitle", header = false)}
 </office:automatic-styles>
 <office:master-styles>
  <style:master-page style:name="Standard" style:page-layout-name="pmMain">
   <style:header><text:p text:style-name="Header">${headerPrefix(m)}<text:page-number text:select-page="current">1</text:page-number></text:p></style:header>
  </style:master-page>
  <style:master-page style:name="Title_20_Page" style:display-name="Title Page" style:page-layout-name="pmTitle" style:next-style-name="Standard"/>
 </office:master-styles>
</office:document-styles>
"""
    }

    private fun content(m: Manuscript): String = buildString {
        append("""<?xml version="1.0" encoding="UTF-8"?>
<office:document-content $NS>
 <office:automatic-styles>
  <style:style style:name="PTitleStart" style:family="paragraph" style:parent-style-name="Title_20_Page_20_Info" style:master-page-name="Title_20_Page"/>
  <style:style style:name="PFirstChapter" style:family="paragraph" style:parent-style-name="Chapter_20_Title" style:master-page-name="Standard">
   <style:paragraph-properties fo:break-before="auto"${if (m.author != null) " style:page-number=\"1\"" else ""}/>
  </style:style>
  <style:style style:name="TBold" style:family="text"><style:text-properties fo:font-weight="bold" style:font-weight-asian="bold" style:font-weight-complex="bold"/></style:style>
  <style:style style:name="TItalic" style:family="text"><style:text-properties fo:font-style="italic" style:font-style-asian="italic" style:font-style-complex="italic"/></style:style>
  <style:style style:name="TBoldItalic" style:family="text"><style:text-properties fo:font-weight="bold" style:font-weight-asian="bold" style:font-weight-complex="bold" fo:font-style="italic" style:font-style-asian="italic" style:font-style-complex="italic"/></style:style>
 </office:automatic-styles>
 <office:body>
  <office:text>
""")
        if (m.author != null) {
            append("""   <text:p text:style-name="PTitleStart">${xml(m.author)}</text:p>
   <text:p text:style-name="Title_20_Page_20_Info">${xml(approximateWords(m.words))}</text:p>
   <text:p text:style-name="Book_20_Title">${xml(m.title)}</text:p>
   <text:p text:style-name="Byline">by ${xml(m.author)}</text:p>
""")
        }
        m.chapters.forEachIndexed { i, ch ->
            // The first chapter switches to the main page style (a new page after a title page).
            val style = if (i == 0) "PFirstChapter" else "Chapter_20_Title"
            append("""   <text:h text:style-name="$style" text:outline-level="1">${xml(ch.title)}</text:h>""").append('\n')
            paragraphs(ch).forEach { append("   ").append(it).append('\n') }
        }
        if (m.chapters.isEmpty() && m.author == null) append("""   <text:p text:style-name="Standard"/>""").append('\n')
        append("""  </office:text>
 </office:body>
</office:document-content>
""")
    }

    /** "About 46,100 words": rounded to the nearest hundred, as manuscript title pages do. */
    fun approximateWords(words: Int): String =
        if (words < 100) "%,d words".format(Locale.US, words) else "About %,d words".format(Locale.US, (words + 50) / 100 * 100)

    /**
     * The chapter's body as `<text:p>` elements: one per non-blank line (blank lines between
     * paragraphs are dropped, since indents mark paragraphs), with styled runs as spans.
     */
    internal fun paragraphs(ch: Chapter): List<String> {
        val body = ch.body
        val bold = BooleanArray(body.length)
        val italic = BooleanArray(body.length)
        for (r in ch.styles) {
            val flags = if (r.style == Style.BOLD) bold else italic
            for (i in r.start.coerceIn(0, body.length) until r.end.coerceIn(0, body.length)) flags[i] = true
        }
        val out = ArrayList<String>()
        var lineStart = 0
        while (lineStart <= body.length) {
            val nl = body.indexOf('\n', lineStart).let { if (it < 0) body.length else it }
            var a = lineStart
            var b = nl
            while (a < b && body[a].isWhitespace()) a++
            while (b > a && body[b - 1].isWhitespace()) b--
            if (a < b) {
                val line = body.substring(a, b)
                if (line in sceneBreaks) {
                    out += """<text:p text:style-name="Scene_20_Break">#</text:p>"""
                } else {
                    out += """<text:p text:style-name="Manuscript_20_Body">""" + runs(body, a, b, bold, italic) + "</text:p>"
                }
            }
            lineStart = nl + 1
        }
        return out
    }

    private fun runs(body: String, a: Int, b: Int, bold: BooleanArray, italic: BooleanArray): String = buildString {
        var i = a
        while (i < b) {
            var j = i + 1
            while (j < b && bold[j] == bold[i] && italic[j] == italic[i]) j++
            val text = xml(body.substring(i, j))
            val style = when {
                bold[i] && italic[i] -> "TBoldItalic"
                bold[i] -> "TBold"
                italic[i] -> "TItalic"
                else -> null
            }
            if (style == null) append(text) else append("""<text:span text:style-name="$style">""").append(text).append("</text:span>")
            i = j
        }
    }

    private fun meta(m: Manuscript): String = """<?xml version="1.0" encoding="UTF-8"?>
<office:document-meta $NS>
 <office:meta>
  <meta:generator>StoryArc</meta:generator>
  <dc:title>${esc(m.title)}</dc:title>
  ${m.author?.let { "<dc:creator>${esc(it)}</dc:creator>" } ?: ""}
  <meta:document-statistic meta:word-count="${m.words}"/>
 </office:meta>
</office:document-meta>
"""

    /** Plain XML escaping, for metadata outside text content. */
    private fun esc(s: String) = s.filter { it >= ' ' || it == '\t' }
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    /**
     * Escapes [s] for ODF text content: markup characters, characters XML forbids, tabs, and
     * runs of spaces (which ODF would otherwise collapse).
     */
    internal fun xml(s: String): String = buildString {
        var spaces = 0
        fun flushSpaces() {
            if (spaces == 0) return
            append(' ')
            if (spaces > 1) append("""<text:s text:c="${spaces - 1}"/>""")
            spaces = 0
        }
        for (c in s) {
            if (c == ' ') { spaces++; continue }
            flushSpaces()
            when {
                c == '&' -> append("&amp;")
                c == '<' -> append("&lt;")
                c == '>' -> append("&gt;")
                c == '"' -> append("&quot;")
                c == '\t' -> append("<text:tab/>")
                c == '\n' || c == '\r' -> append("<text:line-break/>")
                c < ' ' || c == '￾' || c == '￿' -> {}
                else -> append(c)
            }
        }
        flushSpaces()
    }
}
