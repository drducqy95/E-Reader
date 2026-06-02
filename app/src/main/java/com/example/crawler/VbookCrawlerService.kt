package com.example.crawler

import android.util.Log
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup

data class VbookBookSummary(
    val sourceId: String,
    val title: String,
    val author: String,
    val description: String,
    val coverUrl: String,
    val url: String
)

data class VbookBookInfo(
    val sourceId: String,
    val title: String,
    val author: String,
    val description: String,
    val coverUrl: String,
    val url: String,
    val ongoing: Boolean
)

data class VbookChapterRef(
    val index: Int,
    val title: String,
    val url: String
)

data class VbookHomeTab(
    val title: String,
    val input: String,
    val script: String
)

class VbookCrawlerService(
    private val jsEngine: JsEngine = JsEngine()
) : OnlineCrawlerService {
    override fun sourceFormat(sourceId: String): String {
        ExtensionRepository.requireExtension(sourceId)
        return FORMAT
    }

    override fun sources(): List<OnlineSourceOption> = ExtensionRepository.extensions.value.map {
        OnlineSourceOption(it.id, it.name, FORMAT, it.source)
    }

    override suspend fun search(sourceId: String, keyword: String, page: String): List<VbookBookSummary> =
        summaries(sourceId, run(sourceId, "search", keyword, page))

    override suspend fun home(sourceId: String): List<VbookHomeTab> =
        asList(run(sourceId, "home")).mapNotNull { item ->
            val row = item.asStringMap()
            val title = row["title"].orEmpty()
            val script = row["script"].orEmpty()
            if (title.isBlank() || script.isBlank()) null else VbookHomeTab(title, row["input"].orEmpty(), script)
        }

    override suspend fun list(sourceId: String, script: String, input: String, page: String): List<VbookBookSummary> =
        summaries(sourceId, run(sourceId, script, input, page))

    override suspend fun getBookInfo(sourceId: String, url: String): VbookBookInfo {
        val row = asMap(run(sourceId, "detail", url))
        return VbookBookInfo(
            sourceId = sourceId,
            title = row["name"]?.toString().orEmpty().ifBlank { url },
            author = row["author"]?.toString().orEmpty(),
            description = row["description"]?.toString().orEmpty(),
            coverUrl = row["cover"]?.toString().orEmpty(),
            url = url,
            ongoing = row["ongoing"] as? Boolean ?: true
        )
    }

    override suspend fun getChapters(sourceId: String, url: String): List<VbookChapterRef> {
        val extension = ExtensionRepository.requireExtension(sourceId)
        val tocInputs = if (hasScript(extension, "page")) {
            asList(run(sourceId, "page", url)).mapNotNull { it?.toString()?.takeIf(String::isNotBlank) }
        } else {
            listOf(url)
        }
        return tocInputs.flatMap { asList(run(sourceId, "toc", it)) }.mapIndexedNotNull { index, item ->
            val row = item.asStringMap()
            val chapterUrl = row["url"].orEmpty().ifBlank { row["link"].orEmpty() }
            if (chapterUrl.isBlank()) null else {
                VbookChapterRef(index, row["name"].orEmpty().ifBlank { "Chương ${index + 1}" }, chapterUrl)
            }
        }
    }

    override suspend fun getContent(sourceId: String, url: String): String {
        val html = run(sourceId, "chap", url)?.toString().orEmpty()
        if (html.isBlank()) throw IOException("Chapter content is empty")
        return htmlToText(html)
    }

    private suspend fun run(sourceId: String, scriptName: String, vararg args: Any): Any? {
        val extension = ExtensionRepository.requireExtension(sourceId)
        val script = findScript(extension, scriptName)
        val result = withContext(Dispatchers.IO) {
            jsEngine.executeVbookSuspend(script, extension.scripts, "execute", *args)
        }
        Log.d("VBookCrawler", "source=$sourceId script=$scriptName args=${args.joinToString()} result=${describe(result)}")
        if (result is Map<*, *> && result.containsKey("error")) {
            throw IOException(result["error"]?.toString().orEmpty().ifBlank { "VBook script failed: $scriptName" })
        }
        return if (result is Map<*, *> && result.containsKey("data")) result["data"] else result
    }

    private fun describe(value: Any?): String = when (value) {
        null -> "null"
        is List<*> -> "list(${value.size})"
        is Map<*, *> -> "map(keys=${value.keys.joinToString()}, data=${describe(value["data"])})"
        else -> value::class.java.simpleName
    }

    private fun findScript(extension: ExtensionData, name: String): String =
        extension.scripts[name]
            ?: extension.scripts["$name.js"]
            ?: extension.scripts.entries.firstOrNull { it.key.endsWith("/$name") || it.key.endsWith("/$name.js") }?.value
            ?: error("Script not found: $name")

    private fun hasScript(extension: ExtensionData, name: String): Boolean =
        extension.scripts.containsKey(name) ||
            extension.scripts.containsKey("$name.js") ||
            extension.scripts.keys.any { it.endsWith("/$name") || it.endsWith("/$name.js") }

    private fun summaries(sourceId: String, result: Any?): List<VbookBookSummary> =
        asList(result).mapNotNull { item ->
            val row = item.asStringMap()
            val url = row["link"].orEmpty().ifBlank { row["url"].orEmpty() }
            if (url.isBlank()) null else VbookBookSummary(
                sourceId = sourceId,
                title = row["name"].orEmpty().ifBlank { url },
                author = row["author"].orEmpty(),
                description = row["description"].orEmpty(),
                coverUrl = row["cover"].orEmpty(),
                url = url
            )
        }

    private fun htmlToText(html: String): String {
        if (!html.contains('<')) return html.trim()
        val withBreaks = html
            .replace(Regex("(?i)<br\\s*/?>"), "\n")
            .replace(Regex("(?i)</p\\s*>"), "\n")
            .replace(Regex("(?i)</div\\s*>"), "\n")
        return Jsoup.parseBodyFragment(withBreaks).body().wholeText()
            .lineSequence()
            .map(String::trimEnd)
            .joinToString("\n")
            .trim()
    }

    private fun asList(value: Any?): List<*> = value as? List<*>
        ?: throw IOException("Expected list from VBook script")

    private fun asMap(value: Any?): Map<*, *> = value as? Map<*, *>
        ?: throw IOException("Expected object from VBook script")

    private fun Any?.asStringMap(): Map<String, String> =
        (this as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value?.toString().orEmpty() }.orEmpty()

    companion object {
        const val FORMAT = "VBOOK"
    }
}
