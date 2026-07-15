package com.voxli.ui.reader

import android.app.Application
import android.text.TextPaint
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voxli.catalog.db.HistoryEntity
import com.voxli.catalog.db.HistoryDao
import com.voxli.flibusta.provider.FlibustaProvider
import com.voxli.reader.engine.*
import com.voxli.settings.SettingsRepository
import com.voxli.tts.engine.TtsEngine
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch

/**
 * ViewModel for the reader screen.
 * Manages book downloading, paginator lifecycle, TTS, progress saving, and settings state.
 *
 * Reference: roadmap §7.5 (paginator lifecycle), §7.6 (TTS), §4.3 (char_offset).
 */
class ReaderViewModel(
    application: Application,
    private val bookDao: com.voxli.catalog.db.BookDao,
    private val historyDao: HistoryDao,
    private val settingsRepo: SettingsRepository,
    private val bookDownloader: BookDownloader,
    private val flibustaProvider: FlibustaProvider,
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

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    // Session state
    private var document: DocumentModel? = null
    private var paginator: Paginator? = null
    private var ttsEngine: TtsEngine? = null

    // Settings from DataStore
    private var bgColor = MutableStateFlow(Color.White)
    private var textColor = MutableStateFlow(Color.Black)
    private var fontSize = MutableStateFlow(16)

    init {
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
     * Load a book by its flibusta ID: download + parse + build paginator.
     */
    fun loadBook(bookId: Long) {
        if (_isLoading.value) return
        viewModelScope.launch {
            _isLoading.value = true
            _error.value = null

            // Try to download the book (FB2 format)
            val mirror = flibustaProvider.getActiveMirror()
            val file = bookDownloader.download(bookId, "fb2", mirror)
            if (file == null) {
                _error.value = "Не удалось загрузить книгу"
                _isLoading.value = false
                return@launch
            }

            // Parse
            val parser: BookParser = Fb2Parser()
            try {
                document = parser.parse(file)
                buildPaginator()
                restoreProgress(bookId)
            } catch (e: Exception) {
                _error.value = "Ошибка чтения книги: ${e.localizedMessage}"
            }
            _isLoading.value = false
        }
    }

    private suspend fun buildPaginator() {
        val doc = document ?: return

        val paint = TextPaint().apply {
            color = android.graphics.Color.BLACK
            textSize = fontSize.value * getApplication<Application>().resources.displayMetrics.density
            isAntiAlias = true
        }

        // Default dimensions — will be updated from Compose on first draw
        val paginator = Paginator(
            document = doc,
            pageWidthPx = 1080,
            pageHeightPx = 1600,
            textPaint = paint,
        )
        this.paginator = paginator

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

        paginator.calculatePages()

        ttsEngine = TtsEngine(getApplication()) {
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
            SettingsStep.BG_COLOR -> {}
            SettingsStep.TEXT_COLOR -> {}
            SettingsStep.FONT_SIZE -> {}
            SettingsStep.FONT_FACE -> {}
            SettingsStep.DONE -> {}
        }
    }

    fun settingsRight() {
        when (_settingsStep.value) {
            SettingsStep.BG_COLOR -> {}
            SettingsStep.TEXT_COLOR -> {}
            SettingsStep.FONT_SIZE -> {}
            SettingsStep.FONT_FACE -> {}
            SettingsStep.DONE -> {}
        }
    }

    fun settingsUp() {
        when (_settingsStep.value) {
            SettingsStep.BG_COLOR -> {}
            SettingsStep.TEXT_COLOR -> {}
            SettingsStep.FONT_SIZE -> {
                viewModelScope.launch { settingsRepo.setFontSize(fontSize.value + 1f) }
            }
            SettingsStep.FONT_FACE -> {}
            SettingsStep.DONE -> {}
        }
    }

    fun settingsDown() {
        when (_settingsStep.value) {
            SettingsStep.BG_COLOR -> {}
            SettingsStep.TEXT_COLOR -> {}
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
