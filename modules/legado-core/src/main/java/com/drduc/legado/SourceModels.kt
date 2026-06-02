package com.drduc.legado

enum class SourceType { LEGADO_JSON, VBOOK_JS, LOCAL_FILE }

data class SearchResult(
    val name: String,
    val author: String = "",
    val bookUrl: String,
    val coverUrl: String = "",
    val intro: String = ""
)

data class SourceExploreTab(val title: String, val url: String)

data class BookRef(
    val name: String,
    val author: String = "",
    val bookUrl: String,
    val tocUrl: String = bookUrl,
    val coverUrl: String = "",
    val intro: String = ""
)

data class ChapterRef(
    val title: String,
    val url: String,
    val index: Int,
    val bookUrl: String
)

data class ChapterContent(
    val chapter: ChapterRef,
    val text: String,
    val sourceRevision: String = ""
)

data class DebugStep(val stage: String, val rule: String, val resultPreview: String)

fun interface DebugSink {
    fun emit(step: DebugStep)
}

interface SourceAdapter {
    val id: String
    val type: SourceType
    suspend fun search(keyword: String): List<SearchResult>
    suspend fun getBookInfo(url: String): BookRef
    suspend fun getChapterList(book: BookRef): List<ChapterRef>
    suspend fun getContent(chapter: ChapterRef): ChapterContent
}

fun interface JsRuleEvaluator {
    fun evaluate(script: String, input: Any?): Any?
}

fun interface WebViewRuleEvaluator {
    suspend fun evaluate(url: String, html: String, script: String): String
}

interface AnalyzeCookieStore {
    fun cookieHeader(url: String): String?
    fun capture(url: String, setCookieHeaders: List<String>)
}
