package com.voxli.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voxli.catalog.db.BookDao
import com.voxli.catalog.db.BookEntity
import com.voxli.catalog.db.HistoryDao
import com.voxli.catalog.db.HistoryWithBook
import com.voxli.catalog.db.buildBookFtsQuery
import com.voxli.catalog.db.sanitizeFtsQuery
import com.voxli.flibusta.provider.FlibustaProvider
import com.voxli.knigavuhe.matcher.KnigavuheMatcher
import com.voxli.knigavuhe.matcher.NarratorInfo
import com.voxli.settings.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

enum class SortField { RATING, TITLE, AUTHOR }

/** View mode: authors or titles list (roadmap §8). */
enum class LibraryViewMode { AUTHORS, TITLES }

/**
 * ViewModel for library screen.
 * Roadmap §8: search, genre filter, sort, progress bar.
 */
class LibraryViewModel(
    private val bookDao: BookDao,
    private val historyDao: HistoryDao,
    private val settingsRepo: SettingsRepository,
    private val flibustaProvider: FlibustaProvider,
    private val knigavuheMatcher: KnigavuheMatcher,
) : ViewModel() {

    // ---- Search ----
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _debouncedQuery = MutableStateFlow("")
    private var searchJob: Job? = null

    // ---- Books ----
    private val _books = MutableStateFlow<List<BookEntity>>(emptyList())
    val books: StateFlow<List<BookEntity>> = _books.asStateFlow()

    private val _authors = MutableStateFlow<List<String>>(emptyList())
    val authors: StateFlow<List<String>> = _authors.asStateFlow()

    // ---- Sort ----
    private val _sortField = MutableStateFlow(SortField.RATING)
    val sortField: StateFlow<SortField> = _sortField.asStateFlow()

    private val _sortCycle = listOf(SortField.RATING, SortField.TITLE, SortField.AUTHOR)

    fun cycleSort() {
        val idx = _sortCycle.indexOf(_sortField.value)
        _sortField.value = _sortCycle[(idx + 1) % _sortCycle.size]
        loadBooks()
    }

    // ---- View mode (AUTHORS / TITLES) ----
    private val _viewMode = MutableStateFlow(LibraryViewMode.AUTHORS)
    val viewMode: StateFlow<LibraryViewMode> = _viewMode.asStateFlow()

    fun toggleViewMode() {
        _viewMode.value = when (_viewMode.value) {
            LibraryViewMode.AUTHORS -> LibraryViewMode.TITLES
            LibraryViewMode.TITLES -> LibraryViewMode.AUTHORS
        }
        loadBooks()
    }

    // ---- Genre filter ----
    private val _allGenres = MutableStateFlow<List<String>>(emptyList())
    val allGenres: StateFlow<List<String>> = _allGenres.asStateFlow()

    private val _selectedGenres = MutableStateFlow<Set<String>>(emptySet())
    val selectedGenres: StateFlow<Set<String>> = _selectedGenres.asStateFlow()

    // ---- History ----
    private val _history = MutableStateFlow<List<HistoryWithBook>>(emptyList())
    val history: StateFlow<List<HistoryWithBook>> = _history.asStateFlow()

    // ---- Loading / Error ----
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadAllGenres()
        loadHistory()
    }

    // ---- Genre loading ----

    private fun loadAllGenres() {
        viewModelScope.launch {
            try {
                val genres = bookDao.getAllGenres()
                _allGenres.value = genres
                val saved = settingsRepo.selectedGenres.first()
                _selectedGenres.value = if (saved.isEmpty()) genres.toSet() else saved
            } catch (e: Exception) {
                _error.value = "Ошибка загрузки жанров: ${e.localizedMessage}"
                _selectedGenres.value = emptySet()
            } finally {
                loadBooks()
            }
        }
    }

    // ---- Search ----

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300)
            _debouncedQuery.value = query
            performSearch()
        }
    }

    private suspend fun performSearch() {
        val query = _debouncedQuery.value
        val genreFilter = _selectedGenres.value
        if (genreFilter.isEmpty()) { _books.value = emptyList(); return }

        if (query.isBlank()) { loadBooks(); return }

        val sanitized = sanitizeFtsQuery(query)
        if (sanitized.isEmpty()) { loadBooks(); return }

        val bookSqlQuery = buildBookFtsQuery(sanitized)
        val ftsResults = bookDao.searchBooksFts(bookSqlQuery)
        _books.value = ftsResults.filter { it.genre in genreFilter }
    }

    // ---- Data loading with sort ----

    private fun loadBooks() {
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val genreFilter = _selectedGenres.value
                val all = if (genreFilter.isEmpty()) {
                    // No genre filter — load all books from DB
                    bookDao.getAllBooks(limit = 10000, offset = 0)
                } else {
                    bookDao.getBooksByGenres(genreFilter)
                }
                _books.value = when (_sortField.value) {
                    SortField.RATING -> all.sortedByDescending { it.rating }
                    SortField.TITLE -> all.sortedBy { it.title.lowercase() }
                    SortField.AUTHOR -> all.sortedBy { it.author.lowercase() }
                }
            } catch (e: Exception) {
                _error.value = "Ошибка загрузки книг: ${e.localizedMessage}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun loadHistory() {
        viewModelScope.launch {
            try { _history.value = historyDao.getAllHistory().take(20) } catch (_: Exception) { }
        }
    }

    // ---- Pull-to-refresh ----

    private fun loadAuthors() {
        viewModelScope.launch {
            try {
                val genreFilter = _selectedGenres.value
                val all = bookDao.getAllBooks(limit = 10000, offset = 0)
                val authorsInGenres = all
                    .filter { it.author.isNotBlank() && (genreFilter.isEmpty() || it.genre in genreFilter) }
                    .map { it.author }
                    .distinct()
                    .sortedBy { it.lowercase() }
                _authors.value = authorsInGenres
            } catch (e: Exception) {
                _error.value = "Ошибка загрузки авторов: ${e.localizedMessage}"
            }
        }
    }

    fun refresh() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        viewModelScope.launch {
            try {
                flibustaProvider.trySwitchMirror()
                loadBooks()
                loadHistory()
                _error.value = null
            } catch (e: Exception) {
                _error.value = "Ошибка обновления: ${e.localizedMessage}"
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    // ---- Narrators for book card ----

    /** Load narrators for the expanded book card. */
    suspend fun loadNarrators(bookId: Long): List<NarratorInfo> {
        val book = bookDao.getBookById(bookId) ?: return emptyList()
        if (book.title.isBlank() || book.author.isBlank()) return emptyList()
        return knigavuheMatcher.fetchNarrators(book.id, book.title, book.author)
    }

    // ---- Genre filter ----

    fun toggleGenre(genre: String) {
        val current = _selectedGenres.value.toMutableSet()
        if (genre in current) current.remove(genre) else current.add(genre)
        _selectedGenres.value = current
        viewModelScope.launch { settingsRepo.setSelectedGenres(current) }
        loadBooks()
    }

    fun selectAllGenres() {
        val all = _allGenres.value.toSet()
        _selectedGenres.value = all
        viewModelScope.launch { settingsRepo.setSelectedGenres(all) }
        loadBooks()
    }

    fun deselectAllGenres() {
        _selectedGenres.value = emptySet()
        viewModelScope.launch { settingsRepo.setSelectedGenres(emptySet()) }
        loadBooks()
    }

    fun clearError() { _error.value = null }
}
