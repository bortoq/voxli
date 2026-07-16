package com.voxli.reader.android

import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import com.voxli.reader.engine.MeasuredLine
import com.voxli.reader.engine.MeasuredParagraph
import com.voxli.reader.engine.ParagraphBlock
import com.voxli.reader.engine.TextMeasurer

/**
 * Android implementation of TextMeasurer using StaticLayout + TextPaint.
 *
 * This is the ONLY place in the codebase that depends on android.text.* classes
 * for text measurement. Everything else works with pure Kotlin data structures.
 *
 * Reference: roadmap §7.5 — AndroidTextMeasurer.
 */
class AndroidTextMeasurer(
    private val textPaint: TextPaint,
) : TextMeasurer {

    override fun measureParagraphs(
        paragraphs: List<ParagraphBlock>,
        pageWidthPx: Int,
    ): List<MeasuredParagraph> {
        return paragraphs.map { block ->
            val layout = StaticLayout.Builder.obtain(
                block.text, 0, block.text.length,
                textPaint, pageWidthPx
            )
                .setLineSpacing(0f, 1.0f)
                .setBreakStrategy(Layout.BREAK_STRATEGY_SIMPLE)
                .setHyphenationFrequency(Layout.HYPHENATION_FREQUENCY_NONE)
                .build()

            val lines = (0 until layout.lineCount).map { i ->
                MeasuredLine(
                    text = block.text.substring(layout.getLineStart(i), layout.getLineEnd(i)),
                    charStartInBlock = layout.getLineStart(i),
                    charEndInBlock = layout.getLineEnd(i),
                )
            }

            MeasuredParagraph(block, lines)
        }
    }

    override fun getLineHeightPx(): Int {
        val fm = textPaint.fontMetricsInt
        return fm.descent - fm.ascent + fm.leading
    }
}
