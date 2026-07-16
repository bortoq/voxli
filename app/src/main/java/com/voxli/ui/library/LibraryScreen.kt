package com.voxli.ui.library

import androidx.compose.animation.*
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voxli.catalog.db.BookEntity
import com.voxli.catalog.db.HistoryWithBook
import com.voxli.catalog.db.sanitizeFtsQuery
import com.voxli.reader.engine.NarratorInfo

/**
 * Library main screen.
 * Roadmap §8: search + book list (grouped by series) + progress bar.
 */

/** Progress bar step: 0=narrow, 1=sort, 2=bg, 3=text, 4=font */
private enum class BarStep { NARROW, SORT, BG, TEXT, FONT }
private fun BarStep.next(): BarStep = when (this) {
    BarStep.NARROW -> BarStep.SORT
    BarStep.SORT -> BarStep.BG
    BarStep.BG -> BarStep.TEXT
    BarStep.TEXT -> BarStep.FONT
    BarStep.FONT -> BarStep.NARROW
}
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    viewModel: LibraryViewModel,
    onBookClick: (BookEntity) -> Unit,
    onHistoryClick: () -> Unit,
    onFilterClick: () -> Unit,
    onReadBook: (Long) -> Unit,
    onPlayBook: (Long, Long) -> Unit,
) {
    val searchQuery by viewModel.searchQuery.collectAsState()
    val books by viewModel.books.collectAsState()
    val isRefreshing by viewModel.isRefreshing.collectAsState()
    val error by viewModel.error.collectAsState()
    val selectedGenres by viewModel.selectedGenres.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val sortField by viewModel.sortField.collectAsState()

    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val pullToRefreshState = rememberPullToRefreshState()

    // Track which book card is expanded (null = none)
    var expandedBookId by remember { mutableStateOf<Long?>(null) }

    // Narrator cache for expanded card
    var narrators by remember { mutableStateOf<List<NarratorInfo>>(emptyList()) }
    var narratorsLoading by remember { mutableStateOf(false) }

    // Load narrators when card expands
    LaunchedEffect(expandedBookId) {
        val bookId = expandedBookId ?: return@LaunchedEffect
        narratorsLoading = true
        narrators = viewModel.loadNarrators(bookId)
        narratorsLoading = false
    }

    // Progress bar state
    var barStep by remember { mutableStateOf(BarStep.NARROW) }

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

    // Calculate scroll progress
    val scrollProgress by remember {
        derivedStateOf {
            val layoutInfo = listState.layoutInfo
            val total = layoutInfo.totalItemsCount
            if (total <= 1) Pair(0, 0)
            else {
                val firstVisible = layoutInfo.visibleItemsInfo.firstOrNull()
                if (firstVisible != null) Pair(firstVisible.index + 1, total)
                else Pair(0, total)
            }
        }
    }
    val isNarrow = barStep == BarStep.NARROW

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
                // Top row: search + genre count + history
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 12.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedTextField(
                        value = searchQuery,
                        onValueChange = { viewModel.setSearchQuery(it) },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(focusRequester),
                        placeholder = { Text("Поиск...") },
                        leadingIcon = {
                            Icon(Icons.Default.Search, contentDescription = null, modifier = Modifier.size(20.dp))
                        },
                        trailingIcon = {
                            if (searchQuery.isNotEmpty()) {
                                IconButton(onClick = { viewModel.setSearchQuery("") }) {
                                    Icon(Icons.Default.Clear, contentDescription = "Очистить", modifier = Modifier.size(20.dp))
                                }
                            }
                        },
                        singleLine = true,
                    )

                    Surface(
                        onClick = onFilterClick,
                        shape = MaterialTheme.shapes.small,
                        color = MaterialTheme.colorScheme.secondaryContainer,
                        modifier = Modifier.padding(start = 8.dp),
                    ) {
                        Text(
                            text = "${selectedGenres.size}",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }

                    IconButton(onClick = onHistoryClick) {
                        Icon(Icons.Default.History, contentDescription = "История")
                    }
                }

                // Loading indicator
                if (isLoading) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                    )
                }

                // Book list
                BookList(
                    books = books,
                    viewModel = viewModel,
                    listState = listState,
                    expandedBookId = expandedBookId,
                    onToggleExpand = { id ->
                        expandedBookId = if (expandedBookId == id) null else id
                    },
                    narrators = narrators,
                    narratorsLoading = narratorsLoading,
                    onReadBook = onReadBook,
                    onPlayBook = onPlayBook,
                )

                // Bottom progress bar
                ProgressBar(
                    step = barStep,
                    scrollPos = scrollProgress.first,
                    scrollTotal = scrollProgress.second,
                    isNarrow = isNarrow,
                    sortLabel = when (sortField) {
                        SortField.RATING -> "по рейтингу"
                        SortField.TITLE -> "по названию"
                        SortField.AUTHOR -> "по автору"
                    },
                    onTap = {
                        if (isNarrow) {
                            barStep = BarStep.SORT
                        } else if (barStep == BarStep.FONT) {
                            barStep = BarStep.NARROW
                        } else {
                            if (barStep == BarStep.SORT) viewModel.cycleSort()
                            barStep = barStep.next()
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ProgressBar(
    step: BarStep,
    scrollPos: Int,
    scrollTotal: Int,
    isNarrow: Boolean,
    sortLabel: String,
    onTap: () -> Unit,
) {
    val barHeight = if (isNarrow) 8.dp else 24.dp
    val label = when (step) {
        BarStep.NARROW -> "[≡≡] $scrollPos / $scrollTotal"
        BarStep.SORT -> "[≡≡] Сорт: $sortLabel"
        BarStep.BG -> "[≡≡] Фон: цвет ярк"
        BarStep.TEXT -> "[≡≡] Текст: цв ярк"
        BarStep.FONT -> "[≡≡] Шрифт: гарн разм"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(barHeight)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable { onTap() },
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onPrimaryContainer,
            modifier = Modifier.padding(start = 8.dp),
        )
    }
}

@Composable
private fun BookList(
    books: List<BookEntity>,
    viewModel: LibraryViewModel,
    listState: androidx.compose.foundation.lazy.LazyListState,
    expandedBookId: Long?,
    onToggleExpand: (Long) -> Unit,
    narrators: List<NarratorInfo>,
    narratorsLoading: Boolean,
    onReadBook: (Long) -> Unit,
    onPlayBook: (Long, Long) -> Unit,
) {
    val historyList by viewModel.history.collectAsStateWithLifecycle()
    val progressMap: Map<Long, Double> = remember(historyList) {
        historyList.associate { it.bookId to it.progress }
    }

    if (books.isEmpty()) {
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
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Single pass: group by series in one iteration
        val grouped = books.groupBy { it.series.ifEmpty { null } }
        val seriesEntries = grouped.filterKeys { it != null }
            .entries
            .sortedBy { (_, bks) -> bks.minOf { it.seriesNum } }
        val standaloneEntries = grouped[null] ?: emptyList()

        for ((seriesName, seriesBooks) in seriesEntries) {
            item {
                Text(
                    text = seriesName!!.uppercase(),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
                )
            }
            items(seriesBooks.sortedBy { it.seriesNum }, key = { it.id }) { book ->
                BookItem(
                    book = book,
                    showSeriesNum = true,
                    progress = progressMap[book.id] ?: 0.0,
                    isExpanded = expandedBookId == book.id,
                    onToggle = { onToggleExpand(book.id) },
                    narrators = if (expandedBookId == book.id) narrators else emptyList(),
                    narratorsLoading = narratorsLoading && expandedBookId == book.id,
                    onRead = { onReadBook(book.id) },
                    onPlay = { onPlayBook(book.id, it) },
                )
            }
        }

        if (standaloneEntries.isNotEmpty()) {
            items(standaloneEntries, key = { it.id }) { book ->
                BookItem(
                    book = book,
                    showSeriesNum = false,
                    progress = progressMap[book.id] ?: 0.0,
                    isExpanded = expandedBookId == book.id,
                    onToggle = { onToggleExpand(book.id) },
                    narrators = if (expandedBookId == book.id) narrators else emptyList(),
                    narratorsLoading = narratorsLoading && expandedBookId == book.id,
                    onRead = { onReadBook(book.id) },
                    onPlay = { onPlayBook(book.id, it) },
                )
            }
        }
    }
}

@Composable
private fun BookItem(
    book: BookEntity,
    showSeriesNum: Boolean,
    progress: Double = 0.0,
    isExpanded: Boolean = false,
    onToggle: () -> Unit,
    narrators: List<NarratorInfo>,
    narratorsLoading: Boolean,
    onRead: (Long) -> Unit,
    onPlay: (Long, Long) -> Unit,
) {
    Surface(
        onClick = onToggle,
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 8.dp, bottom = 4.dp, start = 4.dp, end = 4.dp),
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

                if (book.rating > 0) {
                    Text(
                        text = "★${"%.1f".format(book.rating)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }

                if (book.hasAudio) {
                    Spacer(Modifier.width(4.dp))
                    Text(
                        text = "🎧",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }

            if (progress > 0.0 && progress < 1.0) {
                LinearProgressIndicator(
                    progress = { progress.toFloat() },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).height(3.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                )
            }

            // Expanded card content
            AnimatedVisibility(visible = isExpanded) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp, horizontal = 4.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        // Annotation
                        if (book.annotation.isNotBlank()) {
                            Text(
                                text = book.annotation,
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 5,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(8.dp))
                        }

                        // Narrators section
                        Text(
                            text = "Чтецы:",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(4.dp))

                        if (narratorsLoading) {
                            Text(
                                text = "Загрузка...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else if (narrators.isNotEmpty()) {
                            narrators.forEach { narrator ->
                                TextButton(
                                    onClick = { onPlay(book.id, narrator.readerId) },
                                    modifier = Modifier.padding(start = 8.dp),
                                ) {
                                    Text(
                                        text = "${narrator.name} (${formatDuration(narrator.durationSeconds)})",
                                        style = MaterialTheme.typography.bodySmall,
                                    )
                                }
                            }
                        } else {
                            Text(
                                text = "TTS-озвучка",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 8.dp),
                            )
                        }

                        Spacer(Modifier.height(8.dp))

                        // Read button
                        Button(
                            onClick = { onRead(book.id) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text("Читать")
                        }
                    }
                }
            }
        }
    }
}

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    return if (h > 0) "${h}ч ${m}мин" else "${m}мин"
}
