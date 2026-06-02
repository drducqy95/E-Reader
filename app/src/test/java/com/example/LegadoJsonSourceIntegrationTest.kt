package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.crawler.JsEngine
import com.example.crawler.LegadoCrawlerService
import com.example.crawler.LegadoJsonSourceRepository
import com.example.crawler.VbookSessionStore
import com.drduc.legado.AnalyzeUrl
import com.drduc.legado.WebViewRuleEvaluator
import com.example.data.AppDatabase
import com.example.data.OnlineLibraryService
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class LegadoJsonSourceIntegrationTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var server: NanoHTTPD
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response = when (session.uri) {
                "/legado-source.json" -> json(sourceJson())
                "/search" -> html(
                    """
                    <div class="book">
                      <a href="$baseUrl/book/1">Legado Fixture</a>
                      <span class="author">Fixture Author</span>
                    </div>
                    """.trimIndent()
                )
                "/book/1" -> html(
                    """
                    <h1>Legado Fixture</h1>
                    <span class="author">Fixture Author</span>
                    <div class="intro">Online source fixture</div>
                    <a class="toc" href="$baseUrl/book/1/toc">TOC</a>
                    """.trimIndent()
                )
                "/book/1/toc" -> html(
                    """
                    <ul>
                      <li class="chapter"><a href="$baseUrl/chapter/1">Chapter 1</a></li>
                      <li class="chapter"><a href="$baseUrl/chapter/2">Chapter 2</a></li>
                    </ul>
                    """.trimIndent()
                )
                "/chapter/1" -> html("""<div class="content">Online chapter one.<br>Readable now.</div>""")
                "/chapter/2" -> html("""<div class="content">Offline chapter two.<br>Downloaded now.</div>""")
                "/blocked" -> newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "blocked")
                "/user-agent" -> html(session.headers["user-agent"].orEmpty())
                "/login-redirect" -> newFixedLengthResponse(Response.Status.REDIRECT, "text/plain", "").apply {
                    addHeader("Location", "$baseUrl/private")
                    addHeader("Set-Cookie", "sid=redirect-session; Path=/; HttpOnly")
                }
                "/private" -> if (session.headers["cookie"]?.contains("sid=redirect-session") == true) {
                    html("private legado content")
                } else {
                    newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "unauthorized")
                }
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
            }

            private fun json(text: String) =
                newFixedLengthResponse(Response.Status.OK, "application/json; charset=utf-8", text)

            private fun html(text: String) =
                newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", text)
        }
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        baseUrl = "http://127.0.0.1:${server.listeningPort}"
    }

    @After
    fun tearDown() {
        database.close()
        server.stop()
    }

    @Test
    fun installsPersistsSearchesReadsOnlineAndDownloadsAllChapters() = runTest {
        val repository = LegadoJsonSourceRepository(context)
        val installed = repository.installFromUrl("$baseUrl/legado-source.json")
        assertEquals(baseUrl, installed.id)
        assertEquals(installed.id, LegadoJsonSourceRepository(context).require(installed.id).id)

        val sessions = VbookSessionStore(context)
        val crawler = LegadoCrawlerService(repository, JsEngine(sessions), sessions)
        val result = crawler.search(installed.id, "fixture")
        assertEquals("Legado Fixture", result.single().title)
        val info = crawler.getBookInfo(installed.id, result.single().url)
        assertEquals("Fixture Author", info.author)
        val toc = crawler.getChapters(installed.id, info.url)
        assertEquals(2, toc.size)
        assertTrue(crawler.getContent(installed.id, toc.first().url).contains("Readable now."))

        val library = OnlineLibraryService(database, crawler)
        val book = library.saveBook(installed.id, info.url)
        assertEquals("LEGADO_JSON", book.format)
        val cachedToc = database.readerDao().getChapters(book.id)
        assertTrue(cachedToc.all { it.rawContent.isBlank() })
        assertTrue(library.ensureContent(book, cachedToc.first()).rawContent.contains("Readable now."))
        library.downloadBook(book.id)
        assertTrue(database.readerDao().getChapters(book.id).all { it.rawContent.isNotBlank() })
        assertTrue(database.readerDao().getDownloadTasks(book.id).all { it.status == "COMPLETED" })

        repository.remove(installed.id)
    }

    @Test
    fun rejectsPlainHttpOutsideDevelopmentHosts() {
        val repository = LegadoJsonSourceRepository(context)
        assertTrue(runCatching { repository.installFromUrl("http://example.com/source.json") }.isFailure)
    }

    @Test
    fun fallsBackToSharedWebViewSessionForBlockedLegadoGetAndReusesUserAgent() = runTest {
        val sessions = VbookSessionStore(context)
        sessions.putUserAgent("fixture-webview-user-agent")
        val analyzeUrl = AnalyzeUrl(
            cookieStore = sessions,
            webViewEvaluator = WebViewRuleEvaluator { url, _, _ ->
                assertEquals("$baseUrl/blocked", url)
                "<html><body>rendered legado content</body></html>"
            },
            userAgent = sessions.userAgent()
        )

        assertTrue(analyzeUrl.fetch("$baseUrl/blocked").body.contains("rendered legado content"))
        assertEquals("fixture-webview-user-agent", analyzeUrl.fetch("$baseUrl/user-agent").body)
    }

    @Test
    fun keepsLoginCookieAcrossLegadoRedirectsAndLaterRequests() = runTest {
        val sessions = VbookSessionStore(context)
        val analyzeUrl = AnalyzeUrl(cookieStore = sessions)

        assertEquals("private legado content", analyzeUrl.fetch("$baseUrl/login-redirect").body)
        assertEquals("private legado content", analyzeUrl.fetch("$baseUrl/private").body)
        assertTrue(sessions.cookieHeader("$baseUrl/private").orEmpty().contains("sid=redirect-session"))
    }

    private fun sourceJson(): String =
        """
        {
          "bookSourceName": "LEGADO_FIXTURE",
          "bookSourceUrl": "$baseUrl",
          "searchUrl": "$baseUrl/search?key={{key}}",
          "ruleSearch": {
            "bookList": "div.book",
            "name": "a@text",
            "author": ".author@text",
            "bookUrl": "a@href"
          },
          "ruleBookInfo": {
            "name": "h1@text",
            "author": ".author@text",
            "intro": ".intro@text",
            "tocUrl": "a.toc@href"
          },
          "ruleToc": {
            "chapterList": "li.chapter",
            "chapterName": "a@text",
            "chapterUrl": "a@href"
          },
          "ruleContent": {
            "content": "div.content@html"
          }
        }
        """.trimIndent()
}
