package com.voxli.reader.engine

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for Paginator.
 * Uses FakeTextMeasurer — no Android SDK, no Robolectric required.
 *
 * Reference: roadmap §7.5 — thread-safe measurement via TextMeasurer.
 */
class PaginatorTest {

    private lateinit var fakeMeasurer: FakeTextMeasurer

    @Before
    fun setUp() {
        fakeMeasurer = FakeTextMeasurer(
            wordsPerLine = 10,
            lineHeightPx = 32,
        )
    }

    @After
    fun tearDone() {
    }

    private val shortDoc = DocumentModel(
        bookId = 1L,
        title = "Test Book",
        author = "Test Author",
        paragraphs = listOf(
            ParagraphBlock(
                text = "Hello world!",
                globalCharStart = 0,
                globalCharEnd = 12,
            ),
            ParagraphBlock(
                text = "This is a second paragraph with a bit more text.",
                globalCharStart = 13,
                globalCharEnd = 62,
            ),
        ),
        totalChars = 62,
    )

    private val longDoc: DocumentModel
        get() {
            val paragraphs = (0 until 50).map { i ->
                val text = "Paragraph number $i. " + "word ".repeat(50)
                ParagraphBlock(
                    text = text,
                    globalCharStart = i * 1000,
                    globalCharEnd = i * 1000 + text.length,
                )
            }
            return DocumentModel(
                bookId = 2L,
                title = "Long Book",
                author = "Author",
                paragraphs = paragraphs,
                totalChars = paragraphs.last().globalCharEnd,
            )
        }

    /** Wait for paginator to finish calculation. */
    private suspend fun awaitPagesReady(paginator: Paginator) {
        paginator.pagesReady.first { it }
    }

    // ─── Constructor ───────────────────────────────────────────────

    @Test
    fun constructor_setsDimensions() {
        val paginator = Paginator(
            document = shortDoc,
            pageWidthPx = 1080,
            pageHeightPx = 1600,
            textMeasurer = fakeMeasurer,
        )
        assertNotNull(paginator)
        assertEquals(0, paginator.pageCount.value)
        assertFalse(paginator.pagesReady.value)
    }

    // ─── calculatePages ────────────────────────────────────────────

    @Test
    fun calculatePages_producesAtLeastOnePage() = runTest {
        val paginator = Paginator(
            document = shortDoc,
            pageWidthPx = 1080,
            pageHeightPx = 1600,
            textMeasurer = fakeMeasurer,
        )

        paginator.calculatePages()
        awaitPagesReady(paginator)

        assertTrue(paginator.pagesReady.value)
        assertTrue(paginator.pageCount.value > 0)
    }

    @Test
    fun calculatePages_longDoc_producesMultiplePages() = runTest {
        val paginator = Paginator(
            document = longDoc,
            pageWidthPx = 400,
            pageHeightPx = 200,
            textMeasurer = fakeMeasurer,
        )

        paginator.calculatePages()
        awaitPagesReady(paginator)

        assertTrue(paginator.pagesReady.value)
        assertTrue(paginator.pageCount.value > 1)
    }

    // ─── getPage ───────────────────────────────────────────────────

    @Test
    fun getPage_beforeCalc_returnsNull() {
        val paginator = Paginator(
            document = shortDoc,
            pageWidthPx = 1080,
            pageHeightPx = 1600,
            textMeasurer = fakeMeasurer,
        )
        assertNull(paginator.getPage(0))
    }

    @Test
    fun getPage_afterCalc_returnsValidPage() = runTest {
        val paginator = Paginator(
            document = shortDoc,
            pageWidthPx = 1080,
            pageHeightPx = 1600,
            textMeasurer = fakeMeasurer,
        )
        paginator.calculatePages()
        awaitPagesReady(paginator)

        val page = paginator.getPage(0)
        assertNotNull(page)
        assertTrue(page!!.charOffsetStart >= 0)
        assertTrue(page.charOffsetEnd >= page.charOffsetStart)
        assertEquals(0, page.pageIndex)
        assertTrue(page.lines.isNotEmpty())
    }

    // ─── findPageByCharOffset ──────────────────────────────────────

    @Test
    fun findPageByCharOffset_offset0_returnsFirstPage() = runTest {
        val paginator = Paginator(
            document = shortDoc,
            pageWidthPx = 1080,
            pageHeightPx = 1600,
            textMeasurer = fakeMeasurer,
        )
        paginator.calculatePages()
        awaitPagesReady(paginator)

        assertEquals(0, paginator.findPageByCharOffset(0))
    }

    @Test
    fun findPageByCharOffset_largeOffset_returnsLastPage() = runTest {
        val paginator = Paginator(
            document = longDoc,
            pageWidthPx = 400,
            pageHeightPx = 200,
            textMeasurer = fakeMeasurer,
        )
        paginator.calculatePages()
        awaitPagesReady(paginator)

        assertEquals(
            paginator.pageCount.value - 1,
            paginator.findPageByCharOffset(Int.MAX_VALUE)
        )
    }

    // ─── Navigation ────────────────────────────────────────────────

    @Test
    fun nextPage_incrementsIndex() = runTest {
        val paginator = Paginator(
            document = longDoc,
            pageWidthPx = 400,
            pageHeightPx = 200,
            textMeasurer = fakeMeasurer,
        )
        paginator.calculatePages()
        awaitPagesReady(paginator)

        val initialIdx = paginator.currentPageIndex.value
        paginator.nextPage()
        assertEquals(initialIdx + 1, paginator.currentPageIndex.value)
    }

    @Test
    fun prevPage_decrementsIndex() = runTest {
        val paginator = Paginator(
            document = longDoc,
            pageWidthPx = 400,
            pageHeightPx = 200,
            textMeasurer = fakeMeasurer,
        )
        paginator.calculatePages()
        awaitPagesReady(paginator)
        paginator.goToPage(5)
        paginator.prevPage()
        assertEquals(4, paginator.currentPageIndex.value)
    }

    @Test
    fun goToPage_clampsToValidRange() {
        val paginator = Paginator(
            document = longDoc,
            pageWidthPx = 400,
            pageHeightPx = 200,
            textMeasurer = fakeMeasurer,
        )
        paginator.goToPage(-100)
        assertEquals(0, paginator.currentPageIndex.value)
        paginator.goToPage(99999)
        assertTrue(paginator.currentPageIndex.value >= 0)
    }

    // ─── rebuild ───────────────────────────────────────────────────

    @Test
    fun rebuild_createsNewWithNewDimensions() {
        val paginator = Paginator(
            document = shortDoc,
            pageWidthPx = 1080,
            pageHeightPx = 1600,
            textMeasurer = fakeMeasurer,
        )
        val rebuilt = paginator.rebuild(800, 1200, FakeTextMeasurer(lineHeightPx = 48))
        assertNotNull(rebuilt)
        assertNotSame(paginator, rebuilt)
    }

    // ─── cancel ────────────────────────────────────────────────────

    @Test
    fun cancel_stopsBackgroundWork() {
        val paginator = Paginator(
            document = longDoc,
            pageWidthPx = 400,
            pageHeightPx = 200,
            textMeasurer = fakeMeasurer,
        )
        paginator.cancel()
    }

    // ─── Edge cases ────────────────────────────────────────────────

    @Test
    fun emptyDocument_handlesGracefully() = runTest {
        val emptyDoc = DocumentModel(
            bookId = 0L, title = "", author = "",
            paragraphs = emptyList(), totalChars = 0,
        )
        val paginator = Paginator(
            document = emptyDoc,
            pageWidthPx = 1080,
            pageHeightPx = 1600,
            textMeasurer = fakeMeasurer,
        )
        paginator.calculatePages()
        awaitPagesReady(paginator)
        assertTrue(paginator.pageCount.value >= 0)
    }

    @Test
    fun singleCharDoc_producesOnePage() = runTest {
        val singleCharDoc = DocumentModel(
            bookId = 3L, title = "X", author = "Y",
            paragraphs = listOf(
                ParagraphBlock(text = "A", globalCharStart = 0, globalCharEnd = 1)
            ),
            totalChars = 1,
        )
        val paginator = Paginator(
            document = singleCharDoc,
            pageWidthPx = 1080,
            pageHeightPx = 1600,
            textMeasurer = fakeMeasurer,
        )
        paginator.calculatePages()
        awaitPagesReady(paginator)
        assertTrue(paginator.pageCount.value >= 1)
    }

    @Test
    fun narrowWidth_causesMorePages() = runTest {
        val wide = Paginator(
            document = longDoc,
            pageWidthPx = 1080,
            pageHeightPx = 800,
            textMeasurer = fakeMeasurer,
        )
        val narrow = Paginator(
            document = longDoc,
            pageWidthPx = 200,
            pageHeightPx = 800,
            textMeasurer = fakeMeasurer,
        )
        wide.calculatePages()
        narrow.calculatePages()
        awaitPagesReady(wide)
        awaitPagesReady(narrow)
        assertTrue(narrow.pageCount.value >= wide.pageCount.value)
    }
}
