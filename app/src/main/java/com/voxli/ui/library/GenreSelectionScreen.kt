package com.voxli.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Genre selection screen showing distinct genres from the database.
 * By default all genres are selected. Empty selection = show no books.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenreSelectionScreen(
    allGenres: List<String>,
    selectedGenres: Set<String>,
    onGenreToggle: (String) -> Unit,
    onDone: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Фильтр жанров") },
                actions = {
                    TextButton(onClick = onDone) {
                        Text("Готово")
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
            // Info text
            Text(
                text = if (selectedGenres.isEmpty()) "Ничего не выбрано" else "Выбрано: ${selectedGenres.size} из ${allGenres.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            // Genre list
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(allGenres, key = { it }) { genre ->
                    val isChecked = genre in selectedGenres
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp),
                    ) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { onGenreToggle(genre) },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = genre,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            }

            // Quick actions row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                OutlinedButton(onClick = {
                    // Select all
                    allGenres.forEach { genre ->
                        if (genre !in selectedGenres) onGenreToggle(genre)
                    }
                }) {
                    Text("Выбрать всё")
                }
                OutlinedButton(onClick = {
                    // Deselect all
                    allGenres.forEach { genre ->
                        if (genre in selectedGenres) onGenreToggle(genre)
                    }
                }) {
                    Text("Сбросить")
                }
                Button(onClick = onDone) {
                    Text("Применить")
                }
            }
        }
    }
}
