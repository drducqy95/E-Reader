package com.example

import android.app.Application
import android.content.Intent
import androidx.core.content.ContextCompat
import com.example.data.AppDatabase
import com.example.data.BookRepository
import com.example.data.SettingsRepository
import com.example.data.dataStore
import com.example.data.GraphPackageManager
import com.example.data.OnlineProviderConfigStore
import com.example.data.OpenAiCompatibleRefinementProvider
import com.example.crawler.ExtensionRepository
import com.example.crawler.VbookCrawlerService
import com.example.crawler.JsEngine
import com.example.crawler.VbookSessionStore
import com.example.crawler.WebViewPool
import com.example.crawler.LegadoCrawlerService
import com.example.crawler.LegadoJsonSourceRepository
import com.example.crawler.CompositeOnlineCrawlerService
import com.example.data.OnlineLibraryService
import com.example.data.ChapterContentStore
import com.example.data.DictionaryPackageManager
import com.example.data.DownloadCoordinator
import com.example.data.UiTranslationRepository
import com.drduc.engine.DrDucGraphTranslationEngine
import com.drduc.engine.TranslationOrchestrator
import com.drduc.engine.graph.LegadoDictionaryCandidateStore
import com.drduc.engine.graph.MobileGraphStore
import com.drduc.engine.graph.OverlayGraphStore
import java.io.File
import com.example.web.LocalWebService

class EReaderApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        ExtensionRepository.initialize(this)
        if (LocalWebService.isEnabled(this)) {
            runCatching {
                ContextCompat.startForegroundService(this, Intent(this, LocalWebService::class.java))
            }
        }
    }

    val database by lazy { AppDatabase.getDatabase(this) }
    val bookRepository by lazy { BookRepository(database.bookDao()) }
    val settingsRepository by lazy { SettingsRepository(dataStore) }
    val graphPackageManager by lazy { GraphPackageManager(this) }
    val onlineProviderConfigStore by lazy { OnlineProviderConfigStore(this) }
    val vbookSessionStore by lazy { VbookSessionStore(this) }
    val webViewPool by lazy { WebViewPool(this, vbookSessionStore) }
    val jsEngine by lazy { JsEngine(vbookSessionStore, webViewPool) }
    val vbookCrawlerService by lazy { VbookCrawlerService(jsEngine) }
    val legadoSourceRepository by lazy { LegadoJsonSourceRepository(this) }
    val legadoCrawlerService by lazy { LegadoCrawlerService(legadoSourceRepository, jsEngine, vbookSessionStore, webViewPool) }
    val onlineCrawlerService by lazy { CompositeOnlineCrawlerService(vbookCrawlerService, legadoCrawlerService) }
    val chapterContentStore by lazy { ChapterContentStore(this) }
    val onlineLibraryService by lazy { OnlineLibraryService(database, onlineCrawlerService, chapterContentStore) }
    val downloadCoordinator by lazy { DownloadCoordinator(this, database, settingsRepository) }
    val vbookLibraryService get() = onlineLibraryService
    val overlayGraphStore by lazy { OverlayGraphStore(this) }
    val legadoDictionaryStore by lazy { LegadoDictionaryCandidateStore(this) }
    val dictionaryPackageManager by lazy {
        DictionaryPackageManager(this, database.readerDao(), legadoDictionaryStore)
    }
    val translationOrchestrator by lazy {
        val graph = File(noBackupFilesDir, "drduc/translation_graph.mobile.sqlite")
        TranslationOrchestrator(
            DrDucGraphTranslationEngine(
                graphStore = MobileGraphStore(graph),
                overlayStore = overlayGraphStore,
                legadoStore = legadoDictionaryStore
            ),
            OpenAiCompatibleRefinementProvider(onlineProviderConfigStore)
        )
    }
    val uiTranslationRepository by lazy { UiTranslationRepository(database.readerDao(), translationOrchestrator) }
}
