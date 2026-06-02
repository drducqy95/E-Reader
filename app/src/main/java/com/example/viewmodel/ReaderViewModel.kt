package com.example.viewmodel

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.data.Book
import com.example.data.BookRepository
import com.example.data.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

class ReaderViewModel(
    application: Application,
    private val bookId: Int,
    private val bookRepository: BookRepository,
    private val settingsRepository: SettingsRepository
) : AndroidViewModel(application) {

    val fontSize: StateFlow<Float> = settingsRepository.fontSize
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 18f)
        
    val bgColorIndex: StateFlow<Int> = settingsRepository.bgColorIndex
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)
        
    val ttsEnabled: StateFlow<Boolean> = settingsRepository.ttsEnabled
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    private val _book = MutableStateFlow<Book?>(null)
    val book: StateFlow<Book?> = _book.asStateFlow()

    private val _textContent = MutableStateFlow<String>("")
    val textContent: StateFlow<String> = _textContent.asStateFlow()
    
    private val _isLoading = MutableStateFlow(true)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    init {
        loadBook()
    }

    private fun loadBook() {
        viewModelScope.launch {
            bookRepository.getBook(bookId).collect { loadedBook ->
                _book.value = loadedBook
                if (loadedBook != null && _textContent.value.isEmpty()) {
                    loadTextContent(loadedBook)
                }
            }
        }
    }

    private suspend fun loadTextContent(book: Book) {
        _isLoading.value = true
        withContext(Dispatchers.IO) {
            try {
                val uri = Uri.parse(book.uriString)
                val inputStream = getApplication<Application>().contentResolver.openInputStream(uri)
                if (inputStream != null) {
                    val reader = BufferedReader(InputStreamReader(inputStream))
                    val text = reader.use { it.readText() }
                    _textContent.value = text
                } else {
                    _textContent.value = "Error: Could not open file."
                }
            } catch (e: Exception) {
                _textContent.value = "Error loading file: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }
    
    fun updateProgress(progress: Float) {
        viewModelScope.launch {
            _book.value?.let { currentBook ->
                bookRepository.update(
                    currentBook.copy(
                        progress = progress,
                        lastReadDate = System.currentTimeMillis()
                    )
                )
            }
        }
    }

    fun setFontSize(size: Float) {
        viewModelScope.launch { settingsRepository.setFontSize(size) }
    }

    fun setBgColorIndex(index: Int) {
        viewModelScope.launch { settingsRepository.setBgColorIndex(index) }
    }
    
    fun setTtsEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setTtsEnabled(enabled) }
    }
}

class ReaderViewModelFactory(
    private val application: Application,
    private val bookId: Int,
    private val bookRepository: BookRepository,
    private val settingsRepository: SettingsRepository
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ReaderViewModel::class.java)) {
            @Suppress("UNCHECKED_CAST")
            return ReaderViewModel(application, bookId, bookRepository, settingsRepository) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
