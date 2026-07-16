package com.voxli.ui.library

import com.voxli.catalog.db.BookDao
import com.voxli.catalog.db.BookEntity
import com.voxli.catalog.db.HistoryDao
import com.voxli.flibusta.provider.FlibustaProvider
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
 * Unit tests for LibraryViewModel.
 *
 * Reference: roadmap §8 (Library), §4.2 (FTS5).
 */
@ExperimentalCoroutinesApi
class LibraryViewModelTest {

    private lateinit var viewModel: LibraryViewModel
    @MockK private lateinit var bookDao: BookDao
    @MockK private lateinit var historyDao: HistoryDao
    @MockK private lateinit var settingsRepo: SettingsRepository
    @MockK private lateinit var flibustaProvider: FlibustaProvider

    private val testDispatcher = StandardTestDispatcher()

    private val sampleBooks = listOf(
        BookEntity(id = 1, title = "Война и мир", author = "Толстой Лев", genre = "prose", rating = 4.5),
        BookEntity(id = 2, title = "Преступление и наказание", author = "Достоевский Фёдор", genre = "prose", rating = 4.8),
        BookEntity(id = 3, title = "1984", author = "Оруэлл Джордж", genre = "sci-fi", rating = 4.6),
        BookEntity(id = 4, title = "Мастер и Маргарита", author = "Булгаков Михаил", genre = "prose", rating = 4.7),
        BookEntity(id = 5, title = "Дюна", author = "Герберт Фрэнк", genre = "sci-fi", rating = 4.4),
    )

    @Before
    fun setUp() {
        MockKAnnotations.init(this, relaxed = true)

        every { settingsRepo.selectedGenres } returns MutableStateFlow(emptySet())
        every { settingsRepo.fontSize } returns MutableStateFlow(16f)
        every { settingsRepo.bgColor } returns MutableStateFlow(0xFFFFFFFF.toInt())
        every { settingsRepo.textColor } returns MutableStateFlow(0xFF000000.toInt())

        coEvery { bookDao.getBooksByGenres(any()) } returns sampleBooks
        coEvery { bookDao.getAllGenres() } returns listOf("prose", "sci-fi")
        coEvery { historyDao.getAllHistory() } returns emptyList()
        coEvery { flibustaProvider.trySwitchMirror() } returns true

        Dispatchers.setMain(testDispatcher)

        viewModel = LibraryViewModel(
            bookDao = bookDao,
            historyDao = historyDao,
            settingsRepo = settingsRepo,
            flibustaProvider = flibustaProvider,
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
        clearAllMocks()
    }

    @Test
    fun initialState_isCorrect() = runTest(testDispatcher) {
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("", viewModel.searchQuery.value)
        assertTrue(viewModel.allGenres.value.contains("prose"))
        assertTrue(viewModel.allGenres.value.contains("sci-fi"))
        // Books should be loaded after genres
        assertTrue(viewModel.books.value.isNotEmpty())
    }

    @Test
    fun setSearchQuery_setsQuery() = runTest(testDispatcher) {
        viewModel.setSearchQuery("война")
        testDispatcher.scheduler.advanceTimeBy(350)
        testDispatcher.scheduler.advanceUntilIdle()
        assertEquals("война", viewModel.searchQuery.value)
    }

    @Test
    fun clearSearch_reloadsBooks() = runTest(testDispatcher) {
        viewModel.setSearchQuery("")
        testDispatcher.scheduler.advanceUntilIdle()
        // After clearing search, books should be reloaded
        coVerify(atLeast = 1) { bookDao.getBooksByGenres(any()) }
    }

    @Test
    fun toggleGenre_addsAndRemoves() {
        viewModel.toggleGenre("prose")
        assertTrue(viewModel.selectedGenres.value.contains("prose"))
        viewModel.toggleGenre("prose")
        assertFalse(viewModel.selectedGenres.value.contains("prose"))
    }

    @Test
    fun emptyGenreFilter_returnsEmptyBooks() = runTest(testDispatcher) {
        // Deselect all genres by toggling each one off
        viewModel.allGenres.value.forEach { genre ->
            viewModel.toggleGenre(genre)
        }
        testDispatcher.scheduler.advanceUntilIdle()
        assertTrue(viewModel.books.value.isEmpty())
    }

    @Test
    fun genreFilter_showsOnlyMatching() = runTest(testDispatcher) {
        // Override mock to filter by genres
        coEvery { bookDao.getBooksByGenres(any()) } answers {
            val genres = arg<Collection<String>>(0)
            sampleBooks.filter { it.genre in genres }
        }

        // Remove one genre from selection, triggering reload with filter
        viewModel.toggleGenre("sci-fi")
        testDispatcher.scheduler.advanceUntilIdle()

        // Only "prose" should now be selected
        assertEquals(setOf("prose"), viewModel.selectedGenres.value)

        // Books should only contain "prose" books
        assertTrue(viewModel.books.value.isNotEmpty())
        viewModel.books.value.forEach { book ->
            assertTrue("Book ${book.title} has genre ${book.genre} not in selected",
                book.genre in viewModel.selectedGenres.value)
        }
    }

    @Test
    fun refresh_reloadsData() = runTest(testDispatcher) {
        viewModel.refresh()
        testDispatcher.scheduler.advanceUntilIdle()
        coVerify(exactly = 1) { flibustaProvider.trySwitchMirror() }
    }

    @Test
    fun clearError_resetsError() {
        viewModel.clearError()
        assertNull(viewModel.error.value)
    }

    // ── Regression tests ─────────────────────────────────────────

    @Test
    fun regression_genresNotEmpty_booksPopulated() = runTest(testDispatcher) {
        // This test guards against the regression where the seed DB has 0 genres,
        // causing the library to always show empty.
        testDispatcher.scheduler.advanceUntilIdle()

        assertTrue("REGRESSION: genres must not be empty — library filtering will show nothing",
            viewModel.allGenres.value.isNotEmpty(),
        )
        assertTrue("REGRESSION: books should be populated when genres exist",
            viewModel.books.value.isNotEmpty(),
        )
    }

    @Test
    fun regression_booksFilteredByGenre() = runTest(testDispatcher) {
        // Verify books are correctly filtered by selected genres
        coEvery { bookDao.getBooksByGenres(any()) } answers {
            val genres = arg<Collection<String>>(0)
            sampleBooks.filter { it.genre in genres }
        }

        // Remove one genre so only "prose" remains
        viewModel.toggleGenre("sci-fi")
        testDispatcher.scheduler.advanceUntilIdle()

        val result = viewModel.books.value
        assertEquals("Only prose books should be returned",
            listOf("prose"), result.map { it.genre }.distinct())
        result.forEach { book ->
            assertTrue(
                "REGRESSION: all returned books should match selected genre",
                book.genre in viewModel.selectedGenres.value,
            )
        }
    }

    @Test
    fun regression_ftsQuery_doesNotCrash() = runTest(testDispatcher) {
        // FTS5 MATCH with special characters must not crash
        coEvery { bookDao.searchBooksFts(any()) } returns sampleBooks

        viewModel.setSearchQuery("война и мир 1869")
        testDispatcher.scheduler.advanceTimeBy(350)
        testDispatcher.scheduler.advanceUntilIdle()
        // No exception should be thrown
    }
}
