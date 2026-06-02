package com.example.crawler

import android.content.Context
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

data class LegadoInstalledSource(
    val id: String,
    val name: String,
    val sourceUrl: String,
    val payloadJson: String
)

class LegadoJsonSourceRepository(context: Context) {
    private val directory = File(context.filesDir, "legado/sources").apply { mkdirs() }
    private val _sources = MutableStateFlow(load())
    val sources: StateFlow<List<LegadoInstalledSource>> = _sources.asStateFlow()

    fun install(payloadJson: String): LegadoInstalledSource {
        val source = decode(payloadJson)
        val target = File(directory, "${fileKey(source.id)}.json")
        val temporary = File(directory, "${target.name}.tmp")
        temporary.writeText(source.payloadJson, StandardCharsets.UTF_8)
        check(temporary.renameTo(target) || run {
            target.delete()
            temporary.renameTo(target)
        }) { "Could not persist Legado source ${source.id}" }
        _sources.value = (_sources.value.filterNot { it.id == source.id } + source).sortedBy(LegadoInstalledSource::name)
        return source
    }

    fun installFromUrl(url: String): LegadoInstalledSource {
        val parsedUrl = URL(url)
        require(parsedUrl.protocol == "https" || parsedUrl.host in DEVELOPMENT_HOSTS) {
            "Legado source URL must use HTTPS"
        }
        val connection = parsedUrl.openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("User-Agent", "Mozilla/5.0")
        return try {
            check(connection.responseCode in 200..299) { "HTTP ${connection.responseCode} for $url" }
            install(connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    fun require(sourceId: String): LegadoInstalledSource =
        _sources.value.firstOrNull { it.id == sourceId } ?: error("Legado source is not installed: $sourceId")

    fun contains(sourceId: String): Boolean = _sources.value.any { it.id == sourceId }

    fun remove(sourceId: String) {
        File(directory, "${fileKey(sourceId)}.json").delete()
        _sources.value = _sources.value.filterNot { it.id == sourceId }
    }

    private fun load(): List<LegadoInstalledSource> = directory
        .listFiles { file -> file.extension.equals("json", true) }
        .orEmpty()
        .mapNotNull { file -> runCatching { decode(file.readText(StandardCharsets.UTF_8)) }.getOrNull() }
        .sortedBy(LegadoInstalledSource::name)

    private fun decode(payloadJson: String): LegadoInstalledSource {
        val normalized = JSONObject(payloadJson).toString()
        val root = JSONObject(normalized)
        val id = root.optString("bookSourceUrl").trim().ifBlank { root.optString("bookSourceName").trim() }
        require(id.isNotBlank()) { "Legado source requires bookSourceUrl or bookSourceName" }
        require(root.optString("searchUrl").isNotBlank()) { "Legado source requires searchUrl" }
        require(root.optJSONObject("ruleSearch") != null) { "Legado source requires ruleSearch" }
        require(root.optJSONObject("ruleToc") != null) { "Legado source requires ruleToc" }
        require(root.optJSONObject("ruleContent") != null) { "Legado source requires ruleContent" }
        return LegadoInstalledSource(
            id = id,
            name = root.optString("bookSourceName").trim().ifBlank { id },
            sourceUrl = root.optString("bookSourceUrl").trim(),
            payloadJson = normalized
        )
    }

    private fun fileKey(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .take(12)
        .joinToString("") { "%02x".format(it) }

    companion object {
        private const val TIMEOUT_MS = 15_000
        private val DEVELOPMENT_HOSTS = setOf("127.0.0.1", "localhost", "10.0.2.2")
    }
}
