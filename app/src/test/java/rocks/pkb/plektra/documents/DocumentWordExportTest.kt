package rocks.pkb.plektra.documents

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.w3c.dom.Document
import org.w3c.dom.Element

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DocumentWordExportTest {
    private val word = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    private val relationships = "http://schemas.openxmlformats.org/package/2006/relationships"

    private fun export(document: LocalDocument, image: ByteArray? = null): Map<String, ByteArray> {
        val out = ByteArrayOutputStream()
        DocumentWordExport.write(document, out, image)
        val files = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                assertNull("Duplicate package part", files.put(entry.name, zip.readBytes()))
                entry = zip.nextEntry
            }
        }
        files.filterKeys { it.endsWith(".xml") || it.endsWith(".rels") }.values.forEach(::parse)
        return files
    }

    private fun parse(bytes: ByteArray): Document =
        DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(ByteArrayInputStream(bytes))

    private fun elements(document: Document, name: String): List<Element> {
        val nodes = document.getElementsByTagNameNS(word, name)
        return (0 until nodes.length).map { nodes.item(it) as Element }
    }

    private fun sample() =
        LocalDocument.blank()
            .copy(
                title = "Word export review",
                paragraphs =
                    listOf(
                        DocumentParagraph(listOf(DocumentRun("Word export review")), heading = 1),
                        DocumentParagraph(
                            listOf(
                                DocumentRun("Bold", bold = true),
                                DocumentRun(" italic", italic = true),
                                DocumentRun(" underlined", underline = true),
                                DocumentRun(" blue", color = "#245A81", fontSize = 14),
                            )
                        ),
                        DocumentParagraph(
                            listOf(
                                DocumentRun(
                                    "French: \u00ab\u00a0bonjour\u00a0\u00bb and narrow\u202fspace. Accents: \u00e9\u00e0. Emoji: \uD83D\uDE42."
                                )
                            )
                        ),
                        DocumentParagraph(listOf(DocumentRun("First item")), list = "number"),
                        DocumentParagraph(listOf(DocumentRun("Second item")), list = "number"),
                        DocumentParagraph(
                            listOf(
                                DocumentRun(
                                    "A separate paragraph with a line break\nnext line and a tab\tend."
                                )
                            ),
                            alignment = "justify",
                        ),
                        DocumentParagraph(listOf(DocumentRun("A restarted list")), list = "number"),
                        DocumentParagraph(
                            listOf(DocumentRun("A bullet")),
                            list = "bullet",
                            indent = 1,
                        ),
                        DocumentParagraph(emptyList()),
                        DocumentParagraph(listOf(DocumentRun("Right aligned")), alignment = "right"),
                    ),
            )

    @Test
    fun plainPackageContainsOnlyInternalDocumentRelationships() {
        val files = export(LocalDocument.blank())
        assertEquals(setOf("[Content_Types].xml", "_rels/.rels", "word/document.xml"), files.keys)
        val rel =
            parse(files.getValue("_rels/.rels"))
                .getElementsByTagNameNS(relationships, "Relationship")
                .item(0) as Element
        assertEquals("word/document.xml", rel.getAttribute("Target"))
        assertFalse(rel.hasAttribute("TargetMode"))
    }

    @Test
    fun unicodeWhitespaceAndXmlCharactersSurviveWithoutExternalContent() {
        val text = "  <tag> & \"quote\" \u00ab\u00a0\u00e9\u00a0\u00bb \u202f\uD83D\uDE42  "
        val document =
            LocalDocument.blank()
                .copy(
                    paragraphs =
                        listOf(DocumentParagraph(listOf(DocumentRun(text + "\nnext\tlast"))))
                )
        val xml = parse(export(document).getValue("word/document.xml"))
        assertEquals(text + "nextlast", xml.documentElement.textContent)
        assertEquals(1, elements(xml, "br").size)
        assertEquals(1, elements(xml, "tab").size)
        elements(xml, "t").forEach {
            assertEquals(
                "preserve",
                it.getAttributeNS("http://www.w3.org/XML/1998/namespace", "space"),
            )
        }
    }

    @Test
    fun invalidXmlCodePointsAreReplaced() {
        val document =
            LocalDocument.blank()
                .copy(
                    paragraphs =
                        listOf(
                            DocumentParagraph(listOf(DocumentRun("a\u0000b\u0001c\uD800d\uFFFFe")))
                        )
                )
        val xml = parse(export(document).getValue("word/document.xml"))
        assertEquals("a\uFFFDb\uFFFDc\uFFFDd\uFFFDe", xml.documentElement.textContent)
    }

    @Test
    fun stylesAndParagraphStructureArePreserved() {
        val files = export(sample())
        val xml = parse(files.getValue("word/document.xml"))
        assertEquals(sample().paragraphs.size, elements(xml, "p").size)
        assertEquals(1, elements(xml, "b").size)
        assertEquals(1, elements(xml, "i").size)
        assertEquals("single", elements(xml, "u").single().getAttributeNS(word, "val"))
        assertEquals("245A81", elements(xml, "color").single().getAttributeNS(word, "val"))
        assertEquals("28", elements(xml, "sz").single().getAttributeNS(word, "val"))
        assertEquals("Heading1", elements(xml, "pStyle").single().getAttributeNS(word, "val"))
        assertTrue(
            elements(xml, "jc")
                .map { it.getAttributeNS(word, "val") }
                .containsAll(listOf("both", "right"))
        )
        assertEquals("1440", elements(xml, "ind").single().getAttributeNS(word, "left"))
        val styleIds =
            elements(parse(files.getValue("word/styles.xml")), "style").map {
                it.getAttributeNS(word, "styleId")
            }
        assertTrue(styleIds.containsAll(listOf("Normal", "Heading1", "Heading2", "Heading3")))
    }

    @Test
    fun separatedListsRestartAndAllPackageTargetsExist() {
        val files = export(sample())
        val xml = parse(files.getValue("word/document.xml"))
        assertEquals(
            listOf("1", "1", "2", "3"),
            elements(xml, "numId").map { it.getAttributeNS(word, "val") },
        )
        val numbering = parse(files.getValue("word/numbering.xml"))
        assertEquals(3, elements(numbering, "num").size)
        val rels =
            parse(files.getValue("word/_rels/document.xml.rels"))
                .getElementsByTagNameNS(relationships, "Relationship")
        for (index in 0 until rels.length) {
            val rel = rels.item(index) as Element
            assertFalse(rel.hasAttribute("TargetMode"))
            assertTrue(files.containsKey("word/" + rel.getAttribute("Target")))
        }
        val types =
            parse(files.getValue("[Content_Types].xml"))
                .getElementsByTagNameNS(
                    "http://schemas.openxmlformats.org/package/2006/content-types",
                    "Override",
                )
        for (index in 0 until types.length) {
            assertTrue(
                files.containsKey(
                    (types.item(index) as Element).getAttribute("PartName").removePrefix("/")
                )
            )
        }
        assertFalse(files.keys.any { it.contains("vba", ignoreCase = true) })
    }

    @Test
    fun solidPageBackgroundHasSettingsAndNoImage() {
        val files = export(LocalDocument.blank().copy(backgroundColor = "#fef1de"))
        val xml = parse(files.getValue("word/document.xml"))
        assertEquals("FEF1DE", elements(xml, "background").single().getAttributeNS(word, "color"))
        assertEquals(
            1,
            elements(parse(files.getValue("word/settings.xml")), "displayBackgroundShape").size,
        )
        assertFalse(files.keys.any { it.startsWith("word/media/") })
    }

    @Test
    fun rejectedInputLeavesDestinationUntouched() {
        val out = ByteArrayOutputStream()
        assertThrows(IllegalArgumentException::class.java) {
            DocumentWordExport.write(LocalDocument.blank().copy(id = "invalid"), out)
        }
        assertEquals(0, out.size())
        val wallpaper = DocumentWallpaper("wallpaper-" + "a".repeat(64) + ".jpg")
        val document = LocalDocument.blank().copy(wallpaper = wallpaper)
        assertThrows(IllegalArgumentException::class.java) {
            DocumentWordExport.write(document, out)
        }
        assertThrows(IllegalArgumentException::class.java) {
            DocumentWordExport.write(document, out, ByteArray(2 * 1024 * 1024 + 1))
        }
        assertEquals(0, out.size())
    }

    @Test
    fun photoBackgroundIsEmbeddedWithoutExternalRelationships() {
        val image =
            javaClass.getResourceAsStream("/document-word-wallpaper-fixture.jpg")!!.use {
                it.readBytes()
            }
        val wallpaper = DocumentWallpaper("wallpaper-" + "a".repeat(64) + ".jpg")
        val files = export(LocalDocument.blank().copy(wallpaper = wallpaper), image)
        assertArrayEquals(image, files.getValue("word/media/wallpaper.jpg"))
        val xml = parse(files.getValue("word/document.xml"))
        assertEquals(1, elements(xml, "background").size)
        val nodes =
            parse(files.getValue("word/_rels/document.xml.rels"))
                .getElementsByTagNameNS(relationships, "Relationship")
        val imageRel =
            (0 until nodes.length)
                .map { nodes.item(it) as Element }
                .single { it.getAttribute("Type").endsWith("/image") }
        assertEquals("media/wallpaper.jpg", imageRel.getAttribute("Target"))
        assertFalse(imageRel.hasAttribute("TargetMode"))
    }

    @Test
    fun exportsLongFictionalNoteAndReviewFixture() {
        val longText = "sample word ".repeat(13500)
        val longDocument =
            LocalDocument.blank()
                .copy(paragraphs = listOf(DocumentParagraph(listOf(DocumentRun(longText)))))
        assertEquals(
            longText,
            parse(export(longDocument).getValue("word/document.xml")).documentElement.textContent,
        )
        val fixture = File("build/reports/document-word-export/review.docx")
        check(fixture.parentFile!!.isDirectory || fixture.parentFile!!.mkdirs())
        fixture.outputStream().use { DocumentWordExport.write(sample(), it) }
        assertTrue(fixture.length() > 0)
    }
}
