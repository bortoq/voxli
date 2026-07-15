package com.voxli.ui.library

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * The 22 flibusta genres as defined in roadmap §14.1.
 */
val FLIBUSTA_GENRES: List<Pair<String, String>> = listOf(
    "prose" to "Проза",
    "detective" to "Детектив",
    "fantasy" to "Фэнтези",
    "sci-fi" to "Научная фантастика",
    "horror" to "Ужасы и Мистика",
    "adventure" to "Приключения",
    "romance" to "Любовный роман",
    "thriller" to "Триллер",
    "poetry" to "Поэзия",
    "drama" to "Драматургия",
    "humor" to "Юмор",
    "child" to "Детская",
    "history" to "История",
    "religion" to "Религия",
    "philosophy" to "Философия",
    "psychology" to "Психология",
    "technical" to "Техническая",
    "reference" to "Справочная",
    "nonfiction" to "Документальная / Публицистика",
    "biography" to "Биография",
    "military" to "Военная",
    "classic" to "Классика",
)

/**
 * Genre selection screen with checkboxes for all 22 flibusta genres.
 * Reference: roadmap §8.4 step 3, §14.1.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GenreSelectionScreen(
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
                text = if (selectedGenres.isEmpty()) "Выбрано: все жанры" else "Выбрано: ${selectedGenres.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )

            // Genre list
            LazyColumn(
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(vertical = 4.dp),
            ) {
                items(FLIBUSTA_GENRES) { (term, label) ->
                    val isChecked = term in selectedGenres
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 2.dp),
                    ) {
                        Checkbox(
                            checked = isChecked,
                            onCheckedChange = { onGenreToggle(term) },
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            text = label,
                            style = MaterialTheme.typography.bodyLarge,
                            modifier = Modifier
                                .weight(1f)
                                .padding(vertical = 12.dp),
                        )
                    }
                }
            }

            // Quick actions
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                OutlinedButton(onClick = {
                    // Deselect all
                    FLIBUSTA_GENRES.forEach { (term, _) ->
                        if (term in selectedGenres) onGenreToggle(term)
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
