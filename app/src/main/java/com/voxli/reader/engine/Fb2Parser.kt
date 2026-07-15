package com.voxli.reader.engine

import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream

/**
 * FB2 parser (FictionBook 2.0).
 * Extracts text with basic formatting (bold, italic, headers) and image references.
 *
 * Reference: roadmap §7.5 — DocumentModel contract.
 * TextLector parsers are Apache 2.0; this is a clean-room implementation
 * following the FB2 spec: http://www.gribuser.ru/xml/fictionbook/2.0
 */
class Fb2Parser : BookParser {

    override suspend fun parse(file: java.io.File): DocumentModel {
        val bookId = file.nameWithoutExtension.toLongOrNull() ?: 0L
        val title = file.nameWithoutExtension

        val paragraphs = mutableListOf<ParagraphBlock>()
        var charOffset = 0
        var currentTitle = ""
        var currentAuthor = ""

        val factory = XmlPullParserFactory.newInstance()
        factory.isNamespaceAware = true
        val parser = factory.newPullParser()

        file.inputStream().use { stream ->
            parser.setInput(stream, "UTF-8")
            val result = parseFb2(parser, paragraphs, charOffset)
            currentTitle = result.title
            charOffset = result.totalChars
        }

        // Re-read to extract title and author properly
        val (docTitle, docAuthor) = extractMeta(file)
        if (docTitle.isNotEmpty()) currentTitle = docTitle
        if (docAuthor.isNotEmpty()) currentAuthor = docAuthor

        return DocumentModel(
            bookId = bookId,
            title = currentTitle,
            author = currentAuthor,
            paragraphs = paragraphs,
            totalChars = paragraphs.lastOrNull()?.globalCharEnd ?: 0,
        )
    }

    private data class ParseResult(
        val title: String,
        val author: String,
        val paragraphs: List<ParagraphBlock>,
        val totalChars: Int,
    )

    private fun parseFb2(
        parser: XmlPullParser,
        paragraphs: MutableList<ParagraphBlock>,
        startOffset: Int,
    ): ParseResult {
        var charOffset = startOffset
        var title = ""
        var author = ""

        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    when (parser.name) {
                        "title-info" -> {
                            val (t, a) = parseTitleInfo(parser)
                            if (t.isNotEmpty()) title = t
                            if (a.isNotEmpty()) author = a
                        }
                        "body" -> {
                            charOffset = parseBody(parser, paragraphs, charOffset)
                        }
                    }
                }
            }
            parser.next()
        }

        return ParseResult(title, author, paragraphs, charOffset)
    }

    private fun parseTitleInfo(parser: XmlPullParser): Pair<String, String> {
        var title = ""
        var author = ""
        var depth = 1

        while (depth > 0) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    depth++
                    when (parser.name) {
                        "book-title" -> title = parser.nextText().trim()
                        "author" -> author = parseAuthor(parser)
                    }
                }
                XmlPullParser.END_TAG -> depth--
            }
            if (depth > 0) parser.next()
        }
        return title to author
    }

    private fun parseAuthor(parser: XmlPullParser): String {
        val parts = mutableListOf<String>()
        var depth = 1

        while (depth > 0) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> depth++
                XmlPullParser.TEXT -> {
                    val text = parser.text?.trim()
                    if (!text.isNullOrBlank()) parts.add(text)
                }
                XmlPullParser.END_TAG -> depth--
            }
            if (depth > 0) parser.next()
        }
        return parts.joinToString(" ")
    }

    private fun parseBody(
        parser: XmlPullParser,
        paragraphs: MutableList<ParagraphBlock>,
        startOffset: Int,
    ): Int {
        var charOffset = startOffset
        var depth = 1

        while (depth > 0) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    depth++
                    when (parser.name) {
                        "title" -> charOffset = parseTitleBlock(parser, paragraphs, charOffset)
                        "p" -> charOffset = parseParagraph(parser, paragraphs, charOffset, isBold = false, isItalic = false)
                        "subtitle" -> charOffset = parseParagraph(parser, paragraphs, charOffset, isHeader = true)
                        "empty-line" -> {
                            // Insert empty paragraph for spacing
                            paragraphs.add(
                                ParagraphBlock(
                                    text = "",
                                    globalCharStart = charOffset,
                                    globalCharEnd = charOffset,
                                    isHeader = false,
                                )
                            )
                        }
                        "section" -> { /* sections are logical containers, continue */ }
                        "image" -> {
                            // Image reference
                            val href = parser.getAttributeValue(null, "href")?.removePrefix("#") ?: ""
                            charOffset = addImageBlock(paragraphs, charOffset, href)
                        }
                    }
                }
                XmlPullParser.END_TAG -> depth--
            }
            if (depth > 0) parser.next()
        }
        return charOffset
    }

    private fun parseTitleBlock(
        parser: XmlPullParser,
        paragraphs: MutableList<ParagraphBlock>,
        startOffset: Int,
    ): Int {
        var charOffset = startOffset
        var depth = 1

        while (depth > 0) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    depth++
                    if (parser.name == "p") {
                        charOffset = parseParagraph(parser, paragraphs, charOffset, isHeader = true)
                    }
                }
                XmlPullParser.END_TAG -> depth--
            }
            if (depth > 0) parser.next()
        }
        return charOffset
    }

    private fun parseParagraph(
        parser: XmlPullParser,
        paragraphs: MutableList<ParagraphBlock>,
        startOffset: Int,
        isBold: Boolean = false,
        isItalic: Boolean = false,
        isHeader: Boolean = false,
    ): Int {
        val sb = StringBuilder()
        var currentBold = isBold
        var currentItalic = isItalic
        var depth = 1

        while (depth > 0) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    depth++
                    when (parser.name) {
                        "strong", "b" -> currentBold = true
                        "emphasis", "i" -> currentItalic = true
                    }
                }
                XmlPullParser.TEXT -> {
                    parser.text?.let { sb.append(it) }
                }
                XmlPullParser.END_TAG -> {
                    depth--
                    when (parser.name) {
                        "strong", "b" -> currentBold = isBold
                        "emphasis", "i" -> currentItalic = isItalic
                    }
                }
            }
            if (depth > 0) parser.next()
        }

        val text = sb.toString().trim()
        if (text.isEmpty() && !isHeader) return startOffset

        val block = ParagraphBlock(
            text = text,
            globalCharStart = startOffset,
            globalCharEnd = startOffset + text.length,
            isBold = currentBold,
            isItalic = currentItalic,
            isHeader = isHeader,
        )
        paragraphs.add(block)
        return block.globalCharEnd + 1  // +1 for paragraph separator
    }

    private fun addImageBlock(
        paragraphs: MutableList<ParagraphBlock>,
        startOffset: Int,
        imageId: String,
    ): Int {
        val block = ParagraphBlock(
            text = "",
            globalCharStart = startOffset,
            globalCharEnd = startOffset,
            isHeader = false,
        )
        paragraphs.add(block)
        return startOffset + 1
    }

    private fun extractMeta(file: java.io.File): Pair<String, String> {
        var title = ""
        var author = ""
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            file.inputStream().use { stream ->
                parser.setInput(stream, "UTF-8")
                var depth = 0
                var inTitleInfo = false

                while (parser.eventType != XmlPullParser.END_DOCUMENT) {
                    when (parser.eventType) {
                        XmlPullParser.START_TAG -> {
                            depth++
                            if (parser.name == "title-info") inTitleInfo = true
                            if (inTitleInfo && parser.name == "book-title") {
                                title = parser.nextText().trim()
                            }
                            if (inTitleInfo && parser.name == "author") {
                                author = parseAuthor(parser)
                            }
                        }
                        XmlPullParser.END_TAG -> {
                            depth--
                            if (parser.name == "title-info") return title to author
                        }
                    }
                    parser.next()
                }
            }
        } catch (_: Exception) { }
        return title to author
    }
}
