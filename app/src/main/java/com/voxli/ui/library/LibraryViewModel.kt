package com.voxli.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.voxli.catalog.db.BookDao
import com.voxli.catalog.db.BookEntity
import com.voxli.catalog.db.HistoryDao
import com.voxli.catalog.db.HistoryWithBook
import com.voxli.catalog.db.buildAuthorFtsQuery
import com.voxli.catalog.db.buildBookFtsQuery
import com.voxli.catalog.db.sanitizeFtsQuery
import com.voxli.flibusta.provider.FlibustaProvider
import com.voxli.settings.SettingsRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * ViewModel for the library screen.
 * Handles search (FTS5), authors/titles modes, genres, sorting, pull-to-refresh.
 *
 * Reference: roadmap §8 (Library), §4.2 (FTS5), §10 Phase 4.
 */
class LibraryViewModel(
    private val bookDao: BookDao,
    private val historyDao: HistoryDao,
    private val settingsRepo: SettingsRepository,
    private val flibustaProvider: FlibustaProvider,
) : ViewModel() {

    // ---- Search ----
    private val _searchQuery = MutableStateFlow("")
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _debouncedQuery = MutableStateFlow("")
    private var searchJob: Job? = null

    // ---- View mode ----
    private val _viewMode = MutableStateFlow(LibraryViewMode.AUTHORS)
    val viewMode: StateFlow<LibraryViewMode> = _viewMode.asStateFlow()

    // ---- Authors ----
    private val _authors = MutableStateFlow<List<String>>(emptyList())
    val authors: StateFlow<List<String>> = _authors.asStateFlow()

    // ---- Books (for Titles mode and author drill-down) ----
    private val _books = MutableStateFlow<List<BookEntity>>(emptyList())
    val books: StateFlow<List<BookEntity>> = _books.asStateFlow()

    // ---- Genre filter ----
    private val _allGenres = MutableStateFlow<List<String>>(emptyList())
    val allGenres: StateFlow<List<String>> = _allGenres.asStateFlow()

    private val _selectedGenres = MutableStateFlow<Set<String>>(emptySet())
    val selectedGenres: StateFlow<Set<String>> = _selectedGenres.asStateFlow()

    // ---- Sorting ----
    private val _sortFieldAuthors = MutableStateFlow("popularity")
    val sortFieldAuthors: StateFlow<String> = _sortFieldAuthors.asStateFlow()

    private val _sortFieldTitles = MutableStateFlow("popularity")
    val sortFieldTitles: StateFlow<String> = _sortFieldTitles.asStateFlow()

    // ---- History ----
    private val _history = MutableStateFlow<List<HistoryWithBook>>(emptyList())
    val history: StateFlow<List<HistoryWithBook>> = _history.asStateFlow()

    // ---- Loading / Error ----
    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // ---- Filtered author (when tapping an author in Authors mode) ----
    private val _filterAuthor = MutableStateFlow<String?>(null)
    val filterAuthor: StateFlow<String?> = _filterAuthor.asStateFlow()

    init {
        viewModelScope.launch {
            settingsRepo.sortFieldAuthors.collect { _sortFieldAuthors.value = it }
        }
        viewModelScope.launch {
            settingsRepo.sortFieldTitles.collect { _sortFieldTitles.value = it }
        }
        // Load all available genres from DB
        loadAllGenres()
        // Initial data load
        loadAuthors()
        loadHistory()
    }

    // ---- Genre loading ----

    private fun loadAllGenres() {
        viewModelScope.launch {
            try {
                val genres = bookDao.getAllGenres()
                _allGenres.value = genres
                // Restore saved selection; if empty = default all
                val saved = settingsRepo.selectedGenres.first()
                _selectedGenres.value = if (saved.isEmpty()) genres.toSet() else saved
            } catch (_: Exception) { }
        }
    }

    // ---- Search ----

    fun setSearchQuery(query: String) {
        _searchQuery.value = query
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            delay(300)  // debounce
            _debouncedQuery.value = query
            performSearch()
        }
    }

    private suspend fun performSearch() {
        val query = _debouncedQuery.value
        if (query.isBlank()) {
            loadAuthors()
            loadBooks()
            return
        }

        val sanitized = sanitizeFtsQuery(query)

        when (_viewMode.value) {
            LibraryViewMode.AUTHORS -> {
                val sqlQuery = buildAuthorFtsQuery(sanitized)
                val results = bookDao.searchAuthorsFts(sqlQuery)
                _authors.value = results
            }
            LibraryViewMode.TITLES -> {
                val sqlQuery = buildBookFtsQuery(sanitized)
                val results = bookDao.searchBooksFts(sqlQuery)
                _books.value = results
            }
        }
    }

    // ---- View mode toggle ----

    fun toggleViewMode() {
        val newMode = when (_viewMode.value) {
            LibraryViewMode.AUTHORS -> LibraryViewMode.TITLES
            LibraryViewMode.TITLES -> LibraryViewMode.AUTHORS
            else -> LibraryViewMode.AUTHORS
        }
        _viewMode.value = newMode
        _filterAuthor.value = null
        viewModelScope.launch { performSearch() }
    }

    /** Navigate to author-specific book list. */
    fun selectAuthor(author: String) {
        _viewMode.value = LibraryViewMode.TITLES
        _filterAuthor.value = author
        viewModelScope.launch {
            val authorBooks = bookDao.getBooksByAuthor(author)
            _books.value = authorBooks
        }
    }

    // ---- Data loading ----

    private fun loadAuthors() {
        viewModelScope.launch {
            try {
                val genreFilter = _selectedGenres.value
                if (genreFilter.isEmpty()) {
                    _authors.value = emptyList()
                    return@launch
                }
                val allBooks = bookDao.getAllBooks()
                val authorsInGenres = allBooks
                    .filter { it.genre in genreFilter && it.author.isNotBlank() }
                    .map { it.author }
                    .distinct()
                    .sortedBy { it.lowercase() }
                _authors.value = authorsInGenres
            } catch (e: Exception) {
                _error.value = "Ошибка загрузки авторов: ${e.localizedMessage}"
            }
        }
    }

    private fun loadBooks() {
        viewModelScope.launch {
            try {
                val genreFilter = _selectedGenres.value
                if (genreFilter.isEmpty()) {
                    _books.value = emptyList()
                    return@launch
                }
                val allBooks = bookDao.getAllBooks()
                _books.value = allBooks.filter { it.genre in genreFilter }
            } catch (e: Exception) {
                _error.value = "Ошибка загрузки книг: ${e.localizedMessage}"
            }
        }
    }

    private fun loadHistory() {
        viewModelScope.launch {
            try {
                _history.value = historyDao.getAllHistory().take(20)
            } catch (_: Exception) { }
        }
    }

    // ---- Pull-to-refresh ----

    fun refresh() {
        if (_isRefreshing.value) return
        _isRefreshing.value = true
        viewModelScope.launch {
            try {
                flibustaProvider.trySwitchMirror()
                loadAuthors()
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

    // ---- Genre filter ----

    fun toggleGenre(genre: String) {
        val current = _selectedGenres.value.toMutableSet()
        if (genre in current) current.remove(genre) else current.add(genre)
        _selectedGenres.value = current
        viewModelScope.launch { settingsRepo.setSelectedGenres(current) }
        loadAuthors()
        loadBooks()
    }

    // ---- Error ----

    fun clearError() {
        _error.value = null
    }
}
