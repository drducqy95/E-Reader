package com.example.crawler

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.util.zip.ZipInputStream
import org.json.JSONArray
import org.json.JSONObject

data class ExtensionInstallIssue(val path: String, val message: String)

data class ExtensionInstallReport(
    val extensions: List<ExtensionData>,
    val issues: List<ExtensionInstallIssue>
)

object ExtensionParser {
    fun parseUrlOrRepo(urlString: String): List<ExtensionData> {
        val report = inspectUrlOrRepo(urlString)
        return report.extensions.ifEmpty {
            listOf(errorExtension(report.issues.joinToString("; ") { it.message }.ifBlank { "Không thể cài extension." }))
        }
    }

    fun inspectUrlOrRepo(urlString: String): ExtensionInstallReport {
        return try {
            useConnection(urlString) { connection ->
                val contentType = connection.contentType.orEmpty()
                if (urlString.endsWith(".json", ignoreCase = true) ||
                    contentType.contains("application/json") ||
                    contentType.contains("text/plain")
                ) {
                    inspectRepo(connection.inputStream.bufferedReader(StandardCharsets.UTF_8).use { it.readText() })
                } else {
                    ExtensionInstallReport(listOf(parseZip(connection.inputStream)), emptyList())
                }
            }
        } catch (error: Throwable) {
            ExtensionInstallReport(emptyList(), listOf(ExtensionInstallIssue(urlString, error.message ?: error.toString())))
        }
    }

    fun parseZipFromUrl(urlString: String): ExtensionData? {
        return try {
            useConnection(urlString) { connection ->
                parseZip(connection.inputStream)
            }
        } catch (error: Throwable) {
            errorExtension(error.message ?: error.toString())
        }
    }

    fun parseZip(input: InputStream): ExtensionData {
        val files = linkedMapOf<String, ByteArray>()
        var totalBytes = 0L
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val safeName = validatedEntryName(entry.name)
                if (!entry.isDirectory) {
                    require(files.size < MAX_FILES) { "Extension ZIP contains too many files" }
                    val bytes = readEntry(zip, MAX_BYTES - totalBytes)
                    totalBytes += bytes.size
                    require(totalBytes <= MAX_BYTES) { "Extension ZIP is too large" }
                    files[safeName] = bytes
                }
                zip.closeEntry()
            }
        }
        val pluginJsonEntry = files.entries.firstOrNull { it.key.endsWith("plugin.json") }
            ?: error("plugin.json not found")
        val pluginDir = pluginJsonEntry.key.substringBeforeLast("plugin.json", "")
        val json = JSONObject(pluginJsonEntry.value.toString(StandardCharsets.UTF_8))
        val metadata = json.getJSONObject("metadata")
        val scriptPaths = json.getJSONObject("script")
        val name = metadata.optString("name", "Unknown").trim().ifBlank { "Unknown" }
        if (json.optBoolean("encrypt", false) || metadata.optBoolean("encrypt", false)) {
            error("Nguồn truyện $name dùng script mã hóa của VBook. Cần decoder tương thích do tác giả hoặc runtime VBook cung cấp; Rhino không thể chạy trực tiếp gói này.")
        }
        val author = metadata.optString("author", "").trim()
        val scripts = linkedMapOf<String, String>()
        files.forEach { (path, bytes) ->
            if (path.endsWith(".js", ignoreCase = true) && !path.contains("__MACOSX")) {
                scripts[path.substringAfterLast('/')] = decodeScript(bytes)
            }
        }
        scriptPaths.keys().forEach { key ->
            val path = scriptPaths.getString(key)
            val scriptBytes = files["$pluginDir$path"]
                ?: files["${pluginDir}src/$path"]
                ?: files.entries.firstOrNull { it.key.endsWith("/$path") || it.key == path }?.value
            requireNotNull(scriptBytes) { "Script not found: $path" }
            scripts[key] = decodeScript(scriptBytes)
        }
        require(scripts.isNotEmpty()) { "Extension does not contain JavaScript" }
        return ExtensionData(
            id = "$name-$author",
            name = name,
            author = author,
            version = metadata.optInt("version", 1),
            source = metadata.optString("source", ""),
            description = metadata.optString("description", ""),
            iconBase64 = null,
            scripts = scripts
        )
    }

    private fun inspectRepo(text: String): ExtensionInstallReport {
        val root = text.trim()
        val data = when {
            root.startsWith("[") -> JSONArray(root)
            else -> JSONObject(root).optJSONArray("data")
        } ?: return ExtensionInstallReport(emptyList(), listOf(ExtensionInstallIssue("", "Invalid repo JSON format.")))
        val extensions = mutableListOf<ExtensionData>()
        val issues = mutableListOf<ExtensionInstallIssue>()
        repeat(data.length()) { index ->
            val path = data.optJSONObject(index)?.optString("path").orEmpty()
            if (path.isNotBlank()) {
                parseZipFromUrl(path)?.let {
                    if (it.id == "error") issues += ExtensionInstallIssue(path, it.description)
                    else extensions += it
                }
            }
        }
        return ExtensionInstallReport(extensions, issues)
    }

    private fun open(urlString: String): HttpURLConnection {
        val connection = URL(urlString).openConnection() as HttpURLConnection
        connection.connectTimeout = TIMEOUT_MS
        connection.readTimeout = TIMEOUT_MS
        connection.setRequestProperty("User-Agent", "Mozilla/5.0")
        connection.doInput = true
        val code = connection.responseCode
        check(code in 200..299) { "HTTP $code for $urlString" }
        return connection
    }

    private inline fun <T> useConnection(urlString: String, block: (HttpURLConnection) -> T): T {
        val connection = open(urlString)
        return try {
            block(connection)
        } finally {
            connection.disconnect()
        }
    }

    private fun validatedEntryName(name: String): String {
        val normalized = name.replace('\\', '/')
        require(!normalized.startsWith('/') && !Regex("^[A-Za-z]:").containsMatchIn(normalized)) {
            "Unsafe ZIP entry: $name"
        }
        require(normalized.split('/').none { it == ".." }) { "Unsafe ZIP entry: $name" }
        return normalized
    }

    private fun readEntry(input: InputStream, remaining: Long): ByteArray {
        require(remaining > 0) { "Extension ZIP is too large" }
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            output.write(buffer, 0, count)
            require(output.size().toLong() <= remaining) { "Extension ZIP is too large" }
        }
        return output.toByteArray()
    }

    private fun decodeScript(bytes: ByteArray): String =
        bytes.toString(StandardCharsets.UTF_8).removePrefix("\uFEFF")

    private fun errorExtension(message: String) =
        ExtensionData("error", "Error", "System", 0, "", message, null, emptyMap())

    private const val MAX_FILES = 256
    private const val MAX_BYTES = 16L * 1024L * 1024L
    private const val TIMEOUT_MS = 15_000
}
