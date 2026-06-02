package com.example.web

import android.content.Context
import android.content.Intent
import android.util.Base64
import com.drduc.engine.TraceLevel
import com.drduc.engine.TranslationMode
import com.drduc.engine.TranslationOrchestrator
import com.drduc.engine.TranslationRequest
import com.drduc.engine.graph.OverlayRuntimeStatus
import com.drduc.web.WebApi
import com.drduc.web.WebApiResponse
import com.example.data.AppDatabase
import com.example.data.GraphDownloadScheduler
import com.example.data.GraphPackageManager
import com.example.data.OnlineProviderConfig
import com.example.data.ProductionExpansionConfig
import com.example.data.isOnlineBook
import com.example.EReaderApplication
import com.example.crawler.ExtensionData
import com.example.crawler.ExtensionParser
import com.example.crawler.ExtensionRepository
import com.example.crawler.VbookBookInfo
import com.example.crawler.VbookBookSummary
import com.example.crawler.LegadoInstalledSource
import com.example.crawler.WebViewLoginActivity
import com.example.data.VbookDownloadScheduler
import com.example.data.DictionaryType
import com.example.data.Bookmark
import com.example.data.ReaderNote
import com.example.data.UiTextFieldType
import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.flow.first

private const val MAX_BROWSER_DEBUG_CHARS = 1_000_000
private const val MAX_BROWSER_DEBUG_SCRIPT_CHARS = 10_000

class EReaderWebApi(
    private val context: Context,
    private val database: AppDatabase,
    private val translator: TranslationOrchestrator,
    private val graphPackageManager: GraphPackageManager
) : WebApi {
    private val app get() = context.applicationContext as EReaderApplication

    override fun handle(session: NanoHTTPD.IHTTPSession): WebApiResponse? = runBlocking {
        dynamicRoute(session)?.let { return@runBlocking it }
        when (session.uri) {
            "/getBookshelf" -> {
                val translated = wantsTranslation(session)
                val books = database.bookDao().getAllBooksSnapshot()
                val response = if (translated) coroutineScope {
                    books.map { async { bookJson(it, translated = true) } }.awaitAll()
                } else {
                    books.map { bookJson(it, translated = false) }
                }
                ok(JSONArray(response))
            }
            "/getChapterList" -> {
                val book = resolveBook(session)
                if (book == null) error("Missing or unknown book id/url") else {
                    val chapters = if (book.isOnlineBook()) app.onlineLibraryService.ensureToc(book) else database.readerDao().getChapters(book.id)
                    val translated = wantsTranslation(session)
                    val response = if (translated) coroutineScope {
                        chapters.map { async { chapterJson(it, translated = true, bookUrl = book.uriString) } }.awaitAll()
                    } else {
                        chapters.map { chapterJson(it, translated = false, bookUrl = book.uriString) }
                    }
                    ok(JSONArray(response))
                }
            }
            "/getBookContent" -> {
                val book = resolveBook(session)
                val index = session.parameters["index"]?.firstOrNull()?.toIntOrNull()
                val chapter = if (book == null || index == null) null else {
                    database.readerDao().getChapters(book.id).firstOrNull { it.chapterIndex == index }
                }
                if (book == null || chapter == null) error("Chapter not found") else {
                    val resolved = if (book.isOnlineBook()) app.onlineLibraryService.ensureContent(book, chapter) else chapter
                    val content = if (wantsTranslation(session)) {
                        translator.translate(TranslationRequest(text = resolved.rawContent, mode = TranslationMode.OFFLINE)).displayText
                    } else {
                        resolved.rawContent
                    }
                    ok(content)
                }
            }
            "/getBookSources" -> onlineSources()
            "/api/v1/vbook/extensions" -> ok(JSONArray(ExtensionRepository.extensions.value.map(::extensionJson)))
            "/api/v1/legado/sources" -> ok(JSONArray(app.legadoSourceRepository.sources.value.map(::legadoSourceJson)))
            "/getReadConfig" -> readerSettings(session)
            "/saveReadConfig" -> readerSettings(session)
            "/api/v1/reader/settings" -> readerSettings(session)
            "/api/v1/dictionaries" -> dictionaries()
            "/api/v1/dictionaries/import" -> importDictionary(session)
            "/api/v1/dictionaries/download" -> downloadDictionary(session)
            "/api/v1/dictionaries/delete" -> deleteDictionary(session)
            "/api/v1/translate" -> translate(session, includeTrace = false)
            "/api/v1/ui/translate" -> translateUiText(session)
            "/api/v1/translation/trace" -> translate(session, includeTrace = true)
            "/api/v1/graph/status" -> graphStatus()
            "/api/v1/overlay/status" -> overlayStatus()
            "/api/v1/overlay/import" -> importOverlayDelta(session)
            "/api/v1/graph/download" -> startGraphDownload(session)
            "/api/v1/graph/download-production" -> startProductionGraphDownload(session)
            "/api/v1/graph/delete" -> {
                if (graphPackageManager.deleteGraph()) ok("deleted") else error("Could not delete graph")
            }
            "/api/v1/downloads" -> downloads()
            "/api/v1/online-provider" -> onlineProvider(session)
            "/api/v1/vbook/extensions/install" -> installVbookExtension(session)
            "/api/v1/vbook/extensions/delete" -> deleteVbookExtension(session)
            "/api/v1/vbook/extensions/audit" -> auditVbookExtensions(session)
            "/api/v1/vbook/browser/open" -> openVbookBrowser(session)
            "/api/v1/vbook/browser/render" -> renderVbookBrowser(session)
            "/api/v1/vbook/session" -> vbookSession(session)
            "/api/v1/vbook/session/clear" -> clearVbookSession(session)
            "/api/v1/vbook/home" -> vbookHome(session)
            "/api/v1/vbook/list" -> vbookList(session)
            "/api/v1/vbook/search" -> searchVbook(session)
            "/api/v1/vbook/book" -> vbookInfo(session)
            "/api/v1/vbook/read" -> saveVbook(session)
            "/api/v1/vbook/download" -> downloadVbook(session)
            "/api/v1/vbook/downloads" -> vbookDownloads(session)
            "/api/v1/legado/sources/install" -> installLegadoSource(session)
            "/api/v1/legado/sources/delete" -> deleteLegadoSource(session)
            "/api/v1/legado/home" -> vbookHome(session)
            "/api/v1/legado/list" -> vbookList(session)
            "/api/v1/legado/search" -> searchVbook(session)
            "/api/v1/legado/book" -> vbookInfo(session)
            "/api/v1/legado/read" -> saveVbook(session)
            "/api/v1/legado/download" -> downloadVbook(session)
            "/api/v1/legado/downloads" -> vbookDownloads(session)
            "/api/v1/legado/session" -> vbookSession(session)
            "/api/v1/legado/session/clear" -> clearVbookSession(session)
            else -> null
        }
    }

    private suspend fun dynamicRoute(session: NanoHTTPD.IHTTPSession): WebApiResponse? {
        val parts = session.uri.trim('/').split('/')
        if (parts.size == 5 && parts.take(3) == listOf("api", "v1", "books")) {
            val bookId = parts[3].toIntOrNull() ?: return error("Invalid book id")
            return when (parts[4]) {
                "chapters" -> {
                    val book = database.bookDao().getBookSnapshot(bookId) ?: return error("Book not found")
                    val chapters = if (book.isOnlineBook()) app.onlineLibraryService.ensureToc(book) else database.readerDao().getChapters(bookId)
                    ok(JSONArray(chapters.map { chapterJson(it, translated = false, bookUrl = book.uriString) }))
                }
                "downloads" -> {
                    if (session.method == NanoHTTPD.Method.POST) {
                        val body = requestJson(session)
                        val chapters = database.readerDao().getChapters(bookId)
                        if (chapters.isEmpty()) return error("Book does not contain chapters")
                        val first = body.optInt("firstChapter", chapters.first().chapterIndex)
                        val last = body.optInt("lastChapter", chapters.last().chapterIndex)
                        ok(downloadBatchJson(app.downloadCoordinator.enqueue(bookId, first, last)))
                    } else {
                        ok(JSONArray(app.downloadCoordinator.list(bookId).map(::downloadBatchJson)))
                    }
                }
                "bookmarks" -> bookmarks(session, bookId)
                "notes" -> notes(session, bookId)
                else -> null
            }
        }
        if (parts.size == 5 && parts.take(3) == listOf("api", "v1", "downloads")) {
            val batchId = parts[3]
            return runCatching {
                when (parts[4]) {
                    "pause" -> app.downloadCoordinator.pause(batchId)
                    "resume" -> app.downloadCoordinator.resume(batchId)
                    "cancel" -> app.downloadCoordinator.cancel(batchId)
                    else -> return null
                }
                "ok"
            }.fold(::ok) { error(it.message ?: "Could not update download") }
        }
        if (parts.size == 5 && parts[4] == "delete") {
            return when (parts.take(3)) {
                listOf("api", "v1", "bookmarks") -> {
                    val id = parts[3].toLongOrNull() ?: return error("Invalid id")
                    database.readerDao().deleteBookmark(id)
                    ok("deleted")
                }
                listOf("api", "v1", "notes") -> {
                    val id = parts[3].toLongOrNull() ?: return error("Invalid id")
                    database.readerDao().deleteNote(id)
                    ok("deleted")
                }
                else -> null
            }
        }
        return null
    }

    private suspend fun bookmarks(session: NanoHTTPD.IHTTPSession, bookId: Int): WebApiResponse {
        if (session.method == NanoHTTPD.Method.POST) {
            val body = requestJson(session)
            database.readerDao().putBookmark(
                Bookmark(
                    bookId = bookId,
                    chapterIndex = body.optInt("chapterIndex"),
                    paragraphAnchor = body.optInt("paragraphAnchor"),
                    excerpt = body.optString("excerpt")
                )
            )
        }
        return ok(JSONArray(database.readerDao().getBookmarks(bookId).map {
            JSONObject().put("id", it.id).put("chapterIndex", it.chapterIndex).put("paragraphAnchor", it.paragraphAnchor).put("excerpt", it.excerpt)
        }))
    }

    private suspend fun notes(session: NanoHTTPD.IHTTPSession, bookId: Int): WebApiResponse {
        if (session.method == NanoHTTPD.Method.POST) {
            val body = requestJson(session)
            database.readerDao().putNote(
                ReaderNote(
                    bookId = bookId,
                    chapterIndex = body.optInt("chapterIndex"),
                    paragraphAnchor = body.optInt("paragraphAnchor"),
                    excerpt = body.optString("excerpt"),
                    content = body.optString("content")
                )
            )
        }
        return ok(JSONArray(database.readerDao().getNotes(bookId).map {
            JSONObject().put("id", it.id).put("chapterIndex", it.chapterIndex).put("paragraphAnchor", it.paragraphAnchor)
                .put("excerpt", it.excerpt).put("content", it.content)
        }))
    }

    private fun installVbookExtension(session: NanoHTTPD.IHTTPSession): WebApiResponse {
        if (session.method != NanoHTTPD.Method.POST) return error("POST required")
        val url = requestJson(session).optString("url")
        return runCatching {
            val extensions = ExtensionParser.parseUrlOrRepo(url)
            extensions.firstOrNull { it.id == "error" }?.let { throw IllegalArgumentException(it.description) }
            extensions.forEach(ExtensionRepository::addExtension)
            JSONArray(extensions.map(::extensionJson))
        }.fold(::ok) { error(it.message ?: "Could not install extension") }
    }

    private fun auditVbookExtensions(session: NanoHTTPD.IHTTPSession): WebApiResponse {
        if (session.method != NanoHTTPD.Method.POST) return error("POST required")
        val url = requestJson(session).optString("url")
        return runCatching {
            val report = ExtensionParser.inspectUrlOrRepo(url)
            JSONObject()
                .put("installable", JSONArray(report.extensions.map(::extensionJson)))
                .put("issues", JSONArray(report.issues.map {
                    JSONObject().put("path", it.path).put("message", it.message)
                }))
        }.fold(::ok) { error(it.message ?: "Could not audit extensions") }
    }

    private fun deleteVbookExtension(session: NanoHTTPD.IHTTPSession): WebApiResponse {
        if (session.method != NanoHTTPD.Method.POST) return error("POST required")
        return runCatching {
            val sourceId = requestJson(session).getString("sourceId")
            ExtensionRepository.removeExtension(sourceId)
            "deleted"
        }.fold(::ok) { error(it.message ?: "Could not delete extension") }
    }

    private suspend fun renderVbookBrowser(session: NanoHTTPD.IHTTPSession): WebApiResponse {
        if (session.method != NanoHTTPD.Method.POST) return error("POST required")
        return runCatching {
            val body = requestJson(session)
            val url = body.getString("url")
            val script = body.optString("script").ifBlank { "document.documentElement.outerHTML" }
            require(url.startsWith("https://") || url.startsWith("http://")) { "Only HTTP(S) URLs are supported" }
            require(script.length <= MAX_BROWSER_DEBUG_SCRIPT_CHARS) { "Browser debug script is too large" }
            val result = app.webViewPool.evaluate(url, "", script)
            JSONObject()
                .put("url", url)
                .put("length", result.length)
                .put("result", result.take(MAX_BROWSER_DEBUG_CHARS))
                .put("truncated", result.length > MAX_BROWSER_DEBUG_CHARS)
        }.fold(::ok) { error(it.message ?: "Could not render URL") }
    }

    private fun openVbookBrowser(session: NanoHTTPD.IHTTPSession): WebApiResponse {
        if (session.method != NanoHTTPD.Method.POST) return error("POST required")
        return runCatching {
            val body = requestJson(session)
            val url = body.getString("url")
            require(url.startsWith("https://") || url.startsWith("http://")) { "Only HTTP(S) URLs are supported" }
            context.startActivity(
                Intent(context, WebViewLoginActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    .putExtra(WebViewLoginActivity.EXTRA_SOURCE_ID, body.optString("sourceId"))
                    .putExtra(WebViewLoginActivity.EXTRA_URL, url)
            )
            "opened"
        }.fold(::ok) { error(it.message ?: "Could not open source browser") }
    }

    private fun vbookSession(session: NanoHTTPD.IHTTPSession): WebApiResponse {
        val url = session.parameters["url"]?.firstOrNull().orEmpty()
        if (url.isBlank()) return error("Missing url")
        val status = app.vbookSessionStore.status(url)
        return ok(JSONObject()
            .put("host", status.host)
            .put("cookieCount", status.cookieCount)
            .put("secureCookieCount", status.secureCookieCount)
            .put("hasUserAgent", status.hasUserAgent))
    }

    private suspend fun clearVbookSession(session: NanoHTTPD.IHTTPSession): WebApiResponse {
        if (session.method != NanoHTTPD.Method.POST) return error("POST required")
        val url = requestJson(session).optString("url")
        if (url.isBlank()) return error("Missing url")
        withContext(Dispatchers.Main.immediate) { app.webViewPool.clearSite(url) }
        return ok("cleared")
    }

    private suspend fun searchVbook(session: NanoHTTPD.IHTTPSession): WebApiResponse {
        if (session.method != NanoHTTPD.Method.POST) return error("POST required")
        val body = requestJson(session)
        return runCatching {
            val translated = body.optBoolean("translate", false)
            JSONArray(app.onlineCrawlerService.search(body.getString("sourceId"), body.optString("keyword"), body.optString("page", "1")).map {
                summaryJson(it, translated)
            })
        }.fold(::ok) { error(it.message ?: "Search failed") }
    }

    private suspend fun vbookHome(session: NanoHTTPD.IHTTPSession): WebApiResponse {
        if (session.method != NanoHTTPD.Method.POST) return error("POST required")
        val body = requestJson(session)
        val sourceId = body.getString("sourceId")
        return runCatching {
            val translated = body.optBoolean("translate", false)
            JSONArray(app.onlineCrawlerService.home(sourceId).map {
                JSONObject()
                    .put("title", translateUi(it.title, UiTextFieldType.EXPLORE_TAB, translated))
                    .put("input", it.input)
                    .put("script", it.script)
            })
        }.fold(::ok) { error(it.message ?: "Could not load source home") }
    }

    private suspend fun vbookList(session: NanoHTTPD.IHTTPSession): WebApiResponse {
        if (session.method != NanoHTTPD.Method.POST) return error("POST required")
        val body = requestJson(session)
        return runCatching {
            val translated = body.optBoolean("translate", false)
            JSONArray(app.onlineCrawlerService.list(
                body.getString("sourceId"),
                body.getString("script"),
                body.optString("input"),
                body.optString("page", "1")
            ).map { summaryJson(it, translated) })
        }.fold(::ok) { error(it.message ?: "Could not load source list") }
    }

    private suspend fun vbookInfo(session: NanoHTTPD.IHTTPSession): WebApiResponse {
        if (session.method != NanoHTTPD.Method.POST) return error("POST required")
        val body = requestJson(session)
        return runCatching {
            val sourceId = body.getString("sourceId")
            val url = body.getString("url")
            val info = app.onlineCrawlerService.getBookInfo(sourceId, url)
            val chapters = app.onlineCrawlerService.getChapters(sourceId, url)
            val translated = body.optBoolean("translate", false)
            val chapterJson = if (translated) coroutineScope {
                chapters.map { chapter ->
                    async {
                        JSONObject()
                            .put("index", chapter.index)
                            .put("name", translateUi(chapter.title, UiTextFieldType.CHAPTER_TITLE, enabled = true))
                            .put("url", chapter.url)
                    }
                }.awaitAll()
            } else {
                chapters.map {
                    JSONObject().put("index", it.index).put("name", it.title).put("url", it.url)
                }
            }
            infoJson(info, translated).put("chapters", JSONArray(chapterJson))
        }.fold(::ok) { error(it.message ?: "Could not load book") }
    }

    private suspend fun saveVbook(session: NanoHTTPD.IHTTPSession): WebApiResponse {
        if (session.method != NanoHTTPD.Method.POST) return error("POST required")
        val body = requestJson(session)
        return runCatching {
            bookJson(app.onlineLibraryService.saveBook(body.getString("sourceId"), body.getString("url")), body.optBoolean("translate", false))
        }.fold(::ok) { error(it.message ?: "Could not save book") }
    }

    private suspend fun downloadVbook(session: NanoHTTPD.IHTTPSession): WebApiResponse {
        if (session.method != NanoHTTPD.Method.POST) return error("POST required")
        val body = requestJson(session)
        return runCatching {
            val book = app.onlineLibraryService.saveBook(body.getString("sourceId"), body.getString("url"))
            JSONObject().put("bookId", book.id).put("workId", VbookDownloadScheduler.enqueue(context, book.id).toString())
        }.fold(::ok) { error(it.message ?: "Could not enqueue book download") }
    }

    private fun vbookDownloads(session: NanoHTTPD.IHTTPSession): WebApiResponse {
        val bookId = session.parameters["bookId"]?.firstOrNull()?.toIntOrNull()
            ?: return error("Missing bookId")
        return ok(JSONArray(VbookDownloadScheduler.snapshots(context, bookId).map {
            JSONObject()
                .put("id", it.id.toString())
                .put("state", it.state.name)
                .put("downloadedChapters", it.downloadedChapters)
                .put("totalChapters", it.totalChapters)
                .put("error", it.error)
        }))
    }

    private fun installLegadoSource(session: NanoHTTPD.IHTTPSession): WebApiResponse {
        if (session.method != NanoHTTPD.Method.POST) return error("POST required")
        val body = requestJson(session)
        return runCatching {
            if (body.optString("sourceJson").isNotBlank()) {
                app.legadoSourceRepository.install(body.getString("sourceJson"))
            } else {
                app.legadoSourceRepository.installFromUrl(body.getString("url"))
            }
        }.fold({ ok(legadoSourceJson(it)) }) { error(it.message ?: "Could not install Legado source") }
    }

    private fun deleteLegadoSource(session: NanoHTTPD.IHTTPSession): WebApiResponse {
        if (session.method != NanoHTTPD.Method.POST) return error("POST required")
        return runCatching {
            val id = requestJson(session).getString("sourceId")
            app.legadoSourceRepository.remove(id)
            "deleted"
        }.fold(::ok) { error(it.message ?: "Could not delete Legado source") }
    }

    private fun onlineSources(): WebApiResponse = ok(JSONArray(
        ExtensionRepository.extensions.value.map(::extensionJson) +
            app.legadoSourceRepository.sources.value.map(::legadoSourceJson)
    ))

    private suspend fun translate(session: NanoHTTPD.IHTTPSession, includeTrace: Boolean): WebApiResponse {
        val body = requestJson(session)
        val mode = runCatching { TranslationMode.valueOf(body.optString("mode", "OFFLINE")) }.getOrDefault(TranslationMode.OFFLINE)
        val result = translator.translate(
            TranslationRequest(
                text = body.optString("text"),
                projectId = body.optString("projectId").takeIf(String::isNotBlank),
                mode = mode,
                traceLevel = if (includeTrace) TraceLevel.FULL else TraceLevel.SUMMARY
            )
        )
        val data = JSONObject()
                .put("text", result.displayText)
                .put("offlineText", result.offlineText)
                .put("refinedText", result.refinedText)
                .put("warning", result.warning)
                .put("graphVersion", result.graphVersion)
                .put("overlayVersion", result.overlayVersion)
                .put("cacheKey", result.cacheKey)
        if (includeTrace) {
            data.put("trace", JSONArray(result.trace?.segments.orEmpty().map {
                JSONObject()
                    .put("source", it.sourceText)
                    .put("target", it.targetText)
                    .put("candidateSource", it.candidate.source.name)
                    .put("nodeId", it.candidate.nodeId)
                    .put("reason", it.candidate.reason)
                    .put("surfaceNote", it.candidate.surfaceNote)
                    .put("alternatives", JSONArray(it.candidate.alternatives))
                    .put("score", it.candidate.score)
                    .put("universe", it.candidate.universe)
                    .put("contextScore", it.candidate.contextScore)
                    .put("penalty", it.candidate.penalty)
                    .put("posTag", it.candidate.posTag)
                    .put("posSub", it.candidate.posSub)
                    .put("contextMarkers", JSONArray(it.candidate.contextMarkers))
                    .put("negativeContextMarkers", JSONArray(it.candidate.negativeContextMarkers))
                    .put("coOccurringEntities", JSONArray(it.candidate.coOccurringEntities))
                    .put("appliedRule", it.candidate.appliedRule)
                    .put("ruleGroup", it.candidate.ruleGroup)
                    .put("matchScore", it.candidate.matchScore)
            }))
        }
        return ok(data)
    }

    private fun graphStatus(): WebApiResponse {
        val packageStatus = graphPackageManager.status()
        val runtimeStatus = translator.runtimeStatus()
        return ok(
            JSONObject()
                .put("runtime", "kotlin")
                .put("pythonInApk", false)
                .put("installed", packageStatus.installed)
                .put("filePath", packageStatus.filePath)
                .put("bytes", packageStatus.bytes)
                .put("sha256", packageStatus.sha256)
                .put("graphVersion", packageStatus.graphVersion)
                 .put("runtimeGraphVersion", runtimeStatus.graphVersion)
                 .put("overlayVersion", runtimeStatus.overlayVersion)
                 .put("installedAt", packageStatus.installedAt)
                 .put("source", packageStatus.source)
                 .put("contextRows", packageStatus.contextRows)
                 .put("universeTerms", packageStatus.universeTerms)
                 .put("grammarNodes", packageStatus.grammarNodes)
                 .put("cooccurrenceRows", packageStatus.cooccurrenceRows)
                 .put("contextUniverseAvailable", packageStatus.contextUniverseAvailable)
                 .put("warnings", JSONArray(packageStatus.warnings))
         )
     }

    private fun overlayStatus(): WebApiResponse = ok(overlayStatusJson(app.overlayGraphStore.status()))

    private fun importOverlayDelta(session: NanoHTTPD.IHTTPSession): WebApiResponse {
        if (session.method != NanoHTTPD.Method.POST) return error("POST required")
        val body = requestJson(session)
        val manifest = body.optJSONObject("manifest") ?: body
        return runCatching {
            app.overlayGraphStore.importReviewedDelta(manifest, translator.runtimeStatus().graphVersion)
        }.fold({ ok(overlayStatusJson(it)) }) { error(it.message ?: "Could not import overlay delta") }
    }

    private fun startGraphDownload(session: NanoHTTPD.IHTTPSession): WebApiResponse {
        if (session.method != NanoHTTPD.Method.POST) return error("POST required")
        val body = requestJson(session)
        return runCatching {
            GraphDownloadScheduler.enqueue(context, body.optString("url"), body.optString("sha256"))
        }.fold(
            onSuccess = { ok(JSONObject().put("workId", it.toString())) },
            onFailure = { error(it.message ?: "Could not enqueue graph download") }
        )
    }

    private fun startProductionGraphDownload(session: NanoHTTPD.IHTTPSession): WebApiResponse {
        if (session.method != NanoHTTPD.Method.POST) return error("POST required")
        return runCatching { GraphDownloadScheduler.enqueueProduction(context) }.fold(
            onSuccess = { ok(JSONObject().put("workId", it.toString())) },
            onFailure = { error(it.message ?: "Could not enqueue production graph download") }
        )
    }

    private fun onlineProvider(session: NanoHTTPD.IHTTPSession): WebApiResponse {
        val store = app.onlineProviderConfigStore
        if (session.method == NanoHTTPD.Method.POST) {
            val body = requestJson(session)
            val current = store.read()
            store.save(
                OnlineProviderConfig(
                    enabled = body.optBoolean("enabled", current.enabled),
                    endpoint = body.optString("endpoint", current.endpoint),
                    model = body.optString("model", current.model),
                    apiToken = if (body.has("apiToken")) body.optString("apiToken") else current.apiToken
                )
            )
        }
        val config = store.read()
        val expansion = ProductionExpansionConfig.fromBuild()
        return ok(JSONObject()
            .put("enabled", config.enabled)
            .put("endpoint", config.endpoint)
            .put("model", config.model)
            .put("hasApiToken", config.apiToken.isNotBlank())
            .put("productionGraphConfigured", expansion.configured)
            .put("productionGraphUrl", expansion.url))
    }

    private suspend fun readerSettings(session: NanoHTTPD.IHTTPSession): WebApiResponse {
        val settings = app.settingsRepository
        if (session.method == NanoHTTPD.Method.POST) {
            val body = requestJson(session)
            if (body.has("readerLayout")) settings.setReaderLayout(body.optString("readerLayout", "PAGED"))
            if (body.has("msOnlineRefinement")) settings.setMsOnlineRefinement(body.optBoolean("msOnlineRefinement"))
            if (body.has("fontSize")) settings.setFontSize(body.optDouble("fontSize", 18.0).toFloat())
            if (body.has("lineSpacing")) settings.setLineSpacing(body.optDouble("lineSpacing", 1.35).toFloat())
            if (body.has("downloadConcurrency")) settings.setDownloadConcurrency(body.optInt("downloadConcurrency", 2))
            if (body.has("downloadRetries")) settings.setDownloadRetries(body.optInt("downloadRetries", 3))
        }
        return ok(JSONObject()
            .put("readerLayout", settings.readerLayout.first())
            .put("msOnlineRefinement", settings.msOnlineRefinement.first())
            .put("fontSize", settings.fontSize.first())
            .put("lineSpacing", settings.lineSpacing.first())
            .put("downloadConcurrency", settings.downloadConcurrency.first())
            .put("downloadRetries", settings.downloadRetries.first()))
    }

    private suspend fun dictionaries(): WebApiResponse {
        app.dictionaryPackageManager.scanInstalled()
        return ok(JSONArray(database.readerDao().getDictionaryPackages().map {
            JSONObject().put("type", it.type).put("fileName", it.fileName).put("version", it.version)
                .put("sha256", it.checksum).put("entryCount", it.entryCount).put("status", it.status)
        }))
    }

    private suspend fun downloadDictionary(session: NanoHTTPD.IHTTPSession): WebApiResponse {
        if (session.method != NanoHTTPD.Method.POST) return error("POST required")
        val body = requestJson(session)
        return runCatching {
            val type = DictionaryType.valueOf(body.getString("type"))
            app.dictionaryPackageManager.download(type, body.getString("url"), body.getString("sha256"))
        }.fold({ ok(JSONObject().put("type", it.type).put("version", it.version).put("entryCount", it.entryCount)) }) {
            error(it.message ?: "Could not download dictionary")
        }
    }

    private suspend fun importDictionary(session: NanoHTTPD.IHTTPSession): WebApiResponse {
        if (session.method != NanoHTTPD.Method.POST) return error("POST required")
        val body = requestJson(session)
        return runCatching {
            val type = DictionaryType.valueOf(body.getString("type"))
            val bytes = Base64.decode(body.getString("contentBase64"), Base64.DEFAULT)
            app.dictionaryPackageManager.import(type, ByteArrayInputStream(bytes), body.optString("sha256").ifBlank { null })
        }.fold({ ok(JSONObject().put("type", it.type).put("version", it.version).put("entryCount", it.entryCount)) }) {
            error(it.message ?: "Could not import dictionary")
        }
    }

    private suspend fun deleteDictionary(session: NanoHTTPD.IHTTPSession): WebApiResponse {
        if (session.method != NanoHTTPD.Method.POST) return error("POST required")
        return runCatching {
            app.dictionaryPackageManager.delete(DictionaryType.valueOf(requestJson(session).getString("type")))
            "deleted"
        }.fold(::ok) { error(it.message ?: "Could not delete dictionary") }
    }

    private fun downloads(): WebApiResponse = ok(JSONArray(GraphDownloadScheduler.snapshots(context).map {
        JSONObject()
            .put("id", it.id.toString())
            .put("state", it.state.name)
            .put("downloadedBytes", it.downloadedBytes)
            .put("totalBytes", it.totalBytes)
            .put("error", it.error)
    }))

    private fun requestJson(session: NanoHTTPD.IHTTPSession): JSONObject {
        val files = hashMapOf<String, String>()
        session.parseBody(files)
        return JSONObject(files["postData"] ?: "{}")
    }

    private suspend fun translateUiText(session: NanoHTTPD.IHTTPSession): WebApiResponse {
        if (session.method != NanoHTTPD.Method.POST) return error("POST required")
        val body = requestJson(session)
        val fieldType = runCatching {
            UiTextFieldType.valueOf(body.optString("fieldType", UiTextFieldType.SOURCE_METADATA.name))
        }.getOrDefault(UiTextFieldType.SOURCE_METADATA)
        val sourceText = body.optString("text")
        return ok(JSONObject()
            .put("fieldType", fieldType.name)
            .put("rawText", sourceText)
            .put("text", app.uiTranslationRepository.translate(sourceText, fieldType)))
    }

    private fun wantsTranslation(session: NanoHTTPD.IHTTPSession): Boolean =
        session.parameters["translate"]?.firstOrNull()?.equals("true", ignoreCase = true) == true

    private suspend fun resolveBook(session: NanoHTTPD.IHTTPSession): com.example.data.Book? {
        val id = session.parameters["id"]?.firstOrNull()?.toIntOrNull()
        if (id != null) return database.bookDao().getBookSnapshot(id)
        val uri = session.parameters["url"]?.firstOrNull().orEmpty()
        return uri.takeIf(String::isNotBlank)?.let { database.bookDao().getBookByUriSnapshot(it) }
    }

    private suspend fun translateUi(text: String, fieldType: UiTextFieldType, enabled: Boolean): String =
        if (enabled) app.uiTranslationRepository.translate(text, fieldType) else text

    private suspend fun bookJson(book: com.example.data.Book, translated: Boolean = false) = JSONObject()
        .put("id", book.id)
        .put("name", translateUi(book.title, UiTextFieldType.BOOK_TITLE, translated))
        .put("author", translateUi(book.author, UiTextFieldType.AUTHOR, translated))
        .put("bookUrl", book.uriString)
        .put("sourceId", book.sourceId)
        .put("cover", book.coverUrl)
        .put("coverUrl", book.coverUrl)
        .put("intro", translateUi(book.description, UiTextFieldType.DESCRIPTION, translated))
        .put("totalChapters", book.totalChapters)
        .put("totalChapterNum", book.totalChapters)
        .put("latestChapterTitle", translateUi(book.latestChapter, UiTextFieldType.CHAPTER_TITLE, translated))
        .put("progress", book.progress)
        .put("durChapterIndex", 0)

    private suspend fun chapterJson(chapter: com.example.data.Chapter, translated: Boolean = false, bookUrl: String = "") = JSONObject()
        .put("index", chapter.chapterIndex)
        .put("title", translateUi(chapter.title, UiTextFieldType.CHAPTER_TITLE, translated))
        .put("bookUrl", bookUrl)
        .put("url", chapter.sourceUrl)
        .put("downloadStatus", chapter.downloadStatus)
        .put("checksum", chapter.checksum)

    private fun downloadBatchJson(batch: com.example.data.DownloadBatch) = JSONObject()
        .put("id", batch.id)
        .put("bookId", batch.bookId)
        .put("firstChapter", batch.firstChapter)
        .put("lastChapter", batch.lastChapter)
        .put("status", batch.status)
        .put("downloadedChapters", batch.downloadedChapters)
        .put("totalChapters", batch.totalChapters)
        .put("attempts", batch.attempts)
        .put("concurrency", batch.concurrency)
        .put("maxRetries", batch.maxRetries)
        .put("error", batch.lastError)

    private fun extensionJson(extension: ExtensionData) = JSONObject()
        .put("id", extension.id)
        .put("name", extension.name)
        .put("author", extension.author)
        .put("version", extension.version)
        .put("source", extension.source)
        .put("description", extension.description)
        .put("scripts", JSONArray(extension.scripts.keys.sorted()))

    private fun legadoSourceJson(source: LegadoInstalledSource) = JSONObject(source.payloadJson)
        .put("id", source.id)
        .put("type", "LEGADO_JSON")

    private suspend fun infoJson(info: VbookBookInfo, translated: Boolean = false) = JSONObject()
        .put("sourceId", info.sourceId)
        .put("name", translateUi(info.title, UiTextFieldType.BOOK_TITLE, translated))
        .put("author", translateUi(info.author, UiTextFieldType.AUTHOR, translated))
        .put("description", translateUi(info.description, UiTextFieldType.DESCRIPTION, translated))
        .put("cover", info.coverUrl)
        .put("url", info.url)
        .put("ongoing", info.ongoing)

    private suspend fun summaryJson(summary: VbookBookSummary, translated: Boolean = false) = JSONObject()
        .put("sourceId", summary.sourceId)
        .put("name", translateUi(summary.title, UiTextFieldType.BOOK_TITLE, translated))
        .put("author", translateUi(summary.author, UiTextFieldType.AUTHOR, translated))
        .put("description", translateUi(summary.description, UiTextFieldType.DESCRIPTION, translated))
        .put("cover", summary.coverUrl)
        .put("url", summary.url)

    private fun overlayStatusJson(status: OverlayRuntimeStatus) = JSONObject()
        .put("overlayVersion", status.overlayVersion)
        .put("termCount", status.termCount)
        .put("reviewedTmCount", status.reviewedTmCount)
        .put("appliedDeltas", JSONArray(status.appliedDeltas.map {
            JSONObject()
                .put("deltaVersion", it.deltaVersion)
                .put("baseGraphVersion", it.baseGraphVersion)
                .put("sha256", it.sha256)
                .put("entryCount", it.entryCount)
                .put("termCount", it.termCount)
                .put("tmCount", it.tmCount)
                .put("appliedAt", it.appliedAt)
        }))

    private fun ok(data: Any?): WebApiResponse = WebApiResponse(body = envelope(true, "", data))

    private fun error(message: String): WebApiResponse =
        WebApiResponse(status = NanoHTTPD.Response.Status.BAD_REQUEST, body = envelope(false, message, JSONObject.NULL))

    private fun envelope(success: Boolean, message: String, data: Any?): String =
        JSONObject().put("isSuccess", success).put("errorMsg", message).put("data", data).toString()
}
