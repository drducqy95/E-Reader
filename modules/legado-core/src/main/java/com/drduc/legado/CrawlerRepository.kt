package com.drduc.legado

class CrawlerRepository {
    private val adapters = linkedMapOf<String, SourceAdapter>()

    fun register(adapter: SourceAdapter) {
        adapters[adapter.id] = adapter
    }

    fun remove(sourceId: String) {
        adapters.remove(sourceId)
    }

    fun sources(): List<SourceAdapter> = adapters.values.toList()

    fun require(sourceId: String): SourceAdapter =
        adapters[sourceId] ?: error("Source is not installed: $sourceId")

    suspend fun searchAll(keyword: String): List<SearchResult> =
        adapters.values.flatMap { adapter ->
            runCatching { adapter.search(keyword) }.getOrDefault(emptyList())
        }
}
