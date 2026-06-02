package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.drduc.legado.WebViewRuleEvaluator
import com.example.crawler.ExtensionParser
import com.example.crawler.ExtensionRepository
import com.example.crawler.JsEngine
import com.example.crawler.VbookSessionStore
import com.example.crawler.VbookCrawlerService
import com.example.data.AppDatabase
import com.example.data.VbookLibraryService
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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
class VbookExtensionIntegrationTest {
    private lateinit var context: Context
    private lateinit var database: AppDatabase
    private lateinit var server: NanoHTTPD
    private lateinit var baseUrl: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        ExtensionRepository.initialize(context, reload = true)
        ExtensionRepository.removeExtension(EXTENSION_ID)
        database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        server = object : NanoHTTPD("127.0.0.1", 0) {
            override fun serve(session: IHTTPSession): Response = when (session.uri) {
                "/plugin.zip" -> bytes(pluginZip(), "application/zip")
                "/search" -> json("""[{"name":"Tam Quốc Fixture","author":"La Quán Trung","link":"$baseUrl/book/1"}]""")
                "/book/1" -> json("""{"name":"Tam Quốc Fixture","author":"La Quán Trung","description":"Fixture online","ongoing":false}""")
                "/book/1/toc" -> json("""[{"name":"Chương 1","url":"$baseUrl/chapter/1"},{"name":"Chương 2","url":"$baseUrl/chapter/2"}]""")
                "/chapter/1" -> json("<p>我是第一章。<br>Đọc online hoạt động.</p>", "text/html; charset=utf-8")
                "/chapter/2" -> json("<p>第二章。<br>Tải offline hoạt động.</p>", "text/html; charset=utf-8")
                "/html-wrapper" -> json("""<div class="content">alpha alpha</div>""", "text/html; charset=utf-8")
                "/blocked-html" -> newFixedLengthResponse(Response.Status.FORBIDDEN, "text/plain", "blocked")
                "/login" -> json("logged-in").apply { addHeader("Set-Cookie", "sid=vbook-session; Path=/") }
                "/private" -> if (session.headers["cookie"]?.contains("sid=vbook-session") == true) json("private chapter") else newFixedLengthResponse(Response.Status.UNAUTHORIZED, "text/plain", "unauthorized")
                "/user-agent" -> json(session.headers["user-agent"].orEmpty(), "text/plain; charset=utf-8")
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "not found")
            }

            private fun json(text: String, mimeType: String = "application/json; charset=utf-8") =
                newFixedLengthResponse(Response.Status.OK, mimeType, text)

            private fun bytes(value: ByteArray, mimeType: String) =
                newFixedLengthResponse(Response.Status.OK, mimeType, ByteArrayInputStream(value), value.size.toLong())
        }
        server.start(NanoHTTPD.SOCKET_READ_TIMEOUT, false)
        baseUrl = "http://127.0.0.1:${server.listeningPort}"
    }

    @After
    fun tearDown() {
        ExtensionRepository.removeExtension(EXTENSION_ID)
        database.close()
        server.stop()
    }

    @Test
    fun installsExtensionCrawlsReadsOnlineAndDownloadsAllChapters() = runTest {
        val parsed = ExtensionParser.parseZip(ByteArrayInputStream(pluginZip()))
        ExtensionRepository.addExtension(parsed)
        ExtensionRepository.initialize(context, reload = true)
        assertEquals(EXTENSION_ID, ExtensionRepository.requireExtension(EXTENSION_ID).id)

        val crawler = VbookCrawlerService()
        val result = crawler.search(EXTENSION_ID, "tam quoc")
        assertEquals("Tam Quốc Fixture", result.single().title)
        val info = crawler.getBookInfo(EXTENSION_ID, result.single().url)
        assertEquals("La Quán Trung", info.author)
        val toc = crawler.getChapters(EXTENSION_ID, info.url)
        assertEquals(2, toc.size)
        assertTrue(crawler.getContent(EXTENSION_ID, toc.first().url).contains("Đọc online hoạt động."))

        val library = VbookLibraryService(database, crawler)
        val book = library.saveBook(EXTENSION_ID, info.url)
        val cachedToc = database.readerDao().getChapters(book.id)
        assertEquals(2, cachedToc.size)
        assertTrue(cachedToc.all { it.rawContent.isBlank() })
        val onlineChapter = library.ensureContent(book, cachedToc.first())
        assertTrue(onlineChapter.rawContent.contains("Đọc online hoạt động."))

        library.downloadBook(book.id, firstChapter = 1, lastChapter = 1)
        assertTrue(database.readerDao().getChapter(book.id, 1)?.rawContent?.contains("offline") == true)
        library.downloadBook(book.id)
        assertTrue(database.readerDao().getChapters(book.id).all { it.rawContent.isNotBlank() })
        assertTrue(database.readerDao().getDownloadTasks(book.id).all { it.status == "COMPLETED" })
    }

    @Test
    fun rejectsZipSlipEntry() {
        val malicious = zip(mapOf("../plugin.json" to "{}"))
        assertTrue(runCatching { ExtensionParser.parseZip(ByteArrayInputStream(malicious)) }.isFailure)
    }

    @Test
    fun persistsLoginCookieForRhinoFetchAndBackgroundCrawler() = runTest {
        val sessions = VbookSessionStore(context)
        val engine = JsEngine(sessions)
        engine.executeVbookSuspend("""function execute(){ return fetch("$baseUrl/login").text(); }""", null, "execute")
        val privateChapter = engine.executeVbookSuspend("""function execute(){ return fetch("$baseUrl/private").text(); }""", null, "execute")
        assertEquals("private chapter", privateChapter)
        assertTrue(sessions.cookieHeader(baseUrl).orEmpty().contains("sid=vbook-session"))
    }

    @Test
    fun exposesJsoupHtmlAsJavascriptStringForExtensionReplacements() = runTest {
        val result = JsEngine().executeVbookSuspend(
            """function execute(){ return fetch("$baseUrl/html-wrapper").html().select(".content").html().replace(/alpha/g, "beta"); }""",
            null,
            "execute"
        )
        assertEquals("beta beta", result)
    }

    @Test
    fun wrapsDirectHttpHtmlForNovelExtensions() = runTest {
        val result = JsEngine().executeVbookSuspend(
            """function execute(){ return Http.get("$baseUrl/html-wrapper").html().select(".content").html().replace(/alpha/g, "gamma"); }""",
            null,
            "execute"
        )
        assertEquals("gamma gamma", result)
    }

    @Test
    fun passesJsoupHtmlIntoJavascriptHelpersAsPrimitiveStrings() = runTest {
        val result = JsEngine().executeVbookSuspend(
            """function clean(html){ return html.replace(/alpha/g, "delta"); } function execute(){ return clean(Http.get("$baseUrl/html-wrapper").html().select(".content").html()); }""",
            null,
            "execute"
        )

        assertEquals("delta delta", result)
    }

    @Test
    fun normalizesHostArgumentsAndSupportsHtmlParseAndSleep() = runTest {
        val result = JsEngine().executeVbookSuspend(
            """function execute(url){ sleep(1); return Html.parse("<div class='content'>delta</div>").select(".content").text() + ":" + url.replace(/old/g, "new"); }""",
            null,
            "execute",
            "old-value"
        )
        assertEquals("delta:new-value", result)
    }

    @Test
    fun coercesExtensionArgumentsToJavascriptStringsForRegexReplacement() = runTest {
        val result = JsEngine().executeVbookSuspend(
            """function execute(url){ return url.replace(/^(?:https?:\/\/)?(?:www\.)?([^:\/\n?]+)/img, "https://fixed.example"); }""",
            null,
            "execute",
            "https://www.69shuba.com/txt/1"
        )

        assertEquals("https://fixed.example/txt/1", result)
    }

    @Test
    fun exposesEngineNewBrowserForRenderedChapterSources() = runTest {
        val evaluator = WebViewRuleEvaluator { _, _, _ ->
            "<html><body><div class='txtnav'>browser chapter</div></body></html>"
        }
        val result = JsEngine(webViewEvaluator = evaluator).executeVbookSuspend(
            """function execute(url){ var browser = Engine.newBrowser(); var doc = browser.launch(url, 4000); browser.close(); return doc.select(".txtnav").text(); }""",
            null,
            "execute",
            "$baseUrl/blocked-html"
        )
        assertEquals("browser chapter", result)
    }

    @Test
    fun wrapsDirectEngineBrowserDocumentsForJavascriptHelpers() = runTest {
        val result = JsEngine().executeVbookSuspend(
            """function clean(html){ return html.replace(/alpha/g, "browser"); } function execute(url){ var browser = Engine.newBrowser(); var doc = browser.launch(url, 4000); browser.close(); return clean(doc.select(".content").html()); }""",
            null,
            "execute",
            "$baseUrl/html-wrapper"
        )

        assertEquals("browser browser", result)
    }

    @Test
    fun fallsBackToWebViewForBlockedGetRequests() = runTest {
        val evaluator = WebViewRuleEvaluator { url, _, _ ->
            assertEquals("$baseUrl/blocked-html", url)
            "<html><body><ul id='article_list_content'><li>rendered list</li></ul></body></html>"
        }
        val result = JsEngine(webViewEvaluator = evaluator).executeVbookSuspend(
            """function execute(){ var response = fetch("$baseUrl/blocked-html"); return response.ok ? response.html().select("#article_list_content li").text() : "not-ok"; }""",
            null,
            "execute"
        )
        assertEquals("rendered list", result)
    }

    @Test
    fun reusesWebViewUserAgentForCrawlerHttpRequests() = runTest {
        val sessions = VbookSessionStore(context)
        sessions.putUserAgent("fixture-webview-user-agent")

        val result = JsEngine(sessions = sessions).executeVbookSuspend(
            """function execute(){ return Http.get("$baseUrl/user-agent").string(); }""",
            null,
            "execute"
        )

        assertEquals("fixture-webview-user-agent", result)
    }

    @Test
    fun rejectsEncryptedExtensionsWithActionableMessage() {
        val encrypted = zip(
            mapOf(
                "plugin.json" to """
                    {
                      "metadata": {
                        "name": "ENCRYPTED_FIXTURE",
                        "encrypt": true
                      },
                      "script": {
                        "home": "home.js"
                      }
                    }
                """.trimIndent(),
                "src/home.js" to "ciphertext"
            )
        )
        val error = runCatching { ExtensionParser.parseZip(ByteArrayInputStream(encrypted)) }.exceptionOrNull()
        assertTrue(error?.message.orEmpty().contains("decoder tương thích"))
    }

    private fun pluginZip(): ByteArray = zip(
        mapOf(
            "plugin.json" to """
                {
                  "metadata": {
                    "name": "VBOOK_FIXTURE",
                    "author": "Codex",
                    "version": 1,
                    "source": "$baseUrl",
                    "description": "Deterministic VBook integration fixture"
                  },
                  "script": {
                    "home": "home.js",
                    "gen": "gen.js",
                    "search": "search.js",
                    "detail": "detail.js",
                    "page": "page.js",
                    "toc": "toc.js",
                    "chap": "chap.js"
                  }
                }
            """.trimIndent(),
            "src/home.js" to """function execute(){return Response.success([{title:"Fixture",input:"$baseUrl/search",script:"gen.js"}]);}""",
            "src/gen.js" to """function execute(input,page){return Response.success(fetch(input).json());}""",
            "src/search.js" to """function execute(key,page){return Response.success(fetch("$baseUrl/search?keyword="+encodeURIComponent(key)).json());}""",
            "src/detail.js" to """function execute(url){return Response.success(fetch(url).json());}""",
            "src/page.js" to """function execute(url){return Response.success([url + "/toc"]);}""",
            "src/toc.js" to """function execute(url){return Response.success(fetch(url).json());}""",
            "src/chap.js" to """function execute(url){return Response.success(fetch(url).text());}"""
        )
    )

    private fun zip(files: Map<String, String>): ByteArray = ByteArrayOutputStream().use { output ->
        ZipOutputStream(output).use { zip ->
            files.forEach { (path, content) ->
                zip.putNextEntry(ZipEntry(path))
                zip.write(content.toByteArray(StandardCharsets.UTF_8))
                zip.closeEntry()
            }
        }
        output.toByteArray()
    }

    companion object {
        private const val EXTENSION_ID = "VBOOK_FIXTURE-Codex"
    }
}
