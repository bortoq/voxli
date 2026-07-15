package com.voxli.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voxli.catalog.db.sanitizeFtsQuery

/**
 * Library main screen.
 * Two modes: Authors / Titles. Search via FTS5. Bottom panel for settings.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen() {
    var viewMode by remember { mutableStateOf(LibraryViewMode.AUTHORS) }
    var searchQuery by remember { mutableStateOf("") }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val listState = rememberLazyListState()

    // Auto-hide keyboard on scroll
    val isScrollInProgress by remember { derivedStateOf { listState.isScrollInProgress } }
    LaunchedEffect(isScrollInProgress) {
        if (isScrollInProgress && searchQuery.isNotEmpty()) {
            focusManager.clearFocus()
        }
    }

    // Debounced search (300ms)
    var debouncedQuery by remember { mutableStateOf("") }
    LaunchedEffect(searchQuery) {
        if (searchQuery.isNotEmpty()) {
            kotlinx.coroutines.delay(300)
            debouncedQuery = searchQuery
        } else {
            debouncedQuery = ""
        }
    }

    val ftsQuery = remember(debouncedQuery) {
        if (debouncedQuery.isNotBlank()) sanitizeFtsQuery(debouncedQuery) else ""
    }

    // TODO: wire to ViewModel with real data
    // val viewModel: LibraryViewModel = koinViewModel()
    // val books by viewModel.searchBooks(ftsQuery).collectAsState(initial = emptyList())

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Voxli") },
                actions = {
                    TextButton(onClick = {
                        viewMode = when (viewMode) {
                            LibraryViewMode.AUTHORS -> LibraryViewMode.TITLES
                            LibraryViewMode.TITLES -> LibraryViewMode.AUTHORS
                        }
                    }) {
                        Text(if (viewMode == LibraryViewMode.AUTHORS) "Авторы ▼" else "Названия ▼")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Search field
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp)
                    .focusRequester(focusRequester),
                placeholder = {
                    Text(if (viewMode == LibraryViewMode.AUTHORS) "Поиск авторов…" else "Поиск по названию…")
                },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                singleLine = true,
            )

            // Content list
            LazyColumn(
                state = listState,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (viewMode == LibraryViewMode.AUTHORS) {
                    // Placeholder: author items
                    items(emptyList<String>()) { author ->
                        AuthorItem(author = author, hasAudio = false)
                    }
                    item {
                        Text(
                            "Библиотека пуста. Загрузите книги через обновление каталога.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 32.dp)
                        )
                    }
                } else {
                    // Placeholder: book items
                    items(emptyList<BookDisplayItem>()) { book ->
                        BookItem(book = book)
                    }
                    if (ftsQuery.isNotBlank()) {
                        item {
                            Text(
                                "Поиск: «$debouncedQuery» — результатов пока нет",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(vertical = 16.dp)
                            )
                        }
                    }
                }
            }

            // Bottom settings bar (placeholder)
            BottomSettingsBar(
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

enum class LibraryViewMode { AUTHORS, TITLES }

@Composable
private fun AuthorItem(author: String, hasAudio: Boolean) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { /* navigate to author's books */ }
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = author,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        if (hasAudio) {
            Text("🎧", style = MaterialTheme.typography.bodySmall)
        }
    }
}

data class BookDisplayItem(
    val id: Long,
    val title: String,
    val author: String,
    val rating: Double,
    val hasAudio: Boolean,
    val series: String = "",
    val seriesNum: Int = 0,
)

@Composable
private fun BookItem(book: BookDisplayItem) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { /* open book */ }
            .padding(vertical = 6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = book.title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (book.hasAudio) {
                Text(" 🎧", style = MaterialTheme.typography.bodySmall)
            }
            Text(
                text = " ${"★".repeat((book.rating + 0.5).toInt())}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
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
}

@Composable
private fun BottomSettingsBar(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier,
        tonalElevation = 2.dp,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(
                text = "Авторы: по популярности",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "[≡≡]",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}
