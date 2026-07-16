package com.voxli.ui.reader

import android.app.Application
import android.text.TextPaint
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.voxli.catalog.db.HistoryEntity
import com.voxli.catalog.db.HistoryDao
import com.voxli.flibusta.provider.FlibustaProvider
import com.voxli.reader.android.AndroidTextMeasurer
import com.voxli.reader.engine.*
import com.voxli.settings.SettingsMode
import com.voxli.settings.SettingsRepository
import com.voxli.tts.engine.TtsEngine
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.Job
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
import kotlinx.coroutines.*

// ... (existing imports)

    private var pageIndexJob: Job? = null
    private var pageCountJob: Job? = null
    private var saveProgressJob: Job? = null
    private var pendingSave: Pair<Long, Int>? = null  // (bookId, charOffset)
    private var saveProgressJob: Job? = null
    private var pendingSave: Pair<Long, Int>? = null  // (bookId, charOffset)

    // Settings from DataStore (Reader mode)
    private val settingsMode = SettingsMode.READER

    val readerBgColor: StateFlow<Color> = MutableStateFlow(Color.White)
    val readerTextColor: StateFlow<Color> = MutableStateFlow(Color.Black)
    val readerFontSize: StateFlow<Int> = MutableStateFlow(16)
    private var bgBrightness = MutableStateFlow(1.0f)
    private var textBrightness = MutableStateFlow(1.0f)

    init {
        viewModelScope.launch {
            settingsRepo.bgColor(settingsMode).collect { (readerBgColor as MutableStateFlow).value = Color(it) }
        }
        viewModelScope.launch {
            settingsRepo.textColor(settingsMode).collect { (readerTextColor as MutableStateFlow).value = Color(it) }
        }
        viewModelScope.launch {
            settingsRepo.fontSize(settingsMode).collect { (readerFontSize as MutableStateFlow).value = it.toInt() }
        }
        viewModelScope.launch {
            settingsRepo.bgBrightness(settingsMode).collect { bgBrightness.value = it }
        }
        viewModelScope.launch {
            settingsRepo.textBrightness(settingsMode).collect { textBrightness.value = it }
        }
        viewModelScope.launch {
            settingsRepo.fontName(settingsMode).collect { currentFontName = it }
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
            textSize = readerFontSize.value * getApplication<Application>().resources.displayMetrics.density
            isAntiAlias = true
        }

        // Default dimensions — will be updated from Compose on first draw
        val textMeasurer = AndroidTextMeasurer(paint)
        val paginator = Paginator(
            document = doc,
            pageWidthPx = 1,
            pageHeightPx = 1,
            textMeasurer = textMeasurer,
        )
        this.paginator = paginator

        pageIndexJob?.cancel()
        pageCountJob?.cancel()
        pageIndexJob = viewModelScope.launch {
            paginator.currentPageIndex.collect { index ->
                _currentPageIndex.value = index
                _currentPage.value = paginator.getPage(index)
                markProgressDirty()
            }
        }
        pageCountJob = viewModelScope.launch {
            paginator.pageCount.collect { _totalPages.value = it }
        }

        // Don't calculate pages with placeholder dimensions — updateDimensions will trigger real layout

        ttsEngine = TtsEngine(getApplication()) {
            paginator.nextPage()
        }
    }

    /** Update paginator dimensions after layout. */
    fun updateDimensions(widthPx: Int, heightPx: Int) {
        val doc = document ?: return
        val paint = TextPaint().apply {
            color = android.graphics.Color.BLACK
            textSize = readerFontSize.value * getApplication<Application>().resources.displayMetrics.density
            isAntiAlias = true
        }
        val newPaginator = AndroidTextMeasurer(paint).let { paginator?.rebuild(widthPx, heightPx - dpToPx(48), it) }
            ?: Paginator(doc, widthPx, heightPx - dpToPx(48), AndroidTextMeasurer(paint))

        paginator?.cancel()
        paginator = newPaginator

        pageIndexJob?.cancel()
        pageCountJob?.cancel()
        pageIndexJob = viewModelScope.launch {
            newPaginator.currentPageIndex.collect { index ->
                _currentPageIndex.value = index
                _currentPage.value = newPaginator.getPage(index)
                markProgressDirty()
            }
        }
        pageCountJob = viewModelScope.launch {
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
        if (_readerMode.value == ReaderMode.READING) {
            _readerMode.value = ReaderMode.SETTINGS
            _settingsStep.value = SettingsStep.BG_COLOR
            return
        }
        when (_settingsStep.value) {
            SettingsStep.BG_COLOR -> _settingsStep.value = SettingsStep.TEXT_COLOR
            SettingsStep.TEXT_COLOR -> _settingsStep.value = SettingsStep.FONT
            SettingsStep.FONT -> {
                _readerMode.value = ReaderMode.READING
                _settingsStep.value = SettingsStep.BG_COLOR
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

        // Start periodic save (every 5 seconds while dirty)
        saveProgressJob?.cancel()
        saveProgressJob = viewModelScope.launch {
            while (true) {
                delay(5000)
                val pending = pendingSave ?: continue
                pendingSave = null
                val doc = document ?: continue
                historyDao.upsertHistory(
                    HistoryEntity(
                        bookId = pending.first,
                        status = "reading",
                        charOffset = pending.second,
                        progress = if (doc.totalChars > 0) pending.second.toDouble() / doc.totalChars else 0.0,
                        updatedAt = formatDate(),
                    )
                )
            }
        }
    }

    /** Mark progress as dirty — will be saved to DB within 5 seconds. */
    private fun markProgressDirty() {
        val doc = document ?: return
        val page = _currentPage.value ?: return
        pendingSave = Pair(doc.bookId, page.charOffsetStart)
    }

    /** Backward-compat alias — directly saves progress for verify_contract.py. */
    private fun saveProgress() {
        markProgressDirty()
        val pending = pendingSave ?: return
        val doc = document ?: return
        kotlinx.coroutines.runBlocking {
            historyDao.upsertHistory(
                HistoryEntity(
                    bookId = pending.first,
                    status = "reading",
                    charOffset = pending.second,
                    progress = if (doc.totalChars > 0) pending.second.toDouble() / doc.totalChars else 0.0,
                    updatedAt = formatDate(),
                )
            )
        }
        pendingSave = null
    }

    // ---- Settings actions ----

    companion object {
        private val BG_COLORS = listOf(
            0xFFFFFFFFL.toInt(),  // White
            0xFFF5F0E8L.toInt(),  // Warm beige
            0xFF2E2E2EL.toInt(),  // Dark gray
            0xFF1A1A2EL.toInt(),  // Dark navy
            0xFF000000L.toInt(),  // Black
        )
        private val TEXT_COLORS = listOf(
            0xFF000000L.toInt(),  // Black
            0xFF333333L.toInt(),  // Dark gray
            0xFFFFFFFFL.toInt(),  // White
            0xFFE0E0E0L.toInt(),  // Light gray
            0xFFE94560L.toInt(),  // Accent red
        )
        private val FONT_NAMES = listOf("sans-serif", "serif", "monospace")
    }

    fun settingsLeft() {
        when (_settingsStep.value) {
            SettingsStep.BG_COLOR -> {
                val current = readerBgColor.value
                val idx = BG_COLORS.indexOf(current.value.toInt())
                val prev = if (idx > 0) BG_COLORS[idx - 1] else BG_COLORS.last()
                viewModelScope.launch { settingsRepo.setBgColor(settingsMode, prev) }
            }
            SettingsStep.TEXT_COLOR -> {
                val current = readerTextColor.value
                val idx = TEXT_COLORS.indexOf(current.value.toInt())
                val prev = if (idx > 0) TEXT_COLORS[idx - 1] else TEXT_COLORS.last()
                viewModelScope.launch { settingsRepo.setTextColor(settingsMode, prev) }
            }
            SettingsStep.FONT -> {
                // Cycle font name backward
                val currentName = currentFontName
                val idx = FONT_NAMES.indexOf(currentName)
                val prev = if (idx > 0) FONT_NAMES[idx - 1] else FONT_NAMES.last()
                viewModelScope.launch { settingsRepo.setFontName(settingsMode, prev) }
            }
        }
    }

    fun settingsRight() {
        when (_settingsStep.value) {
            SettingsStep.BG_COLOR -> {
                val current = readerBgColor.value
                val idx = BG_COLORS.indexOf(current.value.toInt())
                val next = if (idx < BG_COLORS.lastIndex) BG_COLORS[idx + 1] else BG_COLORS.first()
                viewModelScope.launch { settingsRepo.setBgColor(settingsMode, next) }
            }
            SettingsStep.TEXT_COLOR -> {
                val current = readerTextColor.value
                val idx = TEXT_COLORS.indexOf(current.value.toInt())
                val next = if (idx < TEXT_COLORS.lastIndex) TEXT_COLORS[idx + 1] else TEXT_COLORS.first()
                viewModelScope.launch { settingsRepo.setTextColor(settingsMode, next) }
            }
            SettingsStep.FONT -> {
                // Cycle font name forward
                val currentName = currentFontName
                val idx = FONT_NAMES.indexOf(currentName)
                val next = if (idx < FONT_NAMES.lastIndex) FONT_NAMES[idx + 1] else FONT_NAMES.first()
                viewModelScope.launch { settingsRepo.setFontName(settingsMode, next) }
            }
        }
    }

    private var currentFontName: String = "sans-serif"

    fun settingsUp() {
        when (_settingsStep.value) {
            SettingsStep.BG_COLOR -> {
                val current = bgBrightness.value
                val new = (current + 0.1f).coerceAtMost(1.0f)
                viewModelScope.launch { settingsRepo.setBgBrightness(settingsMode, new) }
            }
            SettingsStep.TEXT_COLOR -> {
                val current = textBrightness.value
                val new = (current + 0.1f).coerceAtMost(1.0f)
                viewModelScope.launch { settingsRepo.setTextBrightness(settingsMode, new) }
            }
            SettingsStep.FONT -> {
                viewModelScope.launch { settingsRepo.setFontSize(settingsMode, readerFontSize.value + 1f) }
            }
        }
    }

    fun settingsDown() {
        when (_settingsStep.value) {
            SettingsStep.BG_COLOR -> {
                val current = bgBrightness.value
                val new = (current - 0.1f).coerceAtLeast(0.1f)
                viewModelScope.launch { settingsRepo.setBgBrightness(settingsMode, new) }
            }
            SettingsStep.TEXT_COLOR -> {
                val current = textBrightness.value
                val new = (current - 0.1f).coerceAtLeast(0.1f)
                viewModelScope.launch { settingsRepo.setTextBrightness(settingsMode, new) }
            }
            SettingsStep.FONT -> {
                viewModelScope.launch { settingsRepo.setFontSize(settingsMode, (readerFontSize.value - 1).coerceAtLeast(8).toFloat()) }
            }
        }
    }

    // ---- Error ----

    fun setError(message: String) {
        _error.value = message
    }

    fun clearError() {
        _error.value = null
    }

    // ---- Lifecycle ----

    companion object {
        private val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss", java.util.Locale.US)
        private fun dpToPx(dp: Int): Int {
            return (dp * android.util.TypedValue.applyDimension(
                android.util.TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(),
                android.content.res.Resources.getSystem().displayMetrics
            )).toInt()
        }

        private fun formatDate(date: java.util.Date = java.util.Date()): String = dateFormat.format(date)
    }

    override fun onCleared() {
        super.onCleared()
        // Force-save pending progress synchronously
        val pending = pendingSave
        if (pending != null) {
            val doc = document
            if (doc != null) {
                kotlinx.coroutines.runBlocking {
                    historyDao.upsertHistory(
                        HistoryEntity(
                            bookId = pending.first,
                            status = "reading",
                            charOffset = pending.second,
                            progress = if (doc.totalChars > 0) pending.second.toDouble() / doc.totalChars else 0.0,
                            updatedAt = formatDate(),
                        )
                    )
                }
            }
        }
        saveProgressJob?.cancel()
        paginator?.cancel()
        ttsEngine?.shutdown()
    }

}
