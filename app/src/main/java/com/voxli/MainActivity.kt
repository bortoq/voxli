package com.voxli

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.voxli.catalog.db.BookEntity
import com.voxli.ui.library.GenreSelectionScreen
import com.voxli.ui.library.HistoryScreen
import com.voxli.ui.library.LibraryScreen
import com.voxli.ui.library.LibraryViewModel
import com.voxli.ui.player.PlayerScreen
import com.voxli.ui.player.PlayerViewModel
import com.voxli.ui.reader.ReaderScreen
import com.voxli.ui.reader.ReaderViewModel
import org.koin.compose.viewmodel.koinViewModel

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VoxliNavigation()
        }
    }
}

// Route constants
object Routes {
    const val LIBRARY = "library"
    const val GENRE_FILTER = "genre_filter"
    const val HISTORY = "history"
    const val READER = "reader/{bookId}/{filePath}"
    const val PLAYER = "player/{bookId}/{narratorIndex}"

    fun reader(bookId: Long, filePath: String) = "reader/$bookId/$filePath"
    fun player(bookId: Long, narratorIndex: Int) = "player/$bookId/$narratorIndex"
}

@Composable
fun VoxliNavigation() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Routes.LIBRARY,
        enterTransition = { fadeIn(initialAlpha = 0.3f) },
        exitTransition = { fadeOut(targetAlpha = 0.3f) },
    ) {
        // ---- Library ----
        composable(Routes.LIBRARY) {
            val viewModel: LibraryViewModel = koinViewModel()

            LibraryScreen(
                viewModel = viewModel,
                onBookClick = { book ->
                    // Navigate to reader (or player if audio-only)
                    navController.navigate(Routes.reader(book.id, ""))
                },
                onHistoryClick = {
                    navController.navigate(Routes.HISTORY)
                },
                onFilterClick = {
                    navController.navigate(Routes.GENRE_FILTER)
                },
            )
        }

        // ---- Genre filter ----
        composable(Routes.GENRE_FILTER) {
            val libViewModel: LibraryViewModel = koinViewModel()
            val selectedGenres by libViewModel.selectedGenres.collectAsState()

            GenreSelectionScreen(
                selectedGenres = selectedGenres,
                onGenreToggle = { libViewModel.toggleGenre(it) },
                onDone = { navController.popBackStack() },
            )
        }

        // ---- History ----
        composable(Routes.HISTORY) {
            val libViewModel: LibraryViewModel = koinViewModel()
            val history by libViewModel.history.collectAsState()

            HistoryScreen(
                history = history,
                onBack = { navController.popBackStack() },
                onBookClick = { bookId ->
                    navController.navigate(Routes.reader(bookId, ""))
                },
            )
        }

        // ---- Reader ----
        composable(
            route = Routes.READER,
            arguments = listOf(
                navArgument("bookId") { type = NavType.LongType },
                navArgument("filePath") { type = NavType.StringType },
            ),
        ) {
            val viewModel: ReaderViewModel = koinViewModel()
            val readerMode by viewModel.readerMode.collectAsState()
            val settingsStep by viewModel.settingsStep.collectAsState()
            val currentPage by viewModel.currentPage.collectAsState()
            val currentPageIndex by viewModel.currentPageIndex.collectAsState()
            val totalPages by viewModel.totalPages.collectAsState()
            val isLoading by viewModel.isLoading.collectAsState()

            if (isLoading) {
                // Loading state
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = androidx.compose.ui.Alignment.Center,
                ) {
                    androidx.compose.material3.Text("Загрузка книги...")
                }
            } else {
                ReaderScreen(
                    currentPage = currentPage,
                    currentPageIndex = currentPageIndex,
                    totalPages = totalPages,
                    readerMode = readerMode,
                    settingsStep = settingsStep,
                    isTtsPlaying = false,  // TODO: wire TTS state
                    ttsSpeed = 1.0f,
                    onTapZone1 = { navController.popBackStack() },
                    onTapZone2 = { viewModel.prevPage() },
                    onTapZone3 = { viewModel.toggleTts() },
                    onTapZone4 = { viewModel.nextPage() },
                    onTapZone5 = { /* reserved */ },
                    onProgressBarTap = { viewModel.cycleSettings() },
                    onSettingsLeft = { viewModel.settingsLeft() },
                    onSettingsRight = { viewModel.settingsRight() },
                    onSettingsUp = { viewModel.settingsUp() },
                    onSettingsDown = { viewModel.settingsDown() },
                )
            }
        }

        // ---- Player ----
        composable(
            route = Routes.PLAYER,
            arguments = listOf(
                navArgument("bookId") { type = NavType.LongType },
                navArgument("narratorIndex") { type = NavType.IntType },
            ),
        ) {
            val viewModel: PlayerViewModel = koinViewModel()
            val bookTitle by viewModel.bookTitle.collectAsState()
            val bookAuthor by viewModel.bookAuthor.collectAsState()
            val narratorName by viewModel.narratorName.collectAsState()
            val trackTitles by viewModel.trackTitles.collectAsState()
            val currentTrackIndex by viewModel.currentTrackIndex.collectAsState()
            val isPlaying by viewModel.isPlaying.collectAsState()
            val currentPositionMs by viewModel.currentPositionMs.collectAsState()
            val totalDurationMs by viewModel.totalDurationMs.collectAsState()
            val playbackSpeed by viewModel.playbackSpeed.collectAsState()

            PlayerScreen(
                bookTitle = bookTitle,
                bookAuthor = bookAuthor,
                narratorName = narratorName,
                tracks = trackTitles,
                currentTrackIndex = currentTrackIndex,
                isPlaying = isPlaying,
                currentPositionMs = currentPositionMs,
                totalDurationMs = totalDurationMs,
                playbackSpeed = playbackSpeed,
                onBack = { navController.popBackStack() },
                onPlayPause = { viewModel.playPause() },
                onNext = { viewModel.nextTrack() },
                onPrevious = { viewModel.previousTrack() },
                onSeek = { viewModel.seekTo(it) },
                onSpeedChange = { viewModel.setSpeed(it) },
                onTrackSelect = { viewModel.selectTrack(it) },
            )
        }
    }
}
