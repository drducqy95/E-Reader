package com.example.crawler

import java.net.URL
import java.util.zip.ZipInputStream
import org.json.JSONObject
import java.io.ByteArrayOutputStream

object ExtensionParser {
    fun parseUrlOrRepo(urlString: String): List<ExtensionData> {
        return try {
            val url = URL(urlString)
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            connection.doInput = true
            
            if (connection.responseCode != 200) {
                return listOf(ExtensionData("error", "HTTP Error", "System", 0, "", "Code: ${connection.responseCode} for $urlString", null, emptyMap()))
            }
            
            val contentType = connection.contentType ?: ""
            if (urlString.endsWith(".json") || contentType.contains("application/json") || contentType.contains("text/plain")) {
                val jsonStr = connection.inputStream.bufferedReader().readText()
                val rootJson = JSONObject(jsonStr)
                if (rootJson.has("data")) {
                    val dataArr = rootJson.getJSONArray("data")
                    val resultList = mutableListOf<ExtensionData>()
                    val max = dataArr.length()
                    for (i in 0 until max) {
                        try {
                            val item = dataArr.getJSONObject(i)
                            val zipUrl = item.optString("path", "")
                            if (zipUrl.isNotBlank()) {
                                val ext = parseZipFromUrl(zipUrl)
                                if (ext != null && ext.id != "error") {
                                    resultList.add(ext)
                                }
                            }
                        } catch (e: Exception) {}
                    }
                    if (resultList.isEmpty()) {
                        listOf(ExtensionData("error", "Error", "System", 0, "", "Không thể cài được extension nào từ repo.", null, emptyMap()))
                    } else {
                        resultList
                    }
                } else {
                    listOf(ExtensionData("error", "Error", "System", 0, "", "Invalid repo JSON format.", null, emptyMap()))
                }
            } else {
                val ext = parseZipFromUrl(urlString)
                if (ext != null) listOf(ext) else emptyList()
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            listOf(ExtensionData("error", "Error", "System", 0, "", e.message ?: e.toString(), null, emptyMap()))
        }
    }
    
    fun parseZipFromUrl(urlString: String): ExtensionData? {
        try {
            val url = URL(urlString)
            val connection = url.openConnection() as java.net.HttpURLConnection
            connection.connectTimeout = 15000
            connection.readTimeout = 15000
            connection.setRequestProperty("User-Agent", "Mozilla/5.0")
            connection.doInput = true
            
            if (connection.responseCode != 200) {
                return ExtensionData("error", "HTTP Error", "System", 0, "", "Code: ${connection.responseCode} for $urlString", null, emptyMap())
            }

            ZipInputStream(connection.inputStream).use { zis ->
                var entry = zis.nextEntry
                val files = mutableMapOf<String, ByteArray>()
                
                while (entry != null) {
                    if (!entry.isDirectory) {
                        val baos = ByteArrayOutputStream()
                        zis.copyTo(baos)
                        files[entry.name] = baos.toByteArray()
                    }
                    entry = zis.nextEntry
                }
                
                val pluginJsonEntry = files.entries.find { it.key.endsWith("plugin.json") }
                val pluginJsonBytes = pluginJsonEntry?.value ?: run {
                    val rootFiles = files.keys.joinToString(", ")
                    throw Exception("plugin.json not found. Files: $rootFiles")
                }
                val pluginDir = pluginJsonEntry.key.substringBeforeLast("plugin.json", "")
                
                val pluginJsonStr = String(pluginJsonBytes)
                
                val json = JSONObject(pluginJsonStr)
                val metadata = json.getJSONObject("metadata")
                val scriptPaths = json.getJSONObject("script")
                
                val name = metadata.optString("name", "Unknown")
                val isEncrypted = json.optBoolean("encrypt", false) || metadata.optBoolean("encrypt", false)
                if (isEncrypted) {
                    return ExtensionData(
                        id = "error",
                        name = "Error",
                        author = "System",
                        version = 0,
                        source = "",
                        description = "Nguồn truyện $name sử dụng mã hóa và chưa được hỗ trợ trên Ứng dụng này.",
                        iconBase64 = null,
                        scripts = emptyMap()
                    )
                }
                
                val author = metadata.optString("author", "")
                val version = metadata.optInt("version", 1)
                val source = metadata.optString("source", "")
                val description = metadata.optString("description", "")
                
                val scripts = mutableMapOf<String, String>()
                files.forEach { (path, bytes) ->
                    if (path.endsWith(".js") && !path.contains("__MACOSX")) {
                        val name = path.substringAfterLast("/")
                        var content = String(bytes)
                        if (content.startsWith("\uFEFF")) {
                            content = content.substring(1)
                        }
                        scripts[name] = content
                    }
                }
                
                // Also map the ones in plugin.json script just in case they have weird names
                val keys = scriptPaths.keys()
                while (keys.hasNext()) {
                    val key = keys.next()
                    val path = scriptPaths.getString(key)
                    val scriptBytes = files["$pluginDir$path"] ?: files["${pluginDir}src/$path"] ?: files.entries.find { it.key.endsWith(path) }?.value
                    scriptBytes?.let { 
                        var content = String(it)
                        if (content.startsWith("\uFEFF")) {
                            content = content.substring(1)
                        }
                        scripts[key] = content
                    }
                }
                
                return ExtensionData(
                    id = "$name-$author",
                    name = name,
                    author = author,
                    version = version,
                    source = source,
                    description = description,
                    iconBase64 = null,
                    scripts = scripts
                )
            }
        } catch (e: Throwable) {
            e.printStackTrace()
            return ExtensionData(
                id = "error",
                name = "Error",
                author = "System",
                version = 0,
                source = "",
                description = e.message ?: e.toString(),
                iconBase64 = null,
                scripts = emptyMap()
            )
        }
    }
}
