package com.example.crawler

import android.content.Context
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

object ExtensionRepository {
    private val _extensions = MutableStateFlow<List<ExtensionData>>(emptyList())
    val extensions: StateFlow<List<ExtensionData>> = _extensions.asStateFlow()
    private var storageDir: File? = null

    @Synchronized
    fun initialize(context: Context, reload: Boolean = false) {
        if (storageDir != null && !reload) return
        storageDir = File(context.filesDir, "vbook/extensions").apply { mkdirs() }
        _extensions.value = checkNotNull(storageDir)
            .listFiles { file -> file.extension == "json" }
            .orEmpty()
            .mapNotNull { file -> runCatching { decode(file.readText(StandardCharsets.UTF_8)) }.getOrNull() }
            .sortedBy(ExtensionData::name)
    }

    fun requireExtension(extId: String): ExtensionData =
        _extensions.value.firstOrNull { it.id == extId } ?: error("Extension is not installed: $extId")

    @Synchronized
    fun addExtension(ext: ExtensionData) {
        require(ext.id != "error") { ext.description }
        require(ext.scripts.isNotEmpty()) { "Extension does not contain scripts" }
        val directory = checkNotNull(storageDir) { "ExtensionRepository is not initialized" }
        val target = File(directory, "${fileKey(ext.id)}.json")
        val temporary = File(directory, "${target.name}.tmp")
        temporary.writeText(encode(ext).toString(), StandardCharsets.UTF_8)
        check(temporary.renameTo(target) || run {
            target.delete()
            temporary.renameTo(target)
        }) { "Could not persist extension ${ext.id}" }
        val current = _extensions.value.toMutableList()
        current.removeAll { it.id == ext.id }
        current.add(ext)
        _extensions.value = current.sortedBy(ExtensionData::name)
    }

    @Synchronized
    fun removeExtension(extId: String) {
        storageDir?.let { File(it, "${fileKey(extId)}.json").delete() }
        _extensions.value = _extensions.value.filter { it.id != extId }
    }

    private fun encode(ext: ExtensionData) = JSONObject()
        .put("id", ext.id)
        .put("name", ext.name)
        .put("author", ext.author)
        .put("version", ext.version)
        .put("source", ext.source)
        .put("description", ext.description)
        .put("iconBase64", ext.iconBase64)
        .put("scripts", JSONObject(ext.scripts))

    private fun decode(json: String): ExtensionData {
        val root = JSONObject(json)
        val scriptJson = root.getJSONObject("scripts")
        return ExtensionData(
            id = root.getString("id"),
            name = root.getString("name"),
            author = root.optString("author"),
            version = root.optInt("version", 1),
            source = root.optString("source"),
            description = root.optString("description"),
            iconBase64 = root.optString("iconBase64").takeIf(String::isNotBlank),
            scripts = scriptJson.keys().asSequence().associateWith(scriptJson::getString)
        )
    }

    private fun fileKey(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(StandardCharsets.UTF_8))
        .take(12)
        .joinToString("") { "%02x".format(it) }
}
