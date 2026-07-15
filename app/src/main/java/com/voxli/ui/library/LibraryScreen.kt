package com.voxli.ui.library

import androidx.compose.animation.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voxli.catalog.db.BookEntity
import com.voxli.catalog.db.sanitizeFtsQuery
import com.voxli.ui.library.LibraryViewMode

/**
 * Library main screen with real data, pull-to-refresh, search, genres, sorting.
 *
 * Reference: roadmap §8 (Library), §10 Phase 4 (pull-to-refresh, error handling).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onBookClick: (BookEntity) -> Unit,
    onHistoryClick: () -> Unit,
    onFilterClick: () -> Unit,
) {
    val searchQuery by viewModel.searchQuery.collectAsState()
    val viewMode by viewModel.viewMode.collectAsState()
    val authors by viewModel.authors.collectAsState()
    val books by viewModel.books.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val error by viewModel.error.collectAsState()
    val filterAuthor by viewModel.filterAuthor.collectAsState()
    val selectedGenres by viewModel.selectedGenres.collectAsState()

    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val pullToRefreshState = rememberPullToRefreshState()

    // Auto-hide keyboard on scroll
    val isScrollInProgress by remember { derivedStateOf { listState.isScrollInProgress } }
    LaunchedEffect(isScrollInProgress) {
        if (isScrollInProgress) {
            focusManager.clearFocus()
        }
    }

    // Show error in Snackbar
    LaunchedEffect(error) {
        error?.let {
            snackbarHostState.showSnackbar(it, duration = SnackbarDuration.Short)
            viewModel.clearError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("Voxli") },
                actions = {
                    // Filter button
                    IconButton(onClick = onFilterClick) {
                        BadgedBox(badge = {
                            if (selectedGenres.isNotEmpty()) {
                                Badge { Text("${selectedGenres.size}") }
                            }
                        }) {
                            Icon(Icons.Default.FilterList, contentDescription = "Фильтр")
                        }
                    }
                    // History button
                    IconButton(onClick = onHistoryClick) {
                        Icon(Icons.Default.History, contentDescription = "История")
                    }
                    // View mode toggle
                    TextButton(onClick = { viewModel.toggleViewMode() }) {
                        Text(if (viewMode == LibraryViewMode.AUTHORS) "Авторы ▼" else "Названия ▼")
                    }
                },
            )
        }
    ) { padding ->
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { viewModel.refresh() },
            state = pullToRefreshState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // Search field
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { viewModel.setSearchQuery(it) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .focusRequester(focusRequester),
                    placeholder = {
                        Text(if (viewMode == LibraryViewMode.AUTHORS) "Поиск авторов…" else "Поиск по названию…")
                    },
                    leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                Icon(Icons.Default.Clear, contentDescription = "Очистить")
                            }
                        }
                    },
                    singleLine = true,
                )

                // Content
                AnimatedContent(
                    targetState = viewMode,
                    transitionSpec = {
                        fadeIn() togetherWith fadeOut()
                    },
                    label = "view_mode",
                ) { mode ->
                    when (mode) {
                        LibraryViewMode.AUTHORS -> AuthorList(
                            authors = authors,
                            filterAuthor = null,
                            onAuthorClick = { viewModel.selectAuthor(it) },
                        )
                        LibraryViewMode.TITLES -> BookList(
                            books = books,
                            filterAuthor = filterAuthor,
                            onBookClick = onBookClick,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AuthorList(
    authors: List<String>,
    filterAuthor: String?,
    onAuthorClick: (String) -> Unit,
) {
    if (authors.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "Ничего не найдено",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(authors, key = { it }) { author ->
            Surface(
                onClick = { onAuthorClick(author) },
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.surface,
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 10.dp, horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(
                        Icons.Default.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(
                        text = author,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(
                        Icons.Default.ChevronRight,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                    )
                }
            }
        }
    }
}

@Composable
private fun BookList(
    books: List<BookEntity>,
    filterAuthor: String?,
    onBookClick: (BookEntity) -> Unit,
) {
    if (books.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (filterAuthor != null) {
                    Text(
                        "Нет книг для автора: $filterAuthor",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        "Ничего не найдено",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        return
    }

    // Group by series if applicable
    val groupedBooks = books.groupBy { it.series to it.seriesNum }

    LazyColumn(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Group header for series
        val seriesGroups = books
            .filter { it.series.isNotEmpty() }
            .groupBy { it.series }
            .toList()
            .sortedBy { (_, books) -> books.minOf { it.seriesNum } }

        val standaloneBooks = books.filter { it.series.isEmpty() }

        for ((seriesName, seriesBooks) in seriesGroups) {
            item {
                Text(
                    text = seriesName.uppercase(),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
            }
            items(seriesBooks.sortedBy { it.seriesNum }, key = { it.id }) { book ->
                BookItem(book = book, onClick = { onBookClick(book) }, showSeriesNum = true)
            }
        }

        if (standaloneBooks.isNotEmpty()) {
            items(standaloneBooks, key = { it.id }) { book ->
                BookItem(book = book, onClick = { onBookClick(book) }, showSeriesNum = false)
            }
        }
    }
}

@Composable
private fun BookItem(
    book: BookEntity,
    onClick: () -> Unit,
    showSeriesNum: Boolean,
) {
    Surface(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp, horizontal = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (showSeriesNum) {
                        Text(
                            "${book.seriesNum}. ",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        text = book.title,
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    text = book.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Rating stars
            if (book.rating > 0) {
                Text(
                    text = "★${"%.1f".format(book.rating)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }

            // Audio indicator
            if (book.hasAudio) {
                Spacer(Modifier.width(4.dp))
                Text(
                    text = "🎧",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}
