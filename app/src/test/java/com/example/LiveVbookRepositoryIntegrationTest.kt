package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.crawler.ExtensionParser
import com.example.crawler.ExtensionRepository
import com.example.crawler.VbookBookSummary
import com.example.crawler.VbookCrawlerService
import com.example.data.AppDatabase
import com.example.data.VbookLibraryService
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LiveVbookRepositoryIntegrationTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private var installedExtensionId: String? = null

    @Before
    fun setUp() {
        assumeTrue("Set RUN_LIVE_VBOOK_TESTS=true to exercise the public VBook repository.", liveTestsEnabled())
        context = ApplicationProvider.getApplicationContext()
        ExtensionRepository.initialize(context, reload = true)
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() {
        installedExtensionId?.let(ExtensionRepository::removeExtension)
        if (::database.isInitialized) database.close()
    }

    @Test
    fun downloadsRepositoryInstallsVnexpressReadsOnlineAndCachesArticle() = runTest {
        val extensions = withContext(Dispatchers.IO) {
            ExtensionParser.parseUrlOrRepo(REPOSITORY_URL)
        }
        val extension = extensions.firstOrNull { it.name.equals(SOURCE_NAME, ignoreCase = true) }
            ?: error("Source $SOURCE_NAME was not installable from $REPOSITORY_URL")
        assertTrue(extension.scripts.containsKey("home"))
        assertTrue(extension.scripts.containsKey("chap"))

        installedExtensionId = extension.id
        ExtensionRepository.addExtension(extension)
        ExtensionRepository.initialize(context, reload = true)
        assertEquals(extension.id, ExtensionRepository.requireExtension(extension.id).id)

        val crawler = VbookCrawlerService()
        val homeTabs = crawler.home(extension.id)
        assertTrue("Expected VnExpress home tabs", homeTabs.isNotEmpty())
        var articles = emptyList<VbookBookSummary>()
        for (tab in homeTabs) {
            articles = crawler.list(extension.id, tab.script, tab.input, "1")
            if (articles.isNotEmpty()) break
        }
        if (articles.isEmpty()) error("VnExpress home tabs did not return any article")
        val article = articles.first { it.url.startsWith("https://") }
        val detail = crawler.getBookInfo(extension.id, article.url)
        assertTrue("Expected a live article title", detail.title.isNotBlank())

        val toc = crawler.getChapters(extension.id, article.url)
        assertTrue("Expected at least one article chapter", toc.isNotEmpty())
        val content = crawler.getContent(extension.id, toc.first().url)
        assertTrue("Expected readable live article content", content.length >= MIN_CONTENT_LENGTH)

        val library = VbookLibraryService(database, crawler)
        val book = library.saveBook(extension.id, article.url)
        val cachedChapter = database.readerDao().getChapters(book.id).first()
        assertTrue(cachedChapter.rawContent.isBlank())
        val onlineChapter = library.ensureContent(book, cachedChapter)
        assertTrue(onlineChapter.rawContent.length >= MIN_CONTENT_LENGTH)
        library.downloadBook(book.id)
        assertTrue(database.readerDao().getChapters(book.id).all { it.rawContent.isNotBlank() })
        assertTrue(database.readerDao().getDownloadTasks(book.id).all { it.status == "COMPLETED" })
    }

    private fun liveTestsEnabled(): Boolean =
        System.getenv("RUN_LIVE_VBOOK_TESTS").equals("true", ignoreCase = true) ||
            System.getProperty("runLiveVbookTests").equals("true", ignoreCase = true)

    companion object {
        private const val REPOSITORY_URL = "https://raw.githubusercontent.com/duongden/vbook/main/plugin.json"
        private const val SOURCE_NAME = "vnexpress"
        private const val MIN_CONTENT_LENGTH = 120
    }
}
