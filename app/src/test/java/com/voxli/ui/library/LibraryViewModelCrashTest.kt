package com.voxli.ui.library

import com.voxli.catalog.db.BookDao
import com.voxli.catalog.db.BookEntity
import com.voxli.catalog.db.HistoryDao
import com.voxli.catalog.db.HistoryWithBook
import com.voxli.flibusta.provider.FlibustaProvider
import com.voxli.settings.SettingsRepository
import io.mockk.*
import io.mockk.impl.annotations.MockK
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * Crash regression tests for LibraryViewModel.
 *
 * These tests simulate scenarios that would cause:
 * - Silent failures (empty list with no error shown)
 * - IllegalArgumentException from Room (empty genre list -> IN ())
 * - Infinite loading (exception swallowed, isLoading never reset)
 */
@ExperimentalCoroutinesApi
class LibraryViewModelCrashTest {

    private lateinit var viewModel: LibraryViewModel

    @MockK private lateinit var bookDao: BookDao
    @MockK private lateinit var historyDao: HistoryDao
    @MockK private lateinit var settingsRepo: SettingsRepository
    @MockK private lateinit var flibustaProvider: FlibustaProvider

    private val testDispatcher = UnconfinedTestDispatcher()

    @Before
    fun setUp() {
        MockKAnnotations.init(this, relaxed = true)
        Dispatchers.setMain(testDispatcher)

        // No default mocks for DAO — each test sets its own
        coEvery { historyDao.getAllHistory() } returns emptyList()

        every { settingsRepo.bgColor } returns MutableStateFlow(0xFFFFFFFF.toInt())
        every { settingsRepo.textColor } returns MutableStateFlow(0xFF000000.toInt())
        every { settingsRepo.fontSize } returns MutableStateFlow(16f)
        every { settingsRepo.selectedGenres } returns MutableStateFlow(emptySet())
        every { settingsRepo.sortFieldAuthors } returns MutableStateFlow("popularity")
        every { settingsRepo.sortFieldTitles } returns MutableStateFlow("popularity")
        every { settingsRepo.activeMirror } returns MutableStateFlow("http://flibusta.is")
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    // ═══════════════════════════════════════════════════════════════
    // TEST 1: Empty genre list → Room would throw IllegalArgumentException
    //   for `WHERE genre IN ()`. The fix must avoid calling
    //   getBooksByGenres with empty set — fall back to getAllBooks.
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `empty genre filter should not call getBooksByGenres`() = runTest(testDispatcher) {
        coEvery { bookDao.getAllGenres() } returns emptyList()
        coEvery { bookDao.getAllBooks(any(), any()) } returns listOf(
            BookEntity(id = 1, title = "Book A", author = "Author 1", genre = "detective"),
        )

        viewModel = LibraryViewModel(
            bookDao = bookDao,
            historyDao = historyDao,
            settingsRepo = settingsRepo,
            flibustaProvider = flibustaProvider,
        )

        // Must NOT throw IllegalArgumentException from getBooksByGenres
        coVerify(exactly = 1) { bookDao.getAllBooks(any(), any()) }
        coVerify(exactly = 0) { bookDao.getBooksByGenres(any()) }
    }

    // ═══════════════════════════════════════════════════════════════
    // TEST 2: loadAllGenres throws → loadBooks should still be called
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `loadAllGenres exception should not prevent loadBooks`() = runTest(testDispatcher) {
        coEvery { bookDao.getAllGenres() } throws RuntimeException("DB failure")
        coEvery { bookDao.getAllBooks(any(), any()) } returns emptyList()

        viewModel = LibraryViewModel(
            bookDao = bookDao,
            historyDao = historyDao,
            settingsRepo = settingsRepo,
            flibustaProvider = flibustaProvider,
        )

        // loadBooks should have been called (finally block)
        assertFalse("isLoading must be false after exception recovery",
            viewModel.isLoading.value)
    }

    // ═══════════════════════════════════════════════════════════════
    // TEST 3: getAllBooks throws → must not crash ViewModel
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `getAllBooks exception should not crash`() = runTest(testDispatcher) {
        coEvery { bookDao.getAllGenres() } returns emptyList()
        coEvery { bookDao.getAllBooks(any(), any()) } throws RuntimeException("DB error")

        viewModel = LibraryViewModel(
            bookDao = bookDao,
            historyDao = historyDao,
            settingsRepo = settingsRepo,
            flibustaProvider = flibustaProvider,
        )

        assertFalse("isLoading must be false", viewModel.isLoading.value)
    }

    // ═══════════════════════════════════════════════════════════════
    // TEST 4: Selected genres with missing books → empty list, not crash
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `getBooksByGenres returns empty list should show empty not crash`() = runTest(testDispatcher) {
        coEvery { bookDao.getAllGenres() } returns listOf("detective", "sci-fi", "novel")
        coEvery { bookDao.getBooksByGenres(any()) } returns emptyList()

        viewModel = LibraryViewModel(
            bookDao = bookDao,
            historyDao = historyDao,
            settingsRepo = settingsRepo,
            flibustaProvider = flibustaProvider,
        )

        assertFalse("isLoading must be false", viewModel.isLoading.value)
        assertTrue("books must be empty not crash", viewModel.books.value.isEmpty())
    }

    // ═══════════════════════════════════════════════════════════════
    // TEST 5: Refresh calls loadBooks on error recovery
    // ═══════════════════════════════════════════════════════════════

    @Test
    fun `refresh after DB failure recovers`() = runTest(testDispatcher) {
        coEvery { bookDao.getAllGenres() } throws RuntimeException("DB failure")
        coEvery { bookDao.getAllBooks(any(), any()) } returns emptyList()

        viewModel = LibraryViewModel(
            bookDao = bookDao,
            historyDao = historyDao,
            settingsRepo = settingsRepo,
            flibustaProvider = flibustaProvider,
        )

        // Now DB recovers
        clearMocks(bookDao)
        coEvery { bookDao.getAllGenres() } returns listOf("detective")
        coEvery { bookDao.getAllBooks(any(), any()) } returns listOf(
            BookEntity(id = 3, title = "New Book", author = "Author", genre = "detective"),
        )
        coEvery { flibustaProvider.trySwitchMirror() } returns true

        viewModel.refresh()

        assertFalse("isLoading must be false after refresh",
            viewModel.isLoading.value)
    }
}
