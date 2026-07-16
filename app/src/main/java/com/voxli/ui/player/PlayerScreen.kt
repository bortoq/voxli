package com.voxli.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Audiobook player screen.
 *
 * Reference: roadmap §10 Phase 3 — UI плеера: обложка, треки, прогресс.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    // Book info
    bookTitle: String,
    bookAuthor: String,
    narratorName: String,
    // Tracks
    tracks: List<String>,       // track titles
    currentTrackIndex: Int,
    // Playback state
    isPlaying: Boolean,
    currentPositionMs: Long,
    totalDurationMs: Long,
    playbackSpeed: Float,
    // Actions
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSeek: (Float) -> Unit,    // 0.0–1.0
    onSpeedChange: (Float) -> Unit,
    onTrackSelect: (Int) -> Unit,
    onTapReader: () -> Unit,    // toggle back to reader
    // Colors
    bgColor: Color = Color(0xFF1A1A2E),
    accentColor: Color = Color(0xFFE94560),
    textColor: Color = Color.White,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Аудиокнига", color = textColor) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "Назад",
                            tint = textColor,
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = bgColor,
                ),
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(bgColor)
                .padding(padding)
                .padding(horizontal = 16.dp),
        ) {
            // ---- Cover / Book Info (tap to toggle to Reader) ----
            Spacer(Modifier.height(16.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onTapReader() },
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    // Placeholder cover
                    Box(
                        modifier = Modifier
                            .size(200.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(accentColor.copy(alpha = 0.3f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        Icon(
                            Icons.Default.Headphones,
                            contentDescription = null,
                            tint = accentColor,
                            modifier = Modifier.size(64.dp),
                        )
                    }

                    Spacer(Modifier.height(16.dp))

                    Text(
                        text = bookTitle,
                        color = textColor,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Text(
                        text = bookAuthor,
                        color = textColor.copy(alpha = 0.7f),
                        style = MaterialTheme.typography.bodyMedium,
                    )

                    Text(
                        text = "Читает: $narratorName",
                        color = textColor.copy(alpha = 0.5f),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
                modifier = Modifier.align(Alignment.CenterHorizontally),
            )

            Spacer(Modifier.height(24.dp))

            // ---- Progress bar ----
            val progress = if (totalDurationMs > 0) currentPositionMs.toFloat() / totalDurationMs else 0f

            Slider(
                value = progress,
                onValueChange = onSeek,
                colors = SliderDefaults.colors(
                    thumbColor = accentColor,
                    activeTrackColor = accentColor,
                    inactiveTrackColor = textColor.copy(alpha = 0.2f),
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            // Time labels
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = formatTime(currentPositionMs),
                    color = textColor.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = formatTime(totalDurationMs),
                    color = textColor.copy(alpha = 0.5f),
                    style = MaterialTheme.typography.bodySmall,
                )
            }

            Spacer(Modifier.height(16.dp))

            // ---- Playback controls ----
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Speed
                TextButton(onClick = {
                    val speeds = listOf(0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 2.0f)
                    val currentIdx = speeds.indexOfFirst { it >= playbackSpeed }
                    val nextIdx = (currentIdx + 1) % speeds.size
                    onSpeedChange(speeds[nextIdx])
                }) {
                    Text(
                        "${playbackSpeed}x",
                        color = accentColor,
                        fontSize = 14.sp,
                    )
                }

                Spacer(Modifier.width(16.dp))

                // Previous
                IconButton(
                    onClick = onPrevious,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        contentDescription = "Предыдущий",
                        tint = textColor,
                        modifier = Modifier.size(36.dp),
                    )
                }

                Spacer(Modifier.width(8.dp))

                // Play/Pause
                Surface(
                    onClick = onPlayPause,
                    shape = CircleShape,
                    color = accentColor,
                    modifier = Modifier.size(64.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Пауза" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }

                Spacer(Modifier.width(8.dp))

                // Next
                IconButton(
                    onClick = onNext,
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = "Следующий",
                        tint = textColor,
                        modifier = Modifier.size(36.dp),
                    )
                }

                Spacer(Modifier.width(16.dp))

                // Rewind / Forward placeholders
                Text(
                    "${(playbackSpeed * 100).toInt()}%",
                    color = textColor.copy(alpha = 0.5f),
                    fontSize = 12.sp,
                )
            }

            Spacer(Modifier.height(24.dp))

            // ---- Track list ----
            Text(
                text = "Треки",
                color = textColor.copy(alpha = 0.7f),
                style = MaterialTheme.typography.titleSmall,
            )

            Spacer(Modifier.height(8.dp))

            val listState = rememberLazyListState()

            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                itemsIndexed(tracks) { index, title ->
                    val isCurrent = index == currentTrackIndex
                    Surface(
                        onClick = { onTrackSelect(index) },
                        color = if (isCurrent) accentColor.copy(alpha = 0.2f) else Color.Transparent,
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = "${index + 1}",
                                color = if (isCurrent) accentColor else textColor.copy(alpha = 0.5f),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.width(24.dp),
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = title,
                                color = if (isCurrent) accentColor else textColor,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f),
                            )
                            if (isCurrent && isPlaying) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = null,
                                    tint = accentColor,
                                    modifier = Modifier.size(16.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatTime(ms: Long): String {
    val totalSec = ms / 1000
    val hours = totalSec / 3600
    val minutes = (totalSec % 3600) / 60
    val seconds = totalSec % 60
    return if (hours > 0) {
        "%d:%02d:%02d".format(hours, minutes, seconds)
    } else {
        "%d:%02d".format(minutes, seconds)
    }
}
