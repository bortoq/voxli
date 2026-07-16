package com.voxli.reader.engine

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Paginator — splits formatted text into pages using a TextMeasurer.
 *
 * Pure Kotlin, no Android SDK dependencies. All text measurement is delegated to
 * a [TextMeasurer] implementation (e.g. AndroidTextMeasurer).
 *
 * Reference: roadmap §7.5 — thread-safe paginator.
 *
 * @param document parsed book document
 * @param pageWidthPx viewport width in pixels
 * @param pageHeightPx viewport height in pixels (excluding progress bar)
 * @param textMeasurer text measurement engine (injected)
 */
class Paginator(
    private val document: DocumentModel,
    private val pageWidthPx: Int,
    private val pageHeightPx: Int,
    private val textMeasurer: TextMeasurer,
) {
    private val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private val pageCache = mutableMapOf<Int, Page>()

    private val _pageCount = MutableStateFlow(0)
    val pageCount: StateFlow<Int> = _pageCount.asStateFlow()

    private val _currentPageIndex = MutableStateFlow(0)
    val currentPageIndex: StateFlow<Int> = _currentPageIndex.asStateFlow()

    private val _pagesReady = MutableStateFlow(false)
    val pagesReady: StateFlow<Boolean> = _pagesReady.asStateFlow()

    // Pre-calculated layout for each paragraph (lazy, first access triggers measurement)
    private val paragraphLayouts: List<MeasuredParagraph> by lazy {
        textMeasurer.measureParagraphs(document.paragraphs, pageWidthPx)
    }

    /** Total lines across all paragraphs. */
    private val totalLines: Int by lazy {
        paragraphLayouts.sumOf { it.lines.size }
    }

    private val lineHeightPx: Int by lazy {
        textMeasurer.getLineHeightPx()
    }

    /**
     * Calculate all page breaks. Runs in background, updates state flows.
     */
    fun calculatePages() {
        // Force lazy initialization on the calling thread (may be background, not main)
        val layouts = paragraphLayouts
        val lh = lineHeightPx
        scope.launch {
            val pages = mutableListOf<Page>()
            var currentPageLines = mutableListOf<PageLine>()
            var linesOnPage = 0

            // Calculate max lines per page
            val maxLinesPerPage = if (lineHeightPx > 0) pageHeightPx / lineHeightPx else Int.MAX_VALUE
            val safeMaxLines = maxOf(1, maxLinesPerPage - 1)  // leave 1 line margin

            var globalLineIndex = 0

            for ((paraIndex, mp) in paragraphLayouts.withIndex()) {
                for ((lineInPara, line) in mp.lines.withIndex()) {
                    currentPageLines.add(
                        PageLine(
                            paragraphIndex = paraIndex,
                            lineInParagraph = lineInPara,
                            text = line.text,
                            charStartInBlock = line.charStartInBlock,
                            charEndInBlock = line.charEndInBlock,
                            globalCharStart = mp.block.globalCharStart + line.charStartInBlock,
                            globalCharEnd = mp.block.globalCharStart + line.charEndInBlock,
                            isBold = mp.block.isBold,
                            isItalic = mp.block.isItalic,
                            isHeader = mp.block.isHeader,
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

    /** Rebuild with new dimensions (e.g., on font change or rotation). */
    fun rebuild(newWidthPx: Int, newHeightPx: Int, newTextMeasurer: TextMeasurer): Paginator {
        cancel()
        return Paginator(document, newWidthPx, newHeightPx, newTextMeasurer)
    }
}

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
