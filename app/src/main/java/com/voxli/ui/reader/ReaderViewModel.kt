package com.voxli.ui.reader

import android.app.Application
import android.text.TextPaint
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voxli.catalog.db.HistoryEntity
import com.voxli.catalog.db.HistoryDao
import com.voxli.reader.engine.*
import com.voxli.settings.SettingsRepository
import com.voxli.tts.engine.TtsEngine
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel for the reader screen.
 * Manages paginator lifecycle, TTS, progress saving, and settings state.
 *
 * Reference: roadmap §7.5 (paginator lifecycle), §7.6 (TTS), §4.3 (char_offset).
 */
class ReaderViewModel(
    application: Application,
    private val bookDao: com.voxli.catalog.db.BookDao,
    private val historyDao: HistoryDao,
    private val settingsRepo: SettingsRepository,
) : AndroidViewModel(application) {

    // ---- State ----
    private val _readerMode = MutableStateFlow(ReaderMode.READING)
    val readerMode: StateFlow<ReaderMode> = _readerMode.asStateFlow()

    private val _settingsStep = MutableStateFlow(SettingsStep.BG_COLOR)
    val settingsStep: StateFlow<SettingsStep> = _settingsStep.asStateFlow()

    private val _currentPage = MutableStateFlow<Page?>(null)
    val currentPage: StateFlow<Page?> = _currentPage.asStateFlow()

    private val _currentPageIndex = MutableStateFlow(0)
    val currentPageIndex: StateFlow<Int> = _currentPageIndex.asStateFlow()

    private val _totalPages = MutableStateFlow(0)
    val totalPages: StateFlow<Int> = _totalPages.asStateFlow()

    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    // Session state
    private var document: DocumentModel? = null
    private var paginator: Paginator? = null
    private var ttsEngine: TtsEngine? = null

    // Settings from DataStore
    private var bgColor = MutableStateFlow(Color.White)
    private var textColor = MutableStateFlow(Color.Black)
    private var fontSize = MutableStateFlow(16)

    init {
        // Observe settings
        viewModelScope.launch {
            settingsRepo.bgColor.collect { bgColor.value = Color(it) }
        }
        viewModelScope.launch {
            settingsRepo.textColor.collect { textColor.value = Color(it) }
        }
        viewModelScope.launch {
            settingsRepo.fontSize.collect { fontSize.value = it.toInt() }
        }
    }

    /**
     * Load a book for reading.
     * @param bookId flibusta book ID
     * @param filePath path to cached FB2/EPUB file
     * @param image the file
     */
    fun loadBook(bookId: Long, file: java.io.File) {
        viewModelScope.launch {
            _isLoading.value = true

            // Determine parser by extension
            val parser: BookParser = when {
                file.name.endsWith(".fb2", ignoreCase = true) ||
                file.name.endsWith(".fb2.zip", ignoreCase = true) -> Fb2Parser()
                file.name.endsWith(".epub", ignoreCase = true) -> EpubParser()
                else -> {
                    _isLoading.value = false
                    return@launch
                }
            }

            try {
                document = parser.parse(file)
                buildPaginator()
                // Restore progress
                restoreProgress(bookId)
                _isLoading.value = false
            } catch (e: Exception) {
                _isLoading.value = false
            }
        }
    }

    private suspend fun buildPaginator() {
        val doc = document ?: return

        // Convert TextPaint from settings
        val paint = TextPaint().apply {
            color = android.graphics.Color.BLACK
            textSize = fontSize.value * getApplication<Application>().resources.displayMetrics.density
            isAntiAlias = true
        }

        // Dimensions — will be updated from Compose onFirstDraw
        val paginator = Paginator(
            document = doc,
            pageWidthPx = 1080,  // default, updated on first draw
            pageHeightPx = 1600, // default, updated on first draw
            textPaint = paint,
        )
        this.paginator = paginator

        // Observe paginator state
        viewModelScope.launch {
            paginator.currentPageIndex.collect { index ->
                _currentPageIndex.value = index
                _currentPage.value = paginator.getPage(index)
                saveProgress()
            }
        }
        viewModelScope.launch {
            paginator.pageCount.collect { _totalPages.value = it }
        }

        // Start calculating pages
        paginator.calculatePages()

        // Init TTS
        ttsEngine = TtsEngine(getApplication()) {
            // Auto-advance to next page
            paginator.nextPage()
        }
    }

    /** Update paginator dimensions after layout. */
    fun updateDimensions(widthPx: Int, heightPx: Int) {
        val doc = document ?: return
        val paint = TextPaint().apply {
            color = android.graphics.Color.BLACK
            textSize = fontSize.value * getApplication<Application>().resources.displayMetrics.density
            isAntiAlias = true
        }
        val newPaginator = paginator?.rebuild(widthPx, heightPx - dpToPx(48), paint)
            ?: Paginator(doc, widthPx, heightPx - dpToPx(48), paint)

        paginator?.cancel()
        paginator = newPaginator

        viewModelScope.launch {
            newPaginator.currentPageIndex.collect { index ->
                _currentPageIndex.value = index
                _currentPage.value = newPaginator.getPage(index)
            }
        }
        viewModelScope.launch {
            newPaginator.pageCount.collect { _totalPages.value = it }
        }
        newPaginator.calculatePages()
    }

    // ---- Navigation ----

    fun nextPage() = paginator?.nextPage()
    fun prevPage() = paginator?.prevPage()
    fun goToPage(index: Int) = paginator?.goToPage(index)

    // ---- Mode switching ----

    fun toggleTts() {
        val engine = ttsEngine ?: return
        if (engine.isSpeaking.value) {
            engine.stop()
            _readerMode.value = ReaderMode.READING
        } else {
            _readerMode.value = ReaderMode.TTS
            speakCurrentPage()
        }
    }

    fun enterSettings() {
        _readerMode.value = ReaderMode.SETTINGS
        _settingsStep.value = SettingsStep.BG_COLOR
    }

    fun cycleSettings() {
        _settingsStep.value = when (_settingsStep.value) {
            SettingsStep.BG_COLOR -> SettingsStep.TEXT_COLOR
            SettingsStep.TEXT_COLOR -> SettingsStep.FONT_SIZE
            SettingsStep.FONT_SIZE -> SettingsStep.FONT_FACE
            SettingsStep.FONT_FACE -> SettingsStep.DONE
            SettingsStep.DONE -> {
                _readerMode.value = ReaderMode.READING
                return
            }
        }
    }

    // ---- TTS ----

    private fun speakCurrentPage() {
        val page = _currentPage.value ?: return
        val text = page.lines.joinToString("\n") { it.text }
        ttsEngine?.speak(text, "page_${_currentPageIndex.value}")
    }

    fun ttsSpeedUp() = ttsEngine?.speedUp()
    fun ttsSpeedDown() = ttsEngine?.speedDown()

    // ---- Progress ----

    private suspend fun restoreProgress(bookId: Long) {
        val history = historyDao.getHistory(bookId)
        if (history != null && history.charOffset > 0) {
            val pageIndex = paginator?.findPageByCharOffset(history.charOffset) ?: 0
            goToPage(pageIndex)
        }
    }

    private fun saveProgress() {
        val doc = document ?: return
        val page = _currentPage.value ?: return
        viewModelScope.launch {
            historyDao.upsertHistory(
                HistoryEntity(
                    bookId = doc.bookId,
                    status = "reading",
                    charOffset = page.charOffsetStart,
                    progress = if (doc.totalChars > 0) page.charOffsetStart.toDouble() / doc.totalChars else 0.0,
                    updatedAt = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US).format(java.util.Date()),
                )
            )
        }
    }

    // ---- Settings actions ----

    fun settingsLeft() {
        when (_settingsStep.value) {
            SettingsStep.BG_COLOR -> { /* prev color */ }
            SettingsStep.TEXT_COLOR -> { /* prev color */ }
            SettingsStep.FONT_SIZE -> { /* prev font */ }
            SettingsStep.FONT_FACE -> { /* prev face */ }
            SettingsStep.DONE -> {}
        }
    }

    fun settingsRight() {
        when (_settingsStep.value) {
            SettingsStep.BG_COLOR -> { /* next color */ }
            SettingsStep.TEXT_COLOR -> { /* next color */ }
            SettingsStep.FONT_SIZE -> { /* next font */ }
            SettingsStep.FONT_FACE -> { /* next face */ }
            SettingsStep.DONE -> {}
        }
    }

    fun settingsUp() {
        when (_settingsStep.value) {
            SettingsStep.BG_COLOR -> { /* brightness + */ }
            SettingsStep.TEXT_COLOR -> { /* brightness + */ }
            SettingsStep.FONT_SIZE -> {
                viewModelScope.launch { settingsRepo.setFontSize(fontSize.value + 1f) }
            }
            SettingsStep.FONT_FACE -> {}
            SettingsStep.DONE -> {}
        }
    }

    fun settingsDown() {
        when (_settingsStep.value) {
            SettingsStep.BG_COLOR -> { /* brightness - */ }
            SettingsStep.TEXT_COLOR -> { /* brightness - */ }
            SettingsStep.FONT_SIZE -> {
                viewModelScope.launch { settingsRepo.setFontSize((fontSize.value - 1).coerceAtLeast(8).toFloat()) }
            }
            SettingsStep.FONT_FACE -> {}
            SettingsStep.DONE -> {}
        }
    }

    // ---- Lifecycle ----

    override fun onCleared() {
        super.onCleared()
        paginator?.cancel()
        ttsEngine?.shutdown()
    }

    companion object {
        private fun dpToPx(dp: Int): Int {
            return (dp * android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(),
                android.content.res.Resources.getSystem().displayMetrics
            )).toInt()
        }
    }
}
