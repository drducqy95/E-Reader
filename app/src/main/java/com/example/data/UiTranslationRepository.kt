package com.example.data

import com.drduc.engine.TraceLevel
import com.drduc.engine.TranslationMode
import com.drduc.engine.TranslationOrchestrator
import com.drduc.engine.TranslationProfile
import com.drduc.engine.TranslationRequest
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

enum class UiTextFieldType { BOOK_TITLE, AUTHOR, DESCRIPTION, CHAPTER_TITLE, EXPLORE_TAB, EXCERPT, SOURCE_METADATA }

class UiTranslationRepository(
    private val readerDao: ReaderDao,
    private val orchestrator: TranslationOrchestrator
) {
    private val memory = ConcurrentHashMap<String, String>()
    private val locks = ConcurrentHashMap<String, Mutex>()
    private val concurrency = Semaphore(MAX_CONCURRENT_TRANSLATIONS)

    suspend fun translate(text: String, fieldType: UiTextFieldType): String {
        val source = text.trim()
        if (!shouldTranslate(source)) return text
        val request = TranslationRequest(
            text = source,
            mode = TranslationMode.OFFLINE,
            translationProfile = TranslationProfile.DRDUC,
            traceLevel = TraceLevel.NONE
        )
        val identity = orchestrator.cacheIdentity(request)
        val cacheKey = sha256("ui|${fieldType.name}|${identity.cacheKey}")
        memory[cacheKey]?.let { return it }
        val lock = locks.getOrPut(cacheKey) { Mutex() }
        return try {
            lock.withLock {
                memory[cacheKey]?.let { return@withLock it }
                withContext(Dispatchers.IO) { readerDao.getUiTranslation(cacheKey) }?.let {
                    memory[cacheKey] = it.translatedText
                    return@withLock it.translatedText
                }
                concurrency.withPermit {
                    val translated = withContext(Dispatchers.Default) {
                        orchestrator.translate(request).displayText.ifBlank { source }
                    }
                    withContext(Dispatchers.IO) {
                        readerDao.putUiTranslation(
                            UiTranslationCacheEntity(
                                cacheKey = cacheKey,
                                fieldType = fieldType.name,
                                sourceText = source,
                                translatedText = translated,
                                graphVersion = identity.graphVersion,
                                overlayVersion = identity.overlayVersion,
                                dictionaryVersion = identity.dictionaryVersion,
                                hookVersion = identity.hookVersion
                            )
                        )
                    }
                    memory[cacheKey] = translated
                    translated
                }
            }
        } finally {
            locks.remove(cacheKey, lock)
        }
    }

    internal fun shouldTranslate(text: String): Boolean {
        if (text.isBlank() || text.length > MAX_UI_TEXT_CHARS) return false
        val trimmed = text.trimStart()
        if (trimmed.startsWith("http://", true) ||
            trimmed.startsWith("https://", true) ||
            trimmed.startsWith("@js:", true) ||
            trimmed.startsWith("<js>", true) ||
            trimmed.startsWith('{') ||
            trimmed.startsWith('[')
        ) return false
        return HAN_REGEX.containsMatchIn(text)
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val MAX_CONCURRENT_TRANSLATIONS = 2
        private const val MAX_UI_TEXT_CHARS = 12_000
        private val HAN_REGEX = Regex("[\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF]")
    }
}
