package com.voxli.ui.player

import android.app.Application
import android.content.ComponentName
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voxli.audio.engine.AudioDownloader
import com.voxli.audio.engine.AudioPlaybackService
import com.voxli.catalog.db.BookDao
import com.voxli.catalog.db.HistoryDao
import com.voxli.catalog.db.HistoryEntity
import com.voxli.knigavuhe.matcher.KnigavuheMatcher
import com.voxli.knigavuhe.matcher.NarratorInfo
import com.voxli.knigavuhe.matcher.TrackInfo
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * ViewModel for audiobook player screen.
 *
 * Reference: roadmap §10 Phase 3, §14.2 Knigavuhe narrators/tracks.
 */
class PlayerViewModel(
    application: Application,
    private val bookDao: BookDao,
    private val matcher: KnigavuheMatcher,
    private val downloader: AudioDownloader,
    private val historyDao: HistoryDao,
) : AndroidViewModel(application) {

    private var playbackService: AudioPlaybackService? = null

    // ---- Book state ----
    private val _bookTitle = MutableStateFlow("")
    val bookTitle: StateFlow<String> = _bookTitle.asStateFlow()

    private val _bookAuthor = MutableStateFlow("")
    val bookAuthor: StateFlow<String> = _bookAuthor.asStateFlow()

    private val _narratorName = MutableStateFlow("")
    val narratorName: StateFlow<String> = _narratorName.asStateFlow()

    private val _tracks = MutableStateFlow<List<TrackInfo>>(emptyList())
    val tracks: StateFlow<List<TrackInfo>> = _tracks.asStateFlow()

    // Convenience for track titles — set alongside _tracks in loadBook
    private val _trackTitles = MutableStateFlow<List<String>>(emptyList())
    val trackTitles: StateFlow<List<String>> = _trackTitles.asStateFlow()

    // ---- Playback state ----
    private val _currentTrackIndex = MutableStateFlow(0)
    val currentTrackIndex: StateFlow<Int> = _currentTrackIndex.asStateFlow()

    private val _isPlaying = MutableStateFlow(false)
    val isPlaying: StateFlow<Boolean> = _isPlaying.asStateFlow()

    private val _currentPositionMs = MutableStateFlow(0L)
    val currentPositionMs: StateFlow<Long> = _currentPositionMs.asStateFlow()

    private val _totalDurationMs = MutableStateFlow(0L)
    val totalDurationMs: StateFlow<Long> = _totalDurationMs.asStateFlow()

    private val _playbackSpeed = MutableStateFlow(1.0f)
    val playbackSpeed: StateFlow<Float> = _playbackSpeed.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private var bookId: Long = 0L

    // ---- Position polling ----
    private var isPolling = false

    /**
     * Load audiobook by book ID and reader ID from knigavuhe.
     * Looks up book metadata and narrator info asynchronously.
     */
    fun loadBook(bookId: Long, readerId: Long) {
        if (_isLoading.value) return
        viewModelScope.launch {
            _isLoading.value = true
            try {
                val book = bookDao.getBookById(bookId)
                if (book == null) {
                    _bookTitle.value = "Книга не найдена"
                    _isLoading.value = false
                    return@launch
                }
                _bookTitle.value = book.title
                _bookAuthor.value = book.author

                val narrators = matcher.fetchNarrators(book.id, book.title, book.author)
                val narrator = narrators.find { it.readerId == readerId } ?: narrators.firstOrNull()
                if (narrator == null) {
                    _narratorName.value = "Чтец не найден"
                    _isLoading.value = false
                    return@launch
                }
                loadBook(bookId, book.title, book.author, narrator)
            } catch (e: Exception) {
                _bookTitle.value = "Ошибка загрузки"
            }
            _isLoading.value = false
        }
    }

    /**
     * Load audiobook data for a book with pre-fetched narrator info.
     */
    fun loadBook(bookId: Long, bookTitle: String, bookAuthor: String, narratorInfo: NarratorInfo) {
        this.bookId = bookId
        _bookTitle.value = bookTitle
        _bookAuthor.value = bookAuthor
        _narratorName.value = narratorInfo.name
        _tracks.value = narratorInfo.tracks
        _trackTitles.value = narratorInfo.tracks.map { it.title }

        // Restore progress from history
        viewModelScope.launch {
            val history = historyDao.getHistory(bookId)
            if (history != null && history.playbackPos > 0) {
                // Find which track the position falls in
                var accumulatedMs = 0L
                for ((index, track) in narratorInfo.tracks.withIndex()) {
                    val trackDurationMs = track.durationSeconds * 1000
                    if (history.playbackPos < accumulatedMs + trackDurationMs) {
                        _currentTrackIndex.value = index
                        _currentPositionMs.value = history.playbackPos - accumulatedMs
                        break
                    }
                    accumulatedMs += trackDurationMs
                }
            }
        }
    }

    /**
     * Bind to AudioPlaybackService and start playback.
     */
    fun startPlayback(service: AudioPlaybackService) {
        playbackService = service
        val tracks = _tracks.value
        if (tracks.isEmpty()) return

        service.loadPlaylist(
            tracks = tracks,
            startIndex = _currentTrackIndex.value,
            bookTitle = _bookTitle.value,
        )

        // Seek to saved position
        if (_currentPositionMs.value > 0) {
            service.seekTo(_currentPositionMs.value)
        }

        service.play()
        _isPlaying.value = true
        startPositionPolling()
    }

    /**
     * Bind to existing service without starting playback (for background restore).
     */
    fun bindToService(service: AudioPlaybackService) {
        playbackService = service
        startPositionPolling()
    }

    // ---- Playback controls ----

    fun playPause() {
        val svc = playbackService ?: return
        svc.togglePlayPause()
        _isPlaying.value = svc.isPlaying
    }

    fun nextTrack() {
        val svc = playbackService ?: return
        svc.next()
        _currentTrackIndex.value = svc.currentMediaItemIndex
    }

    fun previousTrack() {
        val svc = playbackService ?: return
        svc.previous()
        _currentTrackIndex.value = svc.currentMediaItemIndex
    }

    fun seekTo(progress: Float) {
        val svc = playbackService ?: return
        val duration = _totalDurationMs.value
        if (duration > 0) {
            svc.seekTo((progress * duration).toLong())
        }
    }

    fun setSpeed(speed: Float) {
        val svc = playbackService ?: return
        svc.setSpeed(speed)
        _playbackSpeed.value = speed
    }

    fun selectTrack(index: Int) {
        val svc = playbackService ?: return
        svc.seekToDefaultPosition(index)
        _currentTrackIndex.value = index
        if (!svc.isPlaying) {
            svc.play()
            _isPlaying.value = true
        }
    }

    // ---- Position polling ----

    private fun startPositionPolling() {
        if (isPolling) return
        isPolling = true
        viewModelScope.launch {
            while (isPolling) {
                val svc = playbackService ?: break
                _currentPositionMs.value = svc.currentPosition
                _totalDurationMs.value = svc.duration
                _currentTrackIndex.value = svc.currentMediaItemIndex
                _isPlaying.value = svc.isPlaying

                // Save progress periodically
                if (bookId > 0) {
                    saveProgress()
                }

                delay(500)  // 500ms polling interval
            }
        }
    }

    fun stopPolling() {
        isPolling = false
    }

    // ---- Progress persistence ----

    private suspend fun saveProgress() {
        val svc = playbackService ?: return
        val tracks = _tracks.value
        if (tracks.isEmpty()) return

        // Calculate total playback position across all tracks
        var totalMs = svc.currentPosition
        val currentIdx = svc.currentMediaItemIndex
        for (i in 0 until currentIdx) {
            totalMs += tracks.getOrNull(i)?.durationSeconds?.times(1000) ?: 0
        }

        historyDao.upsertHistory(
            HistoryEntity(
                bookId = bookId,
                status = "listening",
                progress = if (tracks.isNotEmpty()) (currentIdx + 1).toDouble() / tracks.size else 0.0,
                playbackPos = totalMs,
                updatedAt = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", Locale.US).format(Date()),
            )
        )
    }

    override fun onCleared() {
        super.onCleared()
        stopPolling()
        saveProgressSync()
    }

    private fun saveProgressSync() {
        runBlocking {
            saveProgress()
        }
    }
}
