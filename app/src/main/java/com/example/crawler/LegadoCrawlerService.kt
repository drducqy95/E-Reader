package com.example.crawler

import com.drduc.legado.AnalyzeRule
import com.drduc.legado.AnalyzeUrl
import com.drduc.legado.JsRuleEvaluator
import com.drduc.legado.LegadoJsonSourceAdapter
import com.drduc.legado.WebViewRuleEvaluator
import java.io.IOException

class LegadoCrawlerService(
    private val repository: LegadoJsonSourceRepository,
    private val jsEngine: JsEngine,
    private val sessions: VbookSessionStore,
    private val webViewEvaluator: WebViewRuleEvaluator? = null
) : OnlineCrawlerService {
    fun hasSource(sourceId: String): Boolean = repository.contains(sourceId)

    override fun sourceFormat(sourceId: String): String {
        repository.require(sourceId)
        return FORMAT
    }

    override fun sources(): List<OnlineSourceOption> = repository.sources.value.map {
        OnlineSourceOption(it.id, it.name, FORMAT, it.sourceUrl)
    }

    override suspend fun search(sourceId: String, keyword: String, page: String): List<VbookBookSummary> =
        adapter(sourceId).search(keyword).map {
            VbookBookSummary(sourceId, it.name, it.author, it.intro, it.coverUrl, it.bookUrl)
        }

    override suspend fun home(sourceId: String): List<VbookHomeTab> =
        adapter(sourceId).exploreTabs().map { VbookHomeTab(it.title, it.url, EXPLORE_SCRIPT) }

    override suspend fun list(sourceId: String, script: String, input: String, page: String): List<VbookBookSummary> {
        if (script != EXPLORE_SCRIPT) return emptyList()
        return adapter(sourceId).explore(input).map {
            VbookBookSummary(sourceId, it.name, it.author, it.intro, it.coverUrl, it.bookUrl)
        }
    }

    override suspend fun getBookInfo(sourceId: String, url: String): VbookBookInfo =
        adapter(sourceId).getBookInfo(url).let {
            VbookBookInfo(sourceId, it.name, it.author, it.intro, it.coverUrl, it.bookUrl, true)
        }

    override suspend fun getChapters(sourceId: String, url: String): List<VbookChapterRef> {
        val source = adapter(sourceId)
        val book = source.getBookInfo(url)
        return source.getChapterList(book).map { VbookChapterRef(it.index, it.title, it.url) }
    }

    override suspend fun getContent(sourceId: String, url: String): String {
        val chapter = com.drduc.legado.ChapterRef("", url, 0, "")
        return adapter(sourceId).getContent(chapter).text.takeIf(String::isNotBlank)
            ?: throw IOException("Chapter content is empty")
    }

    private fun adapter(sourceId: String): LegadoJsonSourceAdapter = LegadoJsonSourceAdapter(
        sourceJson = repository.require(sourceId).payloadJson,
        analyzeUrl = AnalyzeUrl(
            cookieStore = sessions,
            webViewEvaluator = webViewEvaluator,
            userAgent = sessions.userAgent()
        ),
        analyzeRule = AnalyzeRule(
            jsEvaluator = JsRuleEvaluator(jsEngine::evaluateRule),
            webViewEvaluator = webViewEvaluator
        )
    )

    companion object {
        const val FORMAT = "LEGADO_JSON"
        private const val EXPLORE_SCRIPT = "legadoExplore"
    }
}
