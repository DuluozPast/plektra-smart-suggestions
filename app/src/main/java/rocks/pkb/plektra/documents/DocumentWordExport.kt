package rocks.pkb.plektra.documents

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Minimal interoperable Word package; no remote content, macros or external relationships. */
internal object DocumentWordExport {
    private const val MAX_WALLPAPER_BYTES = 2 * 1024 * 1024

    private fun xml(text: String): String = buildString {
        text.codePoints().forEach { cp ->
            when (cp) {
                38 -> append("&amp;")
                60 -> append("&lt;")
                62 -> append("&gt;")
                10 -> append("</w:t><w:br/><w:t xml:space=\"preserve\">")
                9 -> append("</w:t><w:tab/><w:t xml:space=\"preserve\">")
                else ->
                    if (cp == 13) Unit
                    else if (cp in 0x20..0xD7FF || cp in 0xE000..0xFFFD || cp in 0x10000..0x10FFFF)
                        appendCodePoint(cp)
                    else append('\uFFFD')
            }
        }
    }

    fun write(document: LocalDocument, output: OutputStream, wallpaperImage: ByteArray? = null) {
        val verified = LocalDocument.parse(document.json())
        val hasHeadings = verified.paragraphs.any { it.heading > 0 }
        val hasLists = verified.paragraphs.any { it.list != "none" }
        val hasWallpaper = verified.wallpaper != null
        require(
            !hasWallpaper || wallpaperImage != null && wallpaperImage.size in 1..MAX_WALLPAPER_BYTES
        )
        val hasBackground = verified.backgroundColor != null || hasWallpaper
        val groups = mutableListOf<String>()
        val body = buildString {
            append(
                "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><w:document xmlns:w=\"http://schemas.openxmlformats.org/wordprocessingml/2006/main\" xmlns:v=\"urn:schemas-microsoft-com:vml\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">"
            )
            if (hasWallpaper)
                append(
                    "<w:background w:color=\"${verified.backgroundColor?.removePrefix("#") ?: "FFFFFF"}\"><v:background id=\"documentWallpaper\"><v:fill type=\"frame\" r:id=\"rWallpaper\"/></v:background></w:background>"
                )
            else
                verified.backgroundColor?.let {
                    append("<w:background w:color=\"${it.removePrefix("#")}\"/>")
                }
            append("<w:body>")
            var previousList = "none"
            verified.paragraphs.forEach { paragraph ->
                if (paragraph.list != "none" && paragraph.list != previousList)
                    groups.add(paragraph.list)
                previousList = paragraph.list
                append("<w:p>")
                if (
                    paragraph.alignment != "left" ||
                        paragraph.indent > 0 ||
                        paragraph.heading > 0 ||
                        paragraph.list != "none"
                ) {
                    append("<w:pPr>")
                    if (paragraph.heading > 0)
                        append("<w:pStyle w:val=\"Heading${paragraph.heading}\"/>")
                    if (paragraph.list != "none")
                        append(
                            "<w:numPr><w:ilvl w:val=\"0\"/><w:numId w:val=\"${groups.size}\"/></w:numPr>"
                        )
                    if (paragraph.indent > 0)
                        append(
                            "<w:ind w:left=\"${720 * (paragraph.indent + if (paragraph.list != "none") 1 else 0)}\"/>"
                        )
                    append(
                        "<w:jc w:val=\"${if (paragraph.alignment == "justify") "both" else paragraph.alignment}\"/>"
                    )
                    append("</w:pPr>")
                }
                paragraph.runs.forEach { run ->
                    append("<w:r><w:rPr>")
                    if (run.bold) append("<w:b/>")
                    if (run.italic) append("<w:i/>")
                    run.color?.let { append("<w:color w:val=\"${it.removePrefix("#")}\"/>") }
                    run.fontSize?.let {
                        append("<w:sz w:val=\"${it * 2}\"/><w:szCs w:val=\"${it * 2}\"/>")
                    }
                    if (run.underline) append("<w:u w:val=\"single\"/>")
                    append("</w:rPr><w:t xml:space=\"preserve\">")
                        .append(xml(run.text))
                        .append("</w:t></w:r>")
                }
                append("</w:p>")
            }
            append(
                "<w:sectPr><w:pgSz w:w=\"11906\" w:h=\"16838\"/><w:pgMar w:top=\"1134\" w:right=\"1134\" w:bottom=\"1134\" w:left=\"1134\"/></w:sectPr></w:body></w:document>"
            )
        }
        ZipOutputStream(output).use { zip ->
            fun entry(name: String, content: String) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(content.toByteArray(Charsets.UTF_8))
                zip.closeEntry()
            }
            val extraTypes =
                (if (hasHeadings)
                    """<Override PartName="/word/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.styles+xml"/>"""
                else "") +
                    (if (hasLists)
                        """<Override PartName="/word/numbering.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.numbering+xml"/>"""
                    else "")
            val imageType =
                if (hasWallpaper) """<Default Extension="jpg" ContentType="image/jpeg"/>""" else ""
            val backgroundType =
                if (hasBackground)
                    """<Override PartName="/word/settings.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.settings+xml"/>"""
                else ""
            entry(
                "[Content_Types].xml",
                """<?xml version="1.0" encoding="UTF-8"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/><Default Extension="xml" ContentType="application/xml"/><Override PartName="/word/document.xml" ContentType="application/vnd.openxmlformats-officedocument.wordprocessingml.document.main+xml"/>$extraTypes$backgroundType$imageType</Types>""",
            )
            entry(
                "_rels/.rels",
                """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships"><Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="word/document.xml"/></Relationships>""",
            )
            entry("word/document.xml", body)
            if (hasHeadings || hasLists || hasBackground) {
                val relationships =
                    (if (hasHeadings)
                        """<Relationship Id="rStyles" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>"""
                    else "") +
                        (if (hasLists)
                            """<Relationship Id="rNumbering" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/numbering" Target="numbering.xml"/>"""
                        else "")
                val imageRelation =
                    if (hasWallpaper)
                        """<Relationship Id="rWallpaper" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image" Target="media/wallpaper.jpg"/>"""
                    else ""
                val backgroundRelation =
                    if (hasBackground)
                        """<Relationship Id="rSettings" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/settings" Target="settings.xml"/>"""
                    else ""
                entry(
                    "word/_rels/document.xml.rels",
                    """<?xml version="1.0" encoding="UTF-8"?><Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">$relationships$backgroundRelation$imageRelation</Relationships>""",
                )
            }
            if (hasWallpaper) {
                zip.putNextEntry(ZipEntry("word/media/wallpaper.jpg"))
                zip.write(wallpaperImage!!)
                zip.closeEntry()
            }
            if (hasBackground)
                entry(
                    "word/settings.xml",
                    """<?xml version="1.0" encoding="UTF-8"?><w:settings xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:displayBackgroundShape/></w:settings>""",
                )
            if (hasHeadings) {
                val styles =
                    (1..3).joinToString("") { level ->
                        """<w:style w:type="paragraph" w:styleId="Heading$level"><w:name w:val="heading $level"/><w:basedOn w:val="Normal"/><w:pPr><w:keepNext/><w:outlineLvl w:val="${level - 1}"/></w:pPr><w:rPr><w:b/><w:sz w:val="${listOf(32,28,24)[level - 1]}"/></w:rPr></w:style>"""
                    }
                entry(
                    "word/styles.xml",
                    """<?xml version="1.0" encoding="UTF-8"?><w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:style w:type="paragraph" w:default="1" w:styleId="Normal"><w:name w:val="Normal"/></w:style>$styles</w:styles>""",
                )
            }
            if (hasLists) {
                val abstracts =
                    listOf("bullet", "decimal")
                        .mapIndexed { index, type ->
                            """<w:abstractNum w:abstractNumId="${index+1}"><w:multiLevelType w:val="singleLevel"/><w:lvl w:ilvl="0"><w:start w:val="1"/><w:numFmt w:val="$type"/><w:lvlText w:val="${if (type == "bullet") "•" else "%1."}"/><w:lvlJc w:val="left"/><w:pPr><w:ind w:left="720" w:hanging="360"/></w:pPr></w:lvl></w:abstractNum>"""
                        }
                        .joinToString("")
                val numbers =
                    groups
                        .mapIndexed { index, type ->
                            """<w:num w:numId="${index+1}"><w:abstractNumId w:val="${if(type == "bullet") 1 else 2}"/><w:lvlOverride w:ilvl="0"><w:startOverride w:val="1"/></w:lvlOverride></w:num>"""
                        }
                        .joinToString("")
                entry(
                    "word/numbering.xml",
                    """<?xml version="1.0" encoding="UTF-8"?><w:numbering xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">$abstracts$numbers</w:numbering>""",
                )
            }
        }
    }
}
