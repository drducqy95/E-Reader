package com.example.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

data class GraphPackageStatus(
    val installed: Boolean,
    val filePath: String = "",
    val bytes: Long = 0,
    val sha256: String = "",
    val graphVersion: String = "none",
    val installedAt: Long = 0,
    val source: String = "",
    val contextRows: Int = 0,
    val universeTerms: Int = 0,
    val grammarNodes: Int = 0,
    val cooccurrenceRows: Int = 0,
    val warnings: List<String> = emptyList()
) {
    val contextUniverseAvailable: Boolean
        get() = contextRows > 0 && universeTerms > 0
}

private data class GraphValidation(
    val graphVersion: String,
    val contextRows: Int,
    val universeTerms: Int,
    val grammarNodes: Int,
    val cooccurrenceRows: Int
)

class GraphPackageManager(private val context: Context) {
    val graphFile: File
        get() = File(context.noBackupFilesDir, "drduc/translation_graph.mobile.sqlite")

    private val metadataFile: File
        get() = File(graphFile.parentFile, "translation_graph.mobile.json")

    fun status(): GraphPackageStatus {
        val target = graphFile
        if (!target.isFile) return GraphPackageStatus(installed = false)
        val metadata = readMetadata()
        val validation = if (metadata?.has("contextRows") == true) null else inspectGraph(target)
        val contextRows = metadata?.optInt("contextRows") ?: validation?.contextRows ?: 0
        val universeTerms = metadata?.optInt("universeTerms") ?: validation?.universeTerms ?: 0
        val grammarNodes = metadata?.optInt("grammarNodes") ?: validation?.grammarNodes ?: 0
        val cooccurrenceRows = metadata?.optInt("cooccurrenceRows") ?: validation?.cooccurrenceRows ?: 0
        return GraphPackageStatus(
            installed = true,
            filePath = target.path,
            bytes = target.length(),
            sha256 = metadata?.optString("sha256").orEmpty(),
            graphVersion = metadata?.optString("graphVersion").orEmpty().ifBlank {
                validation?.graphVersion ?: inspectGraph(target).graphVersion
            },
            installedAt = metadata?.optLong("installedAt") ?: target.lastModified(),
            source = metadata?.optString("source").orEmpty(),
            contextRows = contextRows,
            universeTerms = universeTerms,
            grammarNodes = grammarNodes,
            cooccurrenceRows = cooccurrenceRows,
            warnings = capabilityWarnings(contextRows, universeTerms, grammarNodes, cooccurrenceRows)
        )
    }

    fun importGraph(input: InputStream, expectedSha256: String? = null, source: String = "manual"): GraphPackageStatus {
        val target = graphFile
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.delete()
        val digest = MessageDigest.getInstance("SHA-256")
        temporary.outputStream().buffered().use { output ->
            input.buffered().use { sourceStream ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                while (true) {
                    val count = sourceStream.read(buffer)
                    if (count < 0) break
                    digest.update(buffer, 0, count)
                    output.write(buffer, 0, count)
                }
            }
        }
        val actual = digest.digest().hex()
        require(expectedSha256.isNullOrBlank() || actual.equals(expectedSha256, ignoreCase = true)) {
            temporary.delete()
            "Graph checksum mismatch: expected=$expectedSha256 actual=$actual"
        }
        val validation = runCatching { inspectGraph(temporary) }.getOrElse {
            deleteSidecars(temporary)
            temporary.delete()
            throw it
        }
        deleteSidecars(temporary)
        deleteSidecars(target)
        if (target.exists() && !target.delete()) error("Could not replace old graph package")
        if (!temporary.renameTo(target)) error("Could not finalize graph package")
        val installedAt = System.currentTimeMillis()
        writeMetadata(
            JSONObject()
                .put("schemaVersion", 1)
                .put("graphVersion", validation.graphVersion)
                .put("sha256", actual)
                .put("bytes", target.length())
                .put("installedAt", installedAt)
                .put("source", source)
                .put("contextRows", validation.contextRows)
                .put("universeTerms", validation.universeTerms)
                .put("grammarNodes", validation.grammarNodes)
                .put("cooccurrenceRows", validation.cooccurrenceRows)
        )
        return status()
    }

    fun deleteGraph(): Boolean {
        metadataFile.delete()
        deleteSidecars(graphFile)
        return !graphFile.exists() || graphFile.delete()
    }

    fun importDictionary(fileName: String, input: InputStream): File {
        require(fileName in setOf("Names.txt", "VietPhrase.txt", "ChinesePhienAmWords.txt"))
        val target = File(context.filesDir, "translate/vietphrase/$fileName")
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.outputStream().buffered().use { output -> input.buffered().use { it.copyTo(output) } }
        if (target.exists() && !target.delete()) error("Could not replace dictionary: $fileName")
        if (!temporary.renameTo(target)) error("Could not finalize dictionary: $fileName")
        return target
    }

    private fun inspectGraph(file: File): GraphValidation {
        require(file.isFile && file.length() > 0) { "Graph package is empty" }
        return SQLiteDatabase.openDatabase(file.path, null, SQLiteDatabase.OPEN_READONLY).use { db ->
            val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", emptyArray()).use { cursor ->
                buildSet {
                    while (cursor.moveToNext()) add(cursor.getString(0))
                }
            }
            require(REQUIRED_TABLES.all(tables::contains)) {
                "Invalid graph package: missing ${REQUIRED_TABLES.filterNot(tables::contains).joinToString()}"
            }
            val graphVersion = db.rawQuery("SELECT version FROM graph_versions WHERE id = 1", emptyArray()).use {
                require(it.moveToFirst()) { "Invalid graph package: missing graph version" }
                it.getLong(0).toString()
            }
            GraphValidation(
                graphVersion = graphVersion,
                contextRows = db.countOrZero("SELECT COUNT(*) FROM graph_context_index"),
                universeTerms = db.countOrZero("SELECT COUNT(*) FROM graph_term_index WHERE universe <> ''"),
                grammarNodes = db.countOrZero(
                    "SELECT COUNT(*) FROM graph_nodes WHERE primary_label = 'GrammarRule' OR labels_json LIKE '%GrammarRule%'"
                ),
                cooccurrenceRows = if ("graph_cooccurrence" in tables) {
                    db.countOrZero("SELECT COUNT(*) FROM graph_cooccurrence")
                } else {
                    0
                }
            )
        }
    }

    private fun SQLiteDatabase.countOrZero(query: String): Int = runCatching {
        rawQuery(query, emptyArray()).use { cursor -> if (cursor.moveToFirst()) cursor.getInt(0) else 0 }
    }.getOrDefault(0)

    private fun readMetadata(): JSONObject? = runCatching {
        metadataFile.takeIf(File::isFile)?.readText(Charsets.UTF_8)?.let(::JSONObject)
    }.getOrNull()

    private fun writeMetadata(metadata: JSONObject) {
        val target = metadataFile
        target.parentFile?.mkdirs()
        val temporary = File(target.parentFile, "${target.name}.tmp")
        temporary.writeText(metadata.toString(2), Charsets.UTF_8)
        if (target.exists() && !target.delete()) error("Could not replace graph metadata")
        if (!temporary.renameTo(target)) error("Could not finalize graph metadata")
    }

    private fun ByteArray.hex(): String = joinToString("") { "%02x".format(it) }

    companion object {
        private val REQUIRED_TABLES = setOf(
            "graph_nodes",
            "graph_node_labels",
            "graph_term_index",
            "graph_context_index",
            "graph_node_ids",
            "graph_edges_compact",
            "graph_manifest",
            "graph_versions"
        )

        private fun capabilityWarnings(
            contextRows: Int,
            universeTerms: Int,
            grammarNodes: Int,
            cooccurrenceRows: Int
        ) = buildList {
            if (grammarNodes == 0) add("Grammar rules are unavailable.")
            if (contextRows == 0 || universeTerms == 0) add("Context-universe data is unavailable.")
            if (cooccurrenceRows == 0) add("Entity co-occurrence data is unavailable.")
        }

        private fun deleteSidecars(file: File) {
            File("${file.path}-wal").delete()
            File("${file.path}-shm").delete()
        }
    }
}
