package com.voxli.reader.engine

/**
 * Pure-Kotlin interface for text measurement.
 * Implementations (e.g. AndroidTextMeasurer) provide actual Android SDK measurement.
 *
 * Reference: roadmap §7.5 — TextMeasurer contract.
 */
interface TextMeasurer {

    /**
     * Measure each paragraph and return per-line data.
     * @param paragraphs paragraphs to measure
     * @param pageWidthPx available width in pixels
     * @return list of measured paragraphs with line-level detail
     */
    fun measureParagraphs(
        paragraphs: List<ParagraphBlock>,
        pageWidthPx: Int,
    ): List<MeasuredParagraph>

    /**
     * Get the pixel height of a single line of text.
     * Used by Paginator to calculate max lines per page.
     */
    fun getLineHeightPx(): Int
}

/**
 * Result of measuring a single paragraph.
 */
data class MeasuredParagraph(
    val block: ParagraphBlock,
    val lines: List<MeasuredLine>,
)

/**
 * A single line within a measured paragraph.
 */
data class MeasuredLine(
    val text: String,
    val charStartInBlock: Int,
    val charEndInBlock: Int,
)
