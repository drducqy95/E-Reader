package com.drduc.engine.graph

import java.math.BigDecimal
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

data class OverlayDeltaEntry(
    val type: String,
    val source: String,
    val target: String,
    val priority: Int = 100,
    val confidence: Double = 1.0,
    val universe: String = "",
    val domain: String = "",
    val scope: String = "",
    val entityType: String = "",
    val posTag: String = "",
    val posSub: String = "",
    val grammarRole: String = "",
    val contextMarkers: List<String> = emptyList(),
    val negativeContextMarkers: List<String> = emptyList(),
    val coOccurringEntities: List<String> = emptyList()
)

data class PreparedOverlayDelta(
    val schemaVersion: Int,
    val baseGraphVersion: String,
    val deltaVersion: String,
    val sha256: String,
    val entries: List<OverlayDeltaEntry>
)

object OverlayDeltaManifest {
    const val SCHEMA_VERSION = 2
    private const val MAX_ENTRIES = 50_000

    fun parse(root: JSONObject, currentGraphVersion: String): PreparedOverlayDelta {
        val schemaVersion = root.optInt("schemaVersion")
        require(schemaVersion == SCHEMA_VERSION) {
            "Unsupported overlay delta schema: $schemaVersion"
        }
        val baseGraphVersion = root.optString("baseGraphVersion").trim()
        require(baseGraphVersion.isNotBlank()) { "Overlay delta requires baseGraphVersion" }
        require(baseGraphVersion == currentGraphVersion) {
            "Overlay delta requires graph $baseGraphVersion, runtime graph is $currentGraphVersion"
        }
        val deltaVersion = root.optString("deltaVersion").trim()
        require(deltaVersion.isNotBlank()) { "Overlay delta requires deltaVersion" }
        val sha256 = root.optString("sha256").trim().lowercase()
        require(sha256.matches(Regex("[0-9a-f]{64}"))) { "Overlay delta requires a SHA-256 checksum" }
        val actual = calculateSha256(root)
        require(actual == sha256) { "Overlay delta checksum mismatch: expected=$sha256 actual=$actual" }
        val rows = root.optJSONArray("entries") ?: error("Overlay delta requires entries")
        require(rows.length() in 1..MAX_ENTRIES) { "Overlay delta entries must contain 1..$MAX_ENTRIES rows" }
        return PreparedOverlayDelta(
            schemaVersion = schemaVersion,
            baseGraphVersion = baseGraphVersion,
            deltaVersion = deltaVersion,
            sha256 = sha256,
            entries = (0 until rows.length()).map { index -> parseEntry(rows.getJSONObject(index), index) }
        )
    }

    fun calculateSha256(root: JSONObject): String {
        val payload = JSONObject()
            .put("schemaVersion", root.optInt("schemaVersion"))
            .put("baseGraphVersion", root.optString("baseGraphVersion"))
            .put("deltaVersion", root.optString("deltaVersion"))
            .put("entries", root.optJSONArray("entries") ?: JSONArray())
        return MessageDigest.getInstance("SHA-256")
            .digest(canonicalJson(payload).toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    fun withCalculatedSha256(root: JSONObject): JSONObject =
        JSONObject(root.toString()).put("sha256", calculateSha256(root))

    private fun parseEntry(row: JSONObject, index: Int): OverlayDeltaEntry {
        require(row.optBoolean("reviewed", false)) { "Overlay delta entry $index is not reviewed" }
        val type = row.optString("type").trim().lowercase()
        require(type in setOf("term", "tm")) { "Unsupported overlay delta entry type at $index: $type" }
        val source = row.optString("source")
        val target = row.optString("target")
        require(source.isNotBlank()) { "Overlay delta entry $index requires source" }
        require(target.isNotBlank()) { "Overlay delta entry $index requires target" }
        return OverlayDeltaEntry(
            type = type,
            source = source,
            target = target,
            priority = row.optInt("priority", 100),
            confidence = row.optDouble("confidence", 1.0).coerceIn(0.0, 1.0),
            universe = row.optString("universe"),
            domain = row.optString("domain"),
            scope = row.optString("scope"),
            entityType = row.optString("entityType"),
            posTag = row.optString("posTag"),
            posSub = row.optString("posSub"),
            grammarRole = row.optString("grammarRole"),
            contextMarkers = row.stringList("contextMarkers"),
            negativeContextMarkers = row.stringList("negativeContextMarkers"),
            coOccurringEntities = row.stringList("coOccurringEntities")
        )
    }

    private fun canonicalJson(value: Any?): String = when (value) {
        null, JSONObject.NULL -> "null"
        is JSONObject -> value.keys().asSequence().toList().sorted()
            .joinToString(separator = ",", prefix = "{", postfix = "}") { key ->
                "${canonicalQuote(key)}:${canonicalJson(value.opt(key))}"
            }
        is JSONArray -> (0 until value.length()).joinToString(separator = ",", prefix = "[", postfix = "]") {
            canonicalJson(value.opt(it))
        }
        is Number -> canonicalNumber(value)
        is Boolean -> value.toString()
        else -> canonicalQuote(value.toString())
    }

    private fun canonicalNumber(value: Number): String {
        val number = BigDecimal(value.toString()).stripTrailingZeros()
        return if (number.compareTo(BigDecimal.ZERO) == 0) "0" else number.toPlainString()
    }

    private fun canonicalQuote(value: String): String = buildString(value.length + 2) {
        append('"')
        value.forEach { char ->
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\b' -> append("\\b")
                '\u000c' -> append("\\f")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }

    private fun JSONObject.stringList(key: String): List<String> = optJSONArray(key)?.let { array ->
        (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
    }.orEmpty()
}
