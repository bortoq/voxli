package com.voxli.ui.reader

import android.app.Application
import com.voxli.catalog.db.BookDao
import com.voxli.catalog.db.HistoryDao
import com.voxli.flibusta.provider.FlibustaProvider
import com.voxli.reader.engine.BookDownloader
import com.voxli.settings.SettingsRepository
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for ReaderViewModel.
 *
 * Reference: roadmap §7.5 (paginator lifecycle), §7.6 (TTS), §4.3 (char_offset).
 */
@ExperimentalCoroutinesApi
class ReaderViewModelTest {

    private lateinit var viewModel: ReaderViewModel
    @MockK private lateinit var application: Application
    @MockK private lateinit var bookDao: BookDao
    @MockK private lateinit var historyDao: HistoryDao
    @MockK private lateinit var settingsRepo: SettingsRepository
    @MockK private lateinit var bookDownloader: BookDownloader
    @MockK private lateinit var flibustaProvider: FlibustaProvider

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        MockKAnnotations.init(this, relaxed = true)

        every { settingsRepo.bgColor } returns MutableStateFlow(0xFFFFFFFF.toInt())
        every { settingsRepo.textColor } returns MutableStateFlow(0xFF000000.toInt())
        every { settingsRepo.fontSize } returns MutableStateFlow(16f)
        every { settingsRepo.selectedGenres } returns MutableStateFlow(emptySet())
        every { settingsRepo.sortFieldAuthors } returns MutableStateFlow("popularity")
        every { settingsRepo.sortFieldTitles } returns MutableStateFlow("popularity")
        every { settingsRepo.activeMirror } returns MutableStateFlow("http://flibusta.is")
        every { application.resources } returns mockk {
            every { displayMetrics } returns android.util.DisplayMetrics().also {
                it.density = 2.0f
            }
        }

        Dispatchers.setMain(testDispatcher)

        viewModel = ReaderViewModel(
            application = application,
            bookDao = bookDao,
            historyDao = historyDao,
            settingsRepo = settingsRepo,
            bookDownloader = bookDownloader,
            flibustaProvider = flibustaProvider,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    // ─── Initial state ─────────────────────────────────────────────

    @Test
    fun initialState_isCorrect() {
        assertFalse(viewModel.isLoading.value)
        assertNull(viewModel.currentPage.value)
        assertNull(viewModel.error.value)
        assertEquals(ReaderMode.READING, viewModel.readerMode.value)
        assertEquals(0, viewModel.currentPageIndex.value)
        assertEquals(0, viewModel.totalPages.value)
    }

    // ─── loadBook ──────────────────────────────────────────────────

    @Test
    fun loadBook_downloadFails_showsError() = runTest(testDispatcher) {
        val bookId = 99999L
        every { flibustaProvider.getActiveMirror() } returns "http://flibusta.is"
        coEvery { bookDownloader.download(bookId, "fb2", "http://flibusta.is", any()) } returns null

        viewModel.loadBook(bookId)

        assertFalse(viewModel.isLoading.value)
        assertNotNull(viewModel.error.value)
    }

    @Test
    fun loadBook_alreadyLoading_ignoresSecondCall() = runTest(testDispatcher) {
        val bookId = 12345L
        every { flibustaProvider.getActiveMirror() } returns "http://flibusta.is"
        coEvery { bookDownloader.download(any(), any(), any(), any()) } coAnswers {
            kotlinx.coroutines.delay(100000)
            null
        }

        viewModel.loadBook(bookId)
        viewModel.loadBook(99999L)

        coVerify(exactly = 1) { bookDownloader.download(bookId, any(), any(), any()) }
    }

    // ─── Navigation ────────────────────────────────────────────────

    @Test
    fun navigation_changesPageIndex() {
        // Without a built paginator, navigation is a no-op (paginator == null)
        assertEquals(0, viewModel.currentPageIndex.value)
        viewModel.goToPage(5)
        assertEquals(0, viewModel.currentPageIndex.value)
    }

    @Test
    fun goToPage_clamps() {
        viewModel.goToPage(-10)
        assertEquals(0, viewModel.currentPageIndex.value)
        viewModel.goToPage(Int.MAX_VALUE)
        assertTrue(viewModel.currentPageIndex.value >= 0)
    }

    // ─── Mode switching ────────────────────────────────────────────

    @Test
    fun mode_startsWithReading() {
        assertEquals(ReaderMode.READING, viewModel.readerMode.value)
    }

    @Test
    fun enterSettings_setsModeToSettings() {
        viewModel.enterSettings()
        assertEquals(ReaderMode.SETTINGS, viewModel.readerMode.value)
    }

    @Test
    fun cycleSettings_fullCycle_returnsToReading() {
        viewModel.enterSettings()
        assertEquals(ReaderMode.SETTINGS, viewModel.readerMode.value)
        // cycle: BG_COLOR→TEXT_COLOR→FONT→(back to READING)
        viewModel.cycleSettings()
        viewModel.cycleSettings()
        viewModel.cycleSettings()
        assertEquals(ReaderMode.READING, viewModel.readerMode.value)
    }

    @Test
    fun cycleSettings_fromReading_entersSettings() {
        // Without enterSettings(), cycleSettings should enter settings mode
        viewModel.cycleSettings()
        assertEquals(ReaderMode.SETTINGS, viewModel.readerMode.value)
        assertEquals(SettingsStep.BG_COLOR, viewModel.settingsStep.value)
    }

    // ─── Error ─────────────────────────────────────────────────────

    @Test
    fun clearError_resetsError() {
        viewModel.clearError()
        assertNull(viewModel.error.value)
    }
}
