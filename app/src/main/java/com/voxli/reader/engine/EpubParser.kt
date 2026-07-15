package com.voxli.reader.engine

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.util.zip.ZipEntry
import java.util.zip.ZipFile

/**
 * EPUB parser (EPUB 2/3).
 * Extracts text with basic formatting from XHTML content.
 *
 * Reference: roadmap §7.5 — DocumentModel contract.
 */
class EpubParser : BookParser {

    override suspend fun parse(file: java.io.File): DocumentModel {
        val bookId = file.nameWithoutExtension.toLongOrNull() ?: 0L
        val paragraphs = mutableListOf<ParagraphBlock>()
        var charOffset = 0
        var title = ""
        var author = ""

        ZipFile(file).use { zip ->
            // Find OPF file from container.xml
            val opfPath = findOpfPath(zip)
            if (opfPath == null) {
                // Fallback: scan for .opf
                val opfEntry = zip.entries().asSequence()
                    .find { it.name.endsWith(".opf") && !it.name.contains("META-INF") }
                if (opfEntry != null) {
                    val (t, a, entries) = parseOpf(zip.getInputStream(opfEntry))
                    title = t
                    author = a
                    // Parse each spine entry in order
                    for (entry in entries) {
                        val entryFile = zip.getEntry(entry)
                        if (entryFile != null) {
                            charOffset = parseXhtml(zip.getInputStream(entryFile), paragraphs, charOffset)
                        }
                    }
                }
                return DocumentModel(bookId, file.nameWithoutExtension, "", paragraphs, charOffset)
            }

            val opfEntry = zip.getEntry(opfPath) ?: return DocumentModel(bookId, file.nameWithoutExtension, "", paragraphs, 0)

            val (t, a, spineEntries) = parseOpf(zip.getInputStream(opfEntry))
            title = t
            author = a

            // Resolve relative paths
            val opfDir = opfPath.substringBeforeLast("/", "")
            for (entry in spineEntries) {
                val resolvedPath = if (opfDir.isNotEmpty()) "$opfDir/$entry" else entry
                val entryFile = zip.getEntry(resolvedPath)
                if (entryFile != null) {
                    charOffset = parseXhtml(zip.getInputStream(entryFile), paragraphs, charOffset)
                }
            }
        }

        return DocumentModel(
            bookId = bookId,
            title = title,
            author = author,
            paragraphs = paragraphs,
            totalChars = paragraphs.lastOrNull()?.globalCharEnd ?: 0,
        )
    }

    private fun findOpfPath(zip: ZipFile): String? {
        val containerEntry = zip.getEntry("META-INF/container.xml")
            ?: zip.getEntry("meta-inf/container.xml")
            ?: return null

        val factory = XmlPullParserFactory.newInstance()
        val parser = factory.newPullParser()
        parser.setInput(containerEntry?.let { zip.getInputStream(it) }, "UTF-8")

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            if (parser.eventType == XmlPullParser.START_TAG &&
                parser.name == "rootfile" &&
                "opf" in (parser.getAttributeValue(null, "media-type") ?: "")
            ) {
                return parser.getAttributeValue(null, "full-path")
            }
            parser.next()
        }
        return null
    }

    private data class OpfResult(
        val title: String,
        val author: String,
        val spine: List<String>,
    )

    private fun parseOpf(stream: java.io.InputStream): OpfResult {
        var title = ""
        var author = ""
        val spineEntries = mutableListOf<String>()
        val manifestMap = mutableMapOf<String, String>()  // id -> href
        var spineRefs = mutableListOf<String>()  // idref in spine order

        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(stream, "UTF-8")

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name) {
                            "title" -> title = parser.nextText().trim()
                            "creator" -> {
                                val text = parser.nextText().trim()
                                if (author.isEmpty()) author = text
                            }
                            "item" -> {
                                val id = parser.getAttributeValue(null, "id")
                                val href = parser.getAttributeValue(null, "href")
                                if (id != null && href != null) {
                                    manifestMap[id] = href
                                }
                            }
                            "itemref" -> {
                                val idref = parser.getAttributeValue(null, "idref")
                                if (idref != null) spineRefs.add(idref)
                            }
                        }
                    }
                }
                parser.next()
            }
        } catch (_: Exception) { }

        // Map spine idrefs to hrefs
        for (ref in spineRefs) {
            manifestMap[ref]?.let { spineEntries.add(it) }
        }

        return OpfResult(title, author, spineEntries)
    }

    private fun parseXhtml(
        stream: java.io.InputStream,
        paragraphs: MutableList<ParagraphBlock>,
        startOffset: Int,
    ): Int {
        var charOffset = startOffset
        var inParagraph = false
        var currentBold = false
        var currentItalic = false
        val sb = StringBuilder()

        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            parser.setInput(stream, "UTF-8")

            // Track depth to handle nested tags
            val boldStack = ArrayDeque<Boolean>()
            val italicStack = ArrayDeque<Boolean>()

            while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                when (parser.eventType) {
                    XmlPullParser.START_TAG -> {
                        when (parser.name.lowercase()) {
                            "p", "div" -> {
                                // Flush previous paragraph
                                charOffset = flushParagraph(sb, paragraphs, charOffset, currentBold, currentItalic, false)
                                inParagraph = true
                            }
                            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                                charOffset = flushParagraph(sb, paragraphs, charOffset, false, false, true)
                                inParagraph = true
                            }
                            "b", "strong" -> {
                                boldStack.addLast(currentBold)
                                currentBold = true
                            }
                            "i", "em" -> {
                                italicStack.addLast(currentItalic)
                                currentItalic = true
                            }
                            "br" -> sb.append('\n')
                            "img" -> {
                                val src = parser.getAttributeValue(null, "src") ?: ""
                                if (src.isNotEmpty()) {
                                    sb.append(" [img:$src] ")
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        val text = parser.text?.trim()
                        if (!text.isNullOrBlank()) {
                            if (!inParagraph) {
                                // Text outside paragraph — treat as paragraph
                                charOffset = flushParagraph(sb, paragraphs, charOffset, currentBold, currentItalic, false)
                                inParagraph = true
                            }
                            sb.append(text)
                            sb.append(' ')
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        when (parser.name.lowercase()) {
                            "p", "div", "h1", "h2", "h3", "h4", "h5", "h6" -> {
                                charOffset = flushParagraph(sb, paragraphs, charOffset, currentBold, currentItalic, parser.name.lowercase().startsWith("h"))
                                inParagraph = false
                            }
                            "b", "strong" -> {
                                currentBold = if (boldStack.isNotEmpty()) boldStack.removeLast() else false
                            }
                            "i", "em" -> {
                                currentItalic = if (italicStack.isNotEmpty()) italicStack.removeLast() else false
                            }
                        }
                    }
                }
                parser.next()
            }
        } catch (_: Exception) { }

        // Flush remaining text
        charOffset = flushParagraph(sb, paragraphs, charOffset, currentBold, currentItalic, false)
        return charOffset
    }

    private fun flushParagraph(
        sb: StringBuilder,
        paragraphs: MutableList<ParagraphBlock>,
        startOffset: Int,
        isBold: Boolean,
        isItalic: Boolean,
        isHeader: Boolean,
    ): Int {
        val text = sb.toString().trim()
        sb.clear()
        if (text.isEmpty()) return startOffset

        val block = ParagraphBlock(
            text = text,
            globalCharStart = startOffset,
            globalCharEnd = startOffset + text.length,
            isBold = isBold,
            isItalic = isItalic,
            isHeader = isHeader,
        )
        paragraphs.add(block)
        return block.globalCharEnd + 1
    }
}
