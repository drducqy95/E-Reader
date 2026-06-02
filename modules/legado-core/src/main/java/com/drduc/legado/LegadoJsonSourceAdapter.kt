package com.drduc.legado

import org.json.JSONObject

class LegadoJsonSourceAdapter(
    sourceJson: String,
    private val analyzeUrl: AnalyzeUrl = AnalyzeUrl(),
    private val analyzeRule: AnalyzeRule = AnalyzeRule()
) : SourceAdapter {
    private val source = JSONObject(sourceJson)

    override val id: String = source.optString("bookSourceUrl").ifBlank { source.optString("bookSourceName") }
    override val type: SourceType = SourceType.LEGADO_JSON

    override suspend fun search(keyword: String): List<SearchResult> {
        val response = analyzeUrl.fetch(source.getString("searchUrl"), mapOf("key" to keyword, "page" to "1"))
        val rules = source.getJSONObject("ruleSearch")
        return parseBookList(response, rules)
    }

    fun exploreTabs(): List<SourceExploreTab> =
        source.optString("exploreUrl").ifBlank { source.optString("sortUrl") }
            .lineSequence()
            .map(String::trim)
            .filter(String::isNotBlank)
            .mapNotNull { line ->
                val title = line.substringBefore("::").trim()
                val url = line.substringAfter("::", "").trim()
                if (title.isBlank() || url.isBlank()) null else SourceExploreTab(title, url)
            }.toList()

    suspend fun explore(url: String): List<SearchResult> {
        val response = analyzeUrl.fetch(url, mapOf("page" to "1"))
        val rules = source.optJSONObject("ruleExplore") ?: source.getJSONObject("ruleSearch")
        return parseBookList(response, rules)
    }

    private fun parseBookList(response: AnalyzeResponse, rules: JSONObject): List<SearchResult> {
        return analyzeRule.elements(response.body, rules.getString("bookList"), response.finalUrl).mapNotNull { item ->
            val name = analyzeRule.string(item, rules.optString("name"), response.finalUrl)
            val url = analyzeRule.string(item, rules.optString("bookUrl"), response.finalUrl)
            if (name.isBlank() || url.isBlank()) null else SearchResult(
                name = name,
                author = analyzeRule.string(item, rules.optString("author"), response.finalUrl),
                bookUrl = url,
                coverUrl = analyzeRule.string(item, rules.optString("coverUrl"), response.finalUrl),
                intro = analyzeRule.string(item, rules.optString("intro"), response.finalUrl)
            )
        }
    }

    override suspend fun getBookInfo(url: String): BookRef {
        val response = analyzeUrl.fetch(url)
        val rules = source.optJSONObject("ruleBookInfo") ?: JSONObject()
        val name = analyzeRule.string(response.body, rules.optString("name"), response.finalUrl)
        return BookRef(
            name = name.ifBlank { url },
            author = analyzeRule.string(response.body, rules.optString("author"), response.finalUrl),
            bookUrl = url,
            tocUrl = analyzeRule.string(response.body, rules.optString("tocUrl"), response.finalUrl).ifBlank { url },
            coverUrl = analyzeRule.string(response.body, rules.optString("coverUrl"), response.finalUrl),
            intro = analyzeRule.string(response.body, rules.optString("intro"), response.finalUrl)
        )
    }

    override suspend fun getChapterList(book: BookRef): List<ChapterRef> {
        val response = analyzeUrl.fetch(book.tocUrl)
        val rules = source.getJSONObject("ruleToc")
        return analyzeRule.elements(response.body, rules.getString("chapterList"), response.finalUrl).mapIndexedNotNull { index, item ->
            val title = analyzeRule.string(item, rules.optString("chapterName"), response.finalUrl)
            val url = analyzeRule.string(item, rules.optString("chapterUrl"), response.finalUrl)
            if (title.isBlank() || url.isBlank()) null else ChapterRef(title, url, index, book.bookUrl)
        }
    }

    override suspend fun getContent(chapter: ChapterRef): ChapterContent {
        val response = analyzeUrl.fetch(chapter.url)
        val rules = source.getJSONObject("ruleContent")
        val text = analyzeRule.string(response.body, rules.getString("content"), response.finalUrl)
        return ChapterContent(chapter, text, response.revision)
    }
}
