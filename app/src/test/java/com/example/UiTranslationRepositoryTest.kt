package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.drduc.engine.OfflineTranslationEngine
import com.drduc.engine.TranslationOrchestrator
import com.drduc.engine.TranslationRequest
import com.drduc.engine.TranslationResult
import com.drduc.engine.TranslationRuntimeStatus
import com.example.data.AppDatabase
import com.example.data.UiTextFieldType
import com.example.data.UiTranslationRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class UiTranslationRepositoryTest {
    @Test
    fun translatesHanTextCachesAcrossRepositoriesAndSkipsUnsafeText() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        val engine = FakeEngine()
        try {
            val first = UiTranslationRepository(database.readerDao(), TranslationOrchestrator(engine))
            assertEquals("VI:你好", first.translate("你好", UiTextFieldType.BOOK_TITLE))
            assertEquals(1, engine.calls)

            val second = UiTranslationRepository(database.readerDao(), TranslationOrchestrator(engine))
            assertEquals("VI:你好", second.translate("你好", UiTextFieldType.BOOK_TITLE))
            assertEquals("https://example.com/你好", second.translate("https://example.com/你好", UiTextFieldType.SOURCE_METADATA))
            assertEquals("@js:你好", second.translate("@js:你好", UiTextFieldType.SOURCE_METADATA))
            assertEquals(1, engine.calls)

            database.openHelper.readableDatabase.query("SELECT COUNT(*) FROM ui_translation_cache").use {
                it.moveToFirst()
                assertEquals(1, it.getInt(0))
            }
        } finally {
            database.close()
        }
    }

    private class FakeEngine : OfflineTranslationEngine {
        var calls = 0

        override suspend fun translate(request: TranslationRequest): TranslationResult {
            calls++
            return TranslationResult(rawText = request.text, offlineText = "VI:${request.text}")
        }

        override suspend fun translateCode(text: String) = text

        override fun cacheKey(request: TranslationRequest) = "ui:${request.text}"

        override fun runtimeStatus() = TranslationRuntimeStatus(
            graphVersion = "fixture-graph",
            overlayVersion = 2,
            hookVersion = "fixture-hook",
            dictionaryVersion = "fixture-dictionary"
        )
    }
}
