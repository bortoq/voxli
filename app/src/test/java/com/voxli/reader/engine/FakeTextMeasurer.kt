package com.voxli.reader.engine

/**
 * Fake TextMeasurer for unit tests.
 * Returns deterministic results without Android SDK dependencies.
 *
 * - Wraps text at `wordsPerLine` words per line.
 * - Reports fixed `lineHeightPx` (default 32).
 */
class FakeTextMeasurer(
    var wordsPerLine: Int = 10,
    private val lineHeightPx: Int = 32,
) : TextMeasurer {

    override fun measureParagraphs(
        paragraphs: List<ParagraphBlock>,
        pageWidthPx: Int,
    ): List<MeasuredParagraph> {
        return paragraphs.map { block ->
            val words = block.text.split(" ")
            val lineGroups = words.chunked(wordsPerLine)
            val lines = lineGroups.mapIndexed { idx, group ->
                val lineText = group.joinToString(" ")
                val prevChars = lineGroups.take(idx).sumOf { it.joinToString(" ").length + if (it != lineGroups.last()) 1 else 0 }
                MeasuredLine(
                    text = lineText,
                    charStartInBlock = if (idx == 0) 0 else prevChars,
                    charEndInBlock = if (idx == 0) lineText.length else prevChars + lineText.length,
                )
            }
            MeasuredParagraph(block, lines)
        }
    }

    override fun getLineHeightPx(): Int = lineHeightPx
}
