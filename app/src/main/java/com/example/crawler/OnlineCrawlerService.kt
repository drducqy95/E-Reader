package com.example.crawler

data class OnlineSourceOption(
    val id: String,
    val name: String,
    val type: String,
    val loginUrl: String = ""
)

interface OnlineCrawlerService {
    fun sourceFormat(sourceId: String): String
    fun sources(): List<OnlineSourceOption>
    suspend fun search(sourceId: String, keyword: String, page: String = "1"): List<VbookBookSummary>
    suspend fun home(sourceId: String): List<VbookHomeTab> = emptyList()
    suspend fun list(sourceId: String, script: String, input: String, page: String = "1"): List<VbookBookSummary> =
        emptyList()
    suspend fun getBookInfo(sourceId: String, url: String): VbookBookInfo
    suspend fun getChapters(sourceId: String, url: String): List<VbookChapterRef>
    suspend fun getContent(sourceId: String, url: String): String
}

class CompositeOnlineCrawlerService(
    private val vbook: VbookCrawlerService,
    private val legado: LegadoCrawlerService
) : OnlineCrawlerService {
    override fun sourceFormat(sourceId: String): String = adapter(sourceId).sourceFormat(sourceId)

    override fun sources(): List<OnlineSourceOption> =
        (vbook.sources() + legado.sources()).sortedBy(OnlineSourceOption::name)

    override suspend fun search(sourceId: String, keyword: String, page: String): List<VbookBookSummary> =
        adapter(sourceId).search(sourceId, keyword, page)

    override suspend fun home(sourceId: String): List<VbookHomeTab> = adapter(sourceId).home(sourceId)

    override suspend fun list(sourceId: String, script: String, input: String, page: String): List<VbookBookSummary> =
        adapter(sourceId).list(sourceId, script, input, page)

    override suspend fun getBookInfo(sourceId: String, url: String): VbookBookInfo =
        adapter(sourceId).getBookInfo(sourceId, url)

    override suspend fun getChapters(sourceId: String, url: String): List<VbookChapterRef> =
        adapter(sourceId).getChapters(sourceId, url)

    override suspend fun getContent(sourceId: String, url: String): String =
        adapter(sourceId).getContent(sourceId, url)

    private fun adapter(sourceId: String): OnlineCrawlerService =
        if (legado.hasSource(sourceId)) legado else vbook
}
