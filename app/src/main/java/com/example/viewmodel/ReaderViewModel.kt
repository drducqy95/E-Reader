package com.example.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.drduc.engine.TraceLevel
import com.drduc.engine.TranslationMode
import com.drduc.engine.TranslationOrchestrator
import com.drduc.engine.TranslationProfile
import com.drduc.engine.TranslationRequest
import com.example.data.Book
import com.example.data.BookRepository
import com.example.data.Bookmark
import com.example.data.Chapter
import com.example.data.OnlineLibraryService
import com.example.data.ReaderDao
import com.example.data.ReaderNote
import com.example.data.ReadingProgress
import com.example.data.SettingsRepository
import com.example.data.TranslationCacheEntity
import com.example.data.isOnlineBook
import java.security.MessageDigest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ReaderTranslationMode { RAW, VP, HV, MS }

class ReaderViewModel(
    application: Application,
    private val bookId: Int,
    private val bookRepository: BookRepository,
    private val readerDao: ReaderDao,
    private val settingsRepository: SettingsRepository,
    private val translationOrchestrator: TranslationOrchestrator,
    private val onlineLibraryService: OnlineLibraryService
) : AndroidViewModel(application) {
    val fontSize = settingsRepository.fontSize.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 18f)
    val bgColorIndex = settingsRepository.bgColorIndex.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
    val ttsEnabled = settingsRepository.ttsEnabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)
    val readerLayout = settingsRepository.readerLayout.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), "PAGED")
    val lineSpacing = settingsRepository.lineSpacing.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 1.35f)
    val msOnlineRefinement = settingsRepository.msOnlineRefinement.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)
    val bookmarks = readerDao.observeBookmarks(bookId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val notes = readerDao.observeNotes(bookId).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    private val _book = MutableStateFlow<Book?>(null)
    val book: StateFlow<Book?> = _book.asStateFlow()
    private val _chapters = MutableStateFlow<List<Chapter>>(emptyList())
    val chapters: StateFlow<List<Chapter>> = _chapters.asStateFlow()
    private val _chapterIndex = MutableStateFlow(0)
    val chapterIndex: StateFlow<Int> = _chapterIndex.asStateFlow()
    private val _paragraphAnchor = MutableStateFlow(0)
    val paragraphAnchor: StateFlow<Int> = _paragraphAnchor.asStateFlow()
    private val _readerTranslationMode = MutableStateFlow(ReaderTranslationMode.MS)
    val readerTranslationMode: StateFlow<ReaderTranslationMode> = _readerTranslationMode.asStateFlow()
    private val _textContent = MutableStateFlow("")
    val textContent: StateFlow<String> = _textContent.asStateFlow()
    private val _warning = MutableStateFlow<String?>(null)
    val warning: StateFlow<String?> = _warning.asStateFlow()
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()
    private val _translationStatus = MutableStateFlow<String?>(null)
    val translationStatus: StateFlow<String?> = _translationStatus.asStateFlow()
    private var renderJob: Job? = null

    init {
        viewModelScope.launch { loadBook() }
    }

    fun setReaderTranslationMode(mode: ReaderTranslationMode) {
        if (_readerTranslationMode.value == mode) return
        _readerTranslationMode.value = mode
        scheduleRender()
    }

    fun previousChapter() = openChapter(_chapterIndex.value - 1)
    fun nextChapter() = openChapter(_chapterIndex.value + 1)

    fun openChapter(index: Int, anchor: Int = 0) {
        if (index !in _chapters.value.indices) return
        _chapterIndex.value = index
        _paragraphAnchor.value = anchor.coerceAtLeast(0)
        scheduleRender()
    }

    fun updateParagraphAnchor(anchor: Int, progress: Float) {
        _paragraphAnchor.value = anchor.coerceAtLeast(0)
        viewModelScope.launch {
            readerDao.saveProgress(ReadingProgress(bookId, _chapterIndex.value, _paragraphAnchor.value))
            _book.value?.let {
                bookRepository.update(it.copy(progress = progress.coerceIn(0f, 1f), lastReadDate = System.currentTimeMillis()))
            }
        }
    }

    fun setFontSize(size: Float) { viewModelScope.launch { settingsRepository.setFontSize(size) } }
    fun setBgColorIndex(index: Int) { viewModelScope.launch { settingsRepository.setBgColorIndex(index) } }
    fun setTtsEnabled(enabled: Boolean) { viewModelScope.launch { settingsRepository.setTtsEnabled(enabled) } }
    fun setReaderLayout(layout: String) { viewModelScope.launch { settingsRepository.setReaderLayout(layout) } }
    fun setLineSpacing(value: Float) { viewModelScope.launch { settingsRepository.setLineSpacing(value) } }
    fun setMsOnlineRefinement(enabled: Boolean) { viewModelScope.launch { settingsRepository.setMsOnlineRefinement(enabled) } }

    fun toggleBookmark() {
        viewModelScope.launch {
            val current = bookmarks.value.firstOrNull {
                it.chapterIndex == _chapterIndex.value && it.paragraphAnchor == _paragraphAnchor.value
            }
            if (current != null) {
                readerDao.deleteBookmark(current.id)
            } else {
                readerDao.putBookmark(
                    Bookmark(bookId = bookId, chapterIndex = _chapterIndex.value, paragraphAnchor = _paragraphAnchor.value, excerpt = excerptAtAnchor())
                )
            }
        }
    }

    fun addNote(content: String) {
        if (content.isBlank()) return
        viewModelScope.launch {
            readerDao.putNote(
                ReaderNote(
                    bookId = bookId,
                    chapterIndex = _chapterIndex.value,
                    paragraphAnchor = _paragraphAnchor.value,
                    excerpt = excerptAtAnchor(),
                    content = content.trim()
                )
            )
        }
    }

    fun deleteNote(id: Long) { viewModelScope.launch { readerDao.deleteNote(id) } }

    private suspend fun loadBook() {
        _isLoading.value = true
        val loaded = bookRepository.getBook(bookId).first()
        _book.value = loaded
        if (loaded == null) {
            _textContent.value = "Book not found."
            _isLoading.value = false
            return
        }
        _chapters.value = try {
            withContext(Dispatchers.IO) {
                if (loaded.isOnlineBook()) onlineLibraryService.ensureToc(loaded)
                else readerDao.getChapters(bookId).ifEmpty { importLocalChapters(loaded) }
            }
        } catch (error: Exception) {
            _warning.value = error.message ?: "Could not load chapters."
            emptyList()
        }
        readerDao.getProgress(bookId)?.let {
            _chapterIndex.value = it.chapterIndex.coerceIn(0, _chapters.value.lastIndex.coerceAtLeast(0))
            _paragraphAnchor.value = it.chapterOffset.coerceAtLeast(0)
        }
        renderCurrentChapter()
    }

    private suspend fun renderCurrentChapter() {
        var chapter = _chapters.value.getOrNull(_chapterIndex.value) ?: run {
            _textContent.value = "No chapter content is available."
            _isLoading.value = false
            return
        }
        _isLoading.value = true
        val loadedBook = _book.value
        if (loadedBook?.isOnlineBook() == true && chapter.rawContent.isBlank()) {
            chapter = try {
                withContext(Dispatchers.IO) { onlineLibraryService.ensureContent(loadedBook, chapter) }
            } catch (error: Exception) {
                _warning.value = error.message ?: "Could not load online chapter."
                _textContent.value = "Không thể tải nội dung chương."
                _isLoading.value = false
                return
            }
            _chapters.value = _chapters.value.map { if (it.chapterIndex == chapter.chapterIndex) chapter else it }
        }
        val readerMode = _readerTranslationMode.value
        val request = TranslationRequest(
            text = chapter.rawContent,
            mode = translationMode(readerMode),
            translationProfile = translationProfile(readerMode),
            traceLevel = TraceLevel.SUMMARY
        )
        if (request.mode == TranslationMode.RAW) {
            _textContent.value = chapter.rawContent
            _warning.value = null
            _translationStatus.value = null
            _isLoading.value = false
            preloadNextChapter()
            return
        }
        val identity = translationOrchestrator.cacheIdentity(request)
        val cached = withContext(Dispatchers.IO) { readerDao.getTranslation(identity.cacheKey) }
        if (cached != null) {
            _textContent.value = cached.translatedText
            _warning.value = if (request.mode == TranslationMode.HYBRID_AUTO && identity.providerMode == "offline-fallback") {
                "Online refinement chưa cấu hình; đang hiển thị bản dịch offline."
            } else null
            _translationStatus.value = "Bản dịch lấy từ cache."
            _isLoading.value = false
            preloadNextChapter()
            return
        }
        _translationStatus.value = "Đang dịch chương..."
        val result = withContext(Dispatchers.Default) { translationOrchestrator.translate(request) }
        if (_chapters.value.getOrNull(_chapterIndex.value)?.id != chapter.id || _readerTranslationMode.value != readerMode) return
        withContext(Dispatchers.IO) {
            readerDao.putTranslation(
                TranslationCacheEntity(
                    cacheKey = identity.cacheKey,
                    chapterId = chapter.id,
                    mode = "${readerMode.name}:${request.mode.name}",
                    translatedText = result.displayText,
                    graphVersion = identity.graphVersion,
                    overlayVersion = identity.overlayVersion,
                    providerMode = identity.providerMode,
                    configHash = identity.configHash,
                    sourceChecksum = sha256(chapter.rawContent),
                    hookVersion = identity.hookVersion,
                    dictionaryVersion = identity.dictionaryVersion
                )
            )
        }
        _textContent.value = result.displayText
        _warning.value = result.warning
        _translationStatus.value = "Đã lưu bản dịch ${readerMode.name}."
        _isLoading.value = false
        preloadNextChapter()
    }

    private fun translationMode(mode: ReaderTranslationMode): TranslationMode = when (mode) {
        ReaderTranslationMode.RAW -> TranslationMode.RAW
        ReaderTranslationMode.MS -> if (msOnlineRefinement.value) TranslationMode.HYBRID_AUTO else TranslationMode.OFFLINE
        ReaderTranslationMode.VP, ReaderTranslationMode.HV -> TranslationMode.OFFLINE
    }

    private fun translationProfile(mode: ReaderTranslationMode): TranslationProfile = when (mode) {
        ReaderTranslationMode.RAW -> TranslationProfile.RAW
        ReaderTranslationMode.VP -> TranslationProfile.VIETPHRASE
        ReaderTranslationMode.HV -> TranslationProfile.HAN_VIET
        ReaderTranslationMode.MS -> TranslationProfile.DRDUC
    }

    private fun scheduleRender() {
        renderJob?.cancel()
        renderJob = viewModelScope.launch { renderCurrentChapter() }
    }

    private fun preloadNextChapter() {
        val loadedBook = _book.value ?: return
        if (!loadedBook.isOnlineBook()) return
        val next = _chapters.value.getOrNull(_chapterIndex.value + 1) ?: return
        if (next.rawContent.isNotBlank()) return
        viewModelScope.launch(Dispatchers.IO) { runCatching { onlineLibraryService.ensureContent(loadedBook, next) } }
    }

    private fun excerptAtAnchor(): String =
        _textContent.value.lineSequence().filter(String::isNotBlank).drop(_paragraphAnchor.value).firstOrNull().orEmpty().take(180)

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }

    private suspend fun importLocalChapters(book: Book): List<Chapter> {
        if (book.format != "TXT") return listOf(Chapter(bookId = book.id, chapterIndex = 0, title = book.title, rawContent = "Unsupported local format."))
        val resolver = getApplication<Application>().contentResolver
        val text = resolver.openInputStream(Uri.parse(book.uriString))?.bufferedReader()?.use { it.readText() }
            ?: return listOf(Chapter(bookId = book.id, chapterIndex = 0, title = book.title, rawContent = "Could not open file."))
        val blocks = text.split(CHAPTER_BOUNDARY).filter(String::isNotBlank)
        val chapters = (if (blocks.isEmpty()) listOf(text) else blocks).mapIndexed { index, raw ->
            Chapter(
                bookId = book.id,
                chapterIndex = index,
                title = raw.lineSequence().firstOrNull()?.trim()?.take(100).orEmpty().ifBlank { "Chương ${index + 1}" },
                rawContent = raw.trim()
            )
        }
        readerDao.insertChapters(chapters)
        return readerDao.getChapters(book.id)
    }

    companion object {
        private val CHAPTER_BOUNDARY = Regex(
            "(?m)(?=^\\s*(?:第\\s*[0-9一二三四五六七八九十百千零〇两]+\\s*[章节回卷]|Ch(?:apter|uong)\\s+\\d+))",
            RegexOption.IGNORE_CASE
        )
    }
}

class ReaderViewModelFactory(
    private val application: Application,
    private val bookId: Int,
    private val bookRepository: BookRepository,
    private val readerDao: ReaderDao,
    private val settingsRepository: SettingsRepository,
    private val translationOrchestrator: TranslationOrchestrator,
    private val onlineLibraryService: OnlineLibraryService
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ReaderViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ReaderViewModel(application, bookId, bookRepository, readerDao, settingsRepository, translationOrchestrator, onlineLibraryService) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
