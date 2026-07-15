package com.voxli.reader.engine

import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Paginator — splits formatted text into pages using StaticLayout (thread-safe).
 *
 * Reference: roadmap §7.5 — thread-safe measurement with StaticLayout + TextPaint.
 *
 * @param document parsed book document
 * @param pageWidthPx viewport width in pixels
 * @param pageHeightPx viewport height in pixels (excluding progress bar)
 * @param textPaint pre-configured TextPaint from TextStyle + Density
 */
class Paginator(
    private val document: DocumentModel,
    private val pageWidthPx: Int,
    private val pageHeightPx: Int,
    private val textPaint: TextPaint,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val pageCache = mutableMapOf<Int, Page>()

    private val _pageCount = MutableStateFlow(0)
    val pageCount: StateFlow<Int> = _pageCount.asStateFlow()

    private val _currentPageIndex = MutableStateFlow(0)
    val currentPageIndex: StateFlow<Int> = _currentPageIndex.asStateFlow()

    private val _pagesReady = MutableStateFlow(false)
    val pagesReady: StateFlow<Boolean> = _pagesReady.asStateFlow()

    // Pre-calculated layout for each paragraph
    private val paragraphLayouts: List<ParagraphLayout> by lazy {
        document.paragraphs.map { block ->
            val layout = StaticLayout.Builder.obtain(
                block.text, 0, block.text.length,
                textPaint, pageWidthPx
            )
                .setLineSpacing(0f, 1.0f)
                .setBreakStrategy(Layout.BreakStrategy.SIMPLE)
                .setHyphenationFrequency(Layout.HyphenationFrequency.NONE)
                .build()

            ParagraphLayout(block, layout)
        }
    }

    /** Total lines across all paragraphs. */
    private val totalLines: Int by lazy {
        paragraphLayouts.sumOf { it.layout.lineCount }
    }

    /**
     * Calculate all page breaks. Runs in background, updates state flows.
     */
    fun calculatePages() {
        scope.launch {
            val pages = mutableListOf<Page>()
            var currentPageLines = mutableListOf<PageLine>()
            var linesOnPage = 0
            var pageCharStart = 0
            var pageCharEnd = 0

            // Calculate max lines per page
            val lineHeight = getLineHeight()
            val maxLinesPerPage = if (lineHeight > 0) pageHeightPx / lineHeight else Int.MAX_VALUE
            val safeMaxLines = maxOf(1, maxLinesPerPage - 1)  // leave 1 line margin

            var globalLineIndex = 0

            for ((paraIndex, pl) in paragraphLayouts.withIndex()) {
                val paraLineCount = pl.layout.lineCount

                for (lineInPara in 0 until paraLineCount) {
                    val lineStart = pl.layout.getLineStart(lineInPara)
                    val lineEnd = pl.layout.getLineEnd(lineInPara)
                    val lineText = pl.block.text.substring(lineStart, lineEnd)

                    currentPageLines.add(
                        PageLine(
                            paragraphIndex = paraIndex,
                            lineInParagraph = lineInPara,
                            text = lineText,
                            charStartInBlock = lineStart,
                            charEndInBlock = lineEnd,
                            globalCharStart = pl.block.globalCharStart + lineStart,
                            globalCharEnd = pl.block.globalCharStart + lineEnd,
                            isBold = pl.block.isBold,
                            isItalic = pl.block.isItalic,
                            isHeader = pl.block.isHeader,
                        )
                    )
                    linesOnPage++

                    if (linesOnPage >= safeMaxLines || globalLineIndex == totalLines - 1) {
                        // Finalize page
                        val firstLine = currentPageLines.first()
                        val lastLine = currentPageLines.last()
                        pages.add(
                            Page(
                                lines = currentPageLines.toList(),
                                charOffsetStart = firstLine.globalCharStart,
                                charOffsetEnd = lastLine.globalCharEnd,
                                pageIndex = pages.size,
                            )
                        )
                        currentPageLines = mutableListOf()
                        linesOnPage = 0
                    }

                    globalLineIndex++
                }
            }

            // Add remaining lines as last page
            if (currentPageLines.isNotEmpty()) {
                val firstLine = currentPageLines.first()
                val lastLine = currentPageLines.last()
                pages.add(
                    Page(
                        lines = currentPageLines.toList(),
                        charOffsetStart = firstLine.globalCharStart,
                        charOffsetEnd = lastLine.globalCharEnd,
                        pageIndex = pages.size,
                    )
                )
            }

            // Populate cache
            pageCache.clear()
            pages.forEach { page ->
                pageCache[page.pageIndex] = page
            }
            _pageCount.value = pages.size
            _pagesReady.value = true
        }
    }

    /** Get a specific page. Returns null if not yet calculated. */
    fun getPage(index: Int): Page? = pageCache[index]

    /** Find page index by char_offset. Linear scan, O(n). */
    fun findPageByCharOffset(charOffset: Int): Int {
        if (pageCache.isEmpty()) return 0
        for (i in 0 until pageCount.value) {
            val page = pageCache[i] ?: continue
            if (charOffset in page.charOffsetStart until page.charOffsetEnd) {
                return i
            }
        }
        return pageCount.value - 1
    }

    /** Navigate to a specific page. */
    fun goToPage(index: Int) {
        val clamped = index.coerceIn(0, maxOf(0, pageCount.value - 1))
        _currentPageIndex.value = clamped
    }

    /** Move one page forward. */
    fun nextPage() {
        goToPage(_currentPageIndex.value + 1)
    }

    /** Move one page backward. */
    fun prevPage() {
        goToPage(_currentPageIndex.value - 1)
    }

    /** Cancel all background work. Must be called from ViewModel.onCleared(). */
    fun cancel() {
        scope.cancel()
    }

    private fun getLineHeight(): Int {
        val paint = textPaint
        val fm = paint.fontMetricsInt
        return fm.descent - fm.ascent + 4  // +4 for line spacing
    }

    /** Rebuild with new dimensions (e.g., on font change or rotation). */
    fun rebuild(newWidthPx: Int, newHeightPx: Int, newTextPaint: TextPaint): Paginator {
        cancel()
        // Note: TextPaint is immutable in this context
        return Paginator(document, newWidthPx, newHeightPx, newTextPaint)
    }
}

/** Pre-computed layout for a single paragraph. */
data class ParagraphLayout(
    val block: ParagraphBlock,
    val layout: StaticLayout,
)

/** A single line of text within a page. */
data class PageLine(
    val paragraphIndex: Int,
    val lineInParagraph: Int,
    val text: String,
    val charStartInBlock: Int,
    val charEndInBlock: Int,
    val globalCharStart: Int,
    val globalCharEnd: Int,
    val isBold: Boolean = false,
    val isItalic: Boolean = false,
    val isHeader: Boolean = false,
)

/** A page containing multiple lines. */
data class Page(
    val lines: List<PageLine>,
    val charOffsetStart: Int,
    val charOffsetEnd: Int,
    val pageIndex: Int,
)
