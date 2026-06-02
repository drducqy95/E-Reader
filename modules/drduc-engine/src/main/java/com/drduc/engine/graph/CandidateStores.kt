package com.drduc.engine.graph

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import com.drduc.engine.CandidateSource
import com.drduc.engine.ChinesePos
import com.drduc.engine.GrammarConverter
import com.drduc.engine.GrammarPattern
import com.drduc.engine.PosToken
import com.drduc.engine.TranslationCandidate
import com.drduc.engine.TranslationConfig
import java.io.File
import java.security.MessageDigest
import java.text.Normalizer
import java.util.concurrent.atomic.AtomicLong
import org.json.JSONArray
import org.json.JSONObject

interface CandidateStore {
    fun candidates(text: String, start: Int, maxChars: Int): List<TranslationCandidate>
}

class MobileGraphStore(private val file: File?) : CandidateStore, AutoCloseable {
    private var openedAt: Long = Long.MIN_VALUE
    private var openedDatabase: SQLiteDatabase? = null
    private val contextCache = mutableMapOf<String, IndexedContext>()
    private var hasUniverseTerms: Boolean? = null
    private var cachedGrammarPatterns: List<GrammarPattern>? = null

    @Synchronized
    private fun database(): SQLiteDatabase? {
        val graph = file?.takeIf(File::isFile) ?: return null
        val modifiedAt = graph.lastModified()
        if (openedDatabase == null || openedAt != modifiedAt) {
            openedDatabase?.close()
            openedDatabase = SQLiteDatabase.openDatabase(graph.path, null, SQLiteDatabase.OPEN_READONLY)
            openedAt = modifiedAt
            contextCache.clear()
            hasUniverseTerms = null
            cachedGrammarPatterns = null
        }
        return openedDatabase
    }

    val graphVersion: String
        get() {
            val db = database() ?: return "none"
            return runCatching {
                db.rawQuery("SELECT version FROM graph_versions WHERE id = 1", emptyArray()).use {
                    if (it.moveToFirst()) it.getLong(0).toString() else "0"
                }
            }.getOrDefault("unknown")
        }

    override fun candidates(text: String, start: Int, maxChars: Int): List<TranslationCandidate> {
        val db = database() ?: return emptyList()
        if (start >= text.length) return emptyList()
        val terms = (1..minOf(maxChars, text.length - start))
            .map { text.substring(start, start + it) }
        if (terms.isEmpty()) return emptyList()
        val placeholders = terms.joinToString(",") { "?" }
        val hasNodes = tableExists(db, "graph_nodes")
        val metadataColumn = if (hasNodes) "n.properties_json" else "'{}'"
        val nodeJoin = if (hasNodes) "LEFT JOIN graph_nodes n ON n.id = t.node_id" else ""
        val sql = """
            SELECT t.normalized_source, t.target, t.surface, t.node_id, t.priority, t.confidence,
                   t.graph_layer, t.universe, t.domain, ${optionalColumn(db, "graph_term_index", "scope", "''")},
                   ${optionalColumn(db, "graph_term_index", "kind", "''")}, $metadataColumn, t.max_len
            FROM graph_term_index t
            $nodeJoin
            WHERE t.normalized_source IN ($placeholders)
              AND t.lang IN ('', 'zh', 'zh-CN', 'zh-Hans')
              AND t.status IN ('active', 'approved', 'locked')
            ORDER BY t.max_len DESC, t.priority DESC, t.confidence DESC
            LIMIT 96
        """.trimIndent()
        return runCatching {
            db.rawQuery(sql, terms.toTypedArray()).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val source = cursor.getString(0)
                        val target = cursor.getString(2).ifBlank { cursor.getString(1) }
                        if (source.isNotBlank() && target.isNotBlank()) {
                            val priority = cursor.getInt(4)
                            val confidence = cursor.getDouble(5)
                            val properties = runCatching { JSONObject(cursor.getString(11) ?: "{}") }.getOrDefault(JSONObject())
                            add(
                                TranslationCandidate(
                                    sourceText = source,
                                    targetText = target,
                                    source = CandidateSource.DRDUC_GRAPH,
                                    score = 4.0 + confidence + priority * 0.01,
                                    nodeId = cursor.getString(3),
                                    priority = priority,
                                    confidence = confidence,
                                    universe = cursor.getString(7) ?: "",
                                    domain = cursor.getString(8) ?: "",
                                    scope = cursor.getString(9) ?: "",
                                    entityType = normalizeEntityType(properties.optString("entity_type")),
                                    posTag = properties.optString("pos_tag"),
                                    posSub = properties.optString("pos_sub"),
                                    grammarRole = properties.optString("reorder_role").ifBlank { properties.optString("luat_nhan_trigger") },
                                    contextMarkers = properties.stringList("context_markers"),
                                    negativeContextMarkers = properties.stringList("negative_context_markers"),
                                    coOccurringEntities = properties.stringList("co_occurring_entities"),
                                    reason = cursor.getString(6) ?: "graph"
                                )
                            )
                        }
                    }
                }
            }
        }.getOrDefault(emptyList())
    }

    private fun tableExists(db: SQLiteDatabase, table: String): Boolean =
        db.rawQuery("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?", arrayOf(table)).use { it.moveToFirst() }

    private fun optionalColumn(db: SQLiteDatabase, table: String, column: String, fallback: String): String {
        val exists = db.rawQuery("PRAGMA table_info($table)", emptyArray()).use { cursor ->
            generateSequence { if (cursor.moveToNext()) cursor.getString(1) else null }.any { it == column }
        }
        return if (exists) "t.$column" else fallback
    }

    fun detectUniverses(text: String, maxChars: Int = 24): List<String> {
        val db = database() ?: return emptyList()
        if (!universeTermsAvailable(db)) return emptyList()
        val terms = text.indices.flatMap { start ->
            (1..minOf(maxChars, MAX_UNIVERSE_TERM_CHARS, text.length - start)).map { length -> text.substring(start, start + length) }
        }.distinct()
        if (terms.isEmpty()) return emptyList()
        val counts = linkedMapOf<String, Int>()
        terms.chunked(500).forEach { chunk ->
            val placeholders = chunk.joinToString(",") { "?" }
            runCatching {
                db.rawQuery(
                    "SELECT source, universe FROM graph_term_index WHERE normalized_source IN ($placeholders) AND universe <> '' AND kind IN ('entity', 'lexeme', 'alias')",
                    chunk.toTypedArray()
                ).use { cursor ->
                    while (cursor.moveToNext()) {
                        val source = cursor.getString(0).orEmpty()
                        val universe = canonicalUniverse(cursor.getString(1).orEmpty())
                        if (source.isNotBlank() && universe.isNotBlank() && source in text) counts[universe] = (counts[universe] ?: 0) + 1
                    }
                }
            }
        }
        return counts.entries.sortedByDescending(Map.Entry<String, Int>::value)
            .filter { it.value >= 2 }
            .take(3)
            .map(Map.Entry<String, Int>::key)
    }

    fun applyContext(candidate: TranslationCandidate, config: TranslationConfig): TranslationCandidate {
        if (candidate.source !in setOf(CandidateSource.DRDUC_GRAPH, CandidateSource.PROJECT_OVERLAY)) return candidate
        val indexed = if (candidate.source == CandidateSource.DRDUC_GRAPH) indexedContext(candidate.nodeId) else IndexedContext()
        val universe = canonicalUniverse(candidate.universe.ifBlank { indexed.universe })
        val active = config.activeUniverses.map(::canonicalUniverse).toSet()
        val detected = config.detectedUniverses.map(::canonicalUniverse).toSet()
        val blocked = config.blockedUniverses.map(::canonicalUniverse).toSet()
        val window = listOf(config.contextWindow, config.contextMarkers.joinToString(" ")).joinToString(" ")
        var contextScore = 0.0
        var penalty = 0.0
        if (universe.isNotBlank()) {
            when {
                universe in blocked -> penalty -= 8.0
                universe in active -> contextScore += 2.0
                universe in detected -> contextScore += 1.0
                active.isNotEmpty() || detected.isNotEmpty() -> penalty -= 3.0
                candidate.scope == "universe" -> penalty -= 2.0
            }
        }
        var markerHits = 0
        (indexed.contextMarkers + candidate.contextMarkers).distinct().forEach {
            if (it.isNotBlank() && it in window) {
                markerHits++
                contextScore += 0.35
            }
        }
        (indexed.negativeContextMarkers + candidate.negativeContextMarkers).distinct()
            .forEach { if (it.isNotBlank() && it in window) penalty -= 2.5 }
        (indexed.coOccurringEntities + candidate.coOccurringEntities).distinct()
            .forEach { if (it.isNotBlank() && it in window) contextScore += 0.2 }
        if (candidate.surfaceNote.isNotBlank() && candidate.targetText.isNotBlank()) penalty -= 0.9
        return candidate.copy(
            score = candidate.score + contextScore + penalty,
            universe = universe,
            contextScore = contextScore,
            penalty = penalty,
            contextMarkers = (indexed.contextMarkers + candidate.contextMarkers).distinct(),
            negativeContextMarkers = (indexed.negativeContextMarkers + candidate.negativeContextMarkers).distinct(),
            coOccurringEntities = (indexed.coOccurringEntities + candidate.coOccurringEntities).distinct(),
            scoreBreakdown = mapOf(
                "base" to candidate.score,
                "context_score" to contextScore,
                "penalty" to penalty,
                "marker_hits" to markerHits.toDouble(),
                "total" to candidate.score + contextScore + penalty
            )
        )
    }

    fun posTokens(text: String, maxChars: Int = 24): List<PosToken> {
        val tokens = mutableListOf<PosToken>()
        var index = 0
        while (index < text.length) {
            val char = text[index]
            if (char.isWhitespace()) {
                index++
                continue
            }
            if (!char.isCjk()) {
                tokens += PosToken(char.toString(), index, index + 1, if (char.isLetterOrDigit()) ChinesePos.LITERAL else ChinesePos.PUNCTUATION, 0.4)
                index++
                continue
            }
            val candidate = candidates(text, index, maxChars).maxWithOrNull(
                compareBy<TranslationCandidate> { it.sourceText.length }.thenBy { it.score }
            )
            if (candidate == null) {
                tokens += PosToken(char.toString(), index, index + 1, heuristicPos(char.toString()), 0.25)
                index++
            } else {
                val pos = resolvePos(candidate)
                tokens += PosToken(
                    candidate.sourceText, index, index + candidate.sourceText.length, pos,
                    candidate.confidence.coerceAtLeast(0.2), candidate.entityType, candidate.reason,
                    candidate.sourceText in functionWords, candidate.grammarRole
                )
                index += candidate.sourceText.length
            }
        }
        return tokens
    }

    fun grammarPatterns(): List<GrammarPattern> {
        cachedGrammarPatterns?.let { return it }
        val db = database() ?: return emptyList()
        return runCatching {
            db.rawQuery(
                "SELECT properties_json FROM graph_nodes WHERE primary_label = 'GrammarRule' OR labels_json LIKE '%GrammarRule%'",
                emptyArray()
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        GrammarConverter.patternFromJson(JSONObject(cursor.getString(0))).let { pattern ->
                            if (pattern != null) add(pattern)
                        }
                    }
                }
            }
        }.getOrDefault(emptyList()).also { cachedGrammarPatterns = it }
    }

    private fun universeTermsAvailable(db: SQLiteDatabase): Boolean {
        hasUniverseTerms?.let { return it }
        return runCatching {
            db.rawQuery("SELECT 1 FROM graph_term_index WHERE universe <> '' LIMIT 1", emptyArray()).use { it.moveToFirst() }
        }.getOrDefault(false).also { hasUniverseTerms = it }
    }

    private fun indexedContext(nodeId: String): IndexedContext {
        if (nodeId.isBlank()) return IndexedContext()
        contextCache[nodeId]?.let { return it }
        val db = database() ?: return IndexedContext()
        val result = IndexedContext()
        runCatching {
            db.rawQuery(
                "SELECT marker_type, marker, universe FROM graph_context_index WHERE node_id = ?",
                arrayOf(nodeId)
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val marker = cursor.getString(1).orEmpty()
                    if (result.universe.isBlank()) result.universe = cursor.getString(2).orEmpty()
                    when (cursor.getString(0).orEmpty()) {
                        "context" -> result.contextMarkers += marker
                        "negative_context" -> result.negativeContextMarkers += marker
                        "co_entity" -> result.coOccurringEntities += marker
                    }
                }
            }
        }
        contextCache[nodeId] = result
        return result
    }

    private fun resolvePos(candidate: TranslationCandidate): ChinesePos {
        val explicit = GrammarConverter.normalizePos(candidate.posTag)
            ?: GrammarConverter.normalizePos(candidate.posSub)
        if (explicit != null) return explicit
        if (candidate.sourceText in prepositions) return ChinesePos.PREPOSITION
        if (candidate.sourceText in functionWords) return ChinesePos.FUNCTION
        return when (candidate.entityType) {
            "person", "location", "organization", "realm", "faction", "artifact", "weapon", "technique" -> ChinesePos.PROPER_NOUN
            "project_term", "term" -> ChinesePos.NOUN
            else -> heuristicPos(candidate.sourceText)
        }
    }

    private fun heuristicPos(text: String): ChinesePos = when {
        text.matches(Regex("[0-9\u4e00\u4e8c\u4e09\u56db\u4e94\u516d\u4e03\u516b\u4e5d\u5341\u767e\u5343\u4e07]+")) -> ChinesePos.NUMBER
        text in prepositions -> ChinesePos.PREPOSITION
        text in functionWords -> ChinesePos.FUNCTION
        else -> ChinesePos.UNKNOWN
    }

    override fun close() {
        openedDatabase?.close()
        openedDatabase = null
        openedAt = Long.MIN_VALUE
        contextCache.clear()
        hasUniverseTerms = null
        cachedGrammarPatterns = null
    }

    private data class IndexedContext(
        var universe: String = "",
        val contextMarkers: MutableList<String> = mutableListOf(),
        val negativeContextMarkers: MutableList<String> = mutableListOf(),
        val coOccurringEntities: MutableList<String> = mutableListOf()
    )

    companion object {
        private val functionWords = setOf("\u7684", "\u4e86", "\u7740", "\u8fc7", "\u628a", "\u88ab", "\u6bd4", "\u5728", "\u4ece", "\u5411", "\u4e0e", "\u548c", "\u800c", "\u4f46", "\u56e0\u4e3a", "\u6240\u4ee5")
        private val prepositions = setOf("\u628a", "\u88ab", "\u6bd4", "\u5728", "\u4ece", "\u5411", "\u5bf9", "\u7ed9", "\u4e3a")
        private const val MAX_UNIVERSE_TERM_CHARS = 8
        private fun normalizeEntityType(value: String): String = when (value.trim().lowercase().replace("-", "_").replace(" ", "_")) {
            "character", "characters", "char", "npc", "human", "people", "proper_name", "name" -> "person"
            "place", "places", "loc", "city", "country" -> "location"
            "org", "organisation", "sect", "guild", "school" -> "organization"
            "item", "treasure" -> "artifact"
            "skill", "ability", "martial_art" -> "technique"
            else -> value.trim().lowercase().replace("-", "_").replace(" ", "_")
        }
        private fun canonicalUniverse(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFKD)
            .replace(Regex("\\p{M}+"), "")
            .replace(Regex("[^a-zA-Z0-9]+"), "_")
            .trim('_')
            .lowercase()
        private fun Char.isCjk(): Boolean = code in 0x3400..0x9fff
        private fun JSONObject.stringList(key: String): List<String> = optJSONArray(key)?.let { array ->
            (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
        }.orEmpty()
    }
}

class OverlayGraphStore(context: Context) : CandidateStore, AutoCloseable {
    private val database = context.openOrCreateDatabase("drduc_overlay.sqlite", Context.MODE_PRIVATE, null)
    private val version: AtomicLong

    init {
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS overlay_terms (
              source TEXT PRIMARY KEY,
              target TEXT NOT NULL,
              priority INTEGER NOT NULL DEFAULT 100,
              confidence REAL NOT NULL DEFAULT 1.0,
              status TEXT NOT NULL DEFAULT 'approved',
              universe TEXT NOT NULL DEFAULT '',
              domain TEXT NOT NULL DEFAULT '',
              scope TEXT NOT NULL DEFAULT '',
              entity_type TEXT NOT NULL DEFAULT '',
              pos_tag TEXT NOT NULL DEFAULT '',
              pos_sub TEXT NOT NULL DEFAULT '',
              grammar_role TEXT NOT NULL DEFAULT '',
              context_markers TEXT NOT NULL DEFAULT '[]',
              negative_context_markers TEXT NOT NULL DEFAULT '[]',
              co_occurring_entities TEXT NOT NULL DEFAULT '[]'
            )
            """.trimIndent()
        )
        ensureOverlayColumns()
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS translation_memory (
              source TEXT PRIMARY KEY,
              target TEXT NOT NULL,
              human_reviewed INTEGER NOT NULL DEFAULT 0,
              updated_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS overlay_metadata (
              key TEXT PRIMARY KEY,
              value TEXT NOT NULL
            )
            """.trimIndent()
        )
        database.execSQL("INSERT OR IGNORE INTO overlay_metadata(key, value) VALUES ('version', '0')")
        database.execSQL(
            """
            CREATE TABLE IF NOT EXISTS applied_overlay_deltas (
              delta_version TEXT PRIMARY KEY,
              base_graph_version TEXT NOT NULL,
              sha256 TEXT NOT NULL,
              entry_count INTEGER NOT NULL,
              term_count INTEGER NOT NULL,
              tm_count INTEGER NOT NULL,
              applied_at INTEGER NOT NULL
            )
            """.trimIndent()
        )
        version = AtomicLong(
            database.rawQuery("SELECT value FROM overlay_metadata WHERE key = 'version'", emptyArray()).use {
                if (it.moveToFirst()) it.getString(0).toLongOrNull() ?: 0 else 0
            }
        )
    }

    val overlayVersion: Long get() = version.get()

    fun status(): OverlayRuntimeStatus = OverlayRuntimeStatus(
        overlayVersion = overlayVersion,
        termCount = count("overlay_terms"),
        reviewedTmCount = count("translation_memory", "human_reviewed = 1"),
        appliedDeltas = database.rawQuery(
            """
            SELECT delta_version, base_graph_version, sha256, entry_count, term_count, tm_count, applied_at
            FROM applied_overlay_deltas
            ORDER BY applied_at DESC, delta_version DESC
            """.trimIndent(),
            emptyArray()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(
                        AppliedOverlayDelta(
                            deltaVersion = cursor.getString(0),
                            baseGraphVersion = cursor.getString(1),
                            sha256 = cursor.getString(2),
                            entryCount = cursor.getInt(3),
                            termCount = cursor.getInt(4),
                            tmCount = cursor.getInt(5),
                            appliedAt = cursor.getLong(6)
                        )
                    )
                }
            }
        }
    )

    fun importReviewedDelta(manifest: JSONObject, currentGraphVersion: String): OverlayRuntimeStatus {
        val delta = OverlayDeltaManifest.parse(manifest, currentGraphVersion)
        existingDeltaSha256(delta.deltaVersion)?.let { existingSha256 ->
            require(existingSha256 == delta.sha256) {
                "Overlay delta ${delta.deltaVersion} was already applied with a different checksum"
            }
            return status()
        }
        val appliedAt = System.currentTimeMillis()
        val termCount = delta.entries.count { it.type == "term" }
        val tmCount = delta.entries.count { it.type == "tm" }
        var nextVersion = overlayVersion
        database.beginTransaction()
        try {
            delta.entries.forEach { entry ->
                when (entry.type) {
                    "term" -> writeApprovedTerm(entry)
                    "tm" -> writeReviewedTranslationMemory(entry.source, entry.target, appliedAt)
                }
            }
            database.execSQL(
                """
                INSERT INTO applied_overlay_deltas(
                  delta_version, base_graph_version, sha256, entry_count, term_count, tm_count, applied_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf<Any>(
                    delta.deltaVersion, delta.baseGraphVersion, delta.sha256, delta.entries.size,
                    termCount, tmCount, appliedAt
                )
            )
            nextVersion = overlayVersion + 1
            database.execSQL(
                "UPDATE overlay_metadata SET value = ? WHERE key = 'version'",
                arrayOf<Any>(nextVersion.toString())
            )
            database.setTransactionSuccessful()
        } finally {
            database.endTransaction()
        }
        version.set(nextVersion)
        return status()
    }

    fun exactTranslationMemory(source: String): String? =
        database.rawQuery("SELECT target FROM translation_memory WHERE source = ? AND human_reviewed = 1", arrayOf(source)).use {
            if (it.moveToFirst()) it.getString(0) else null
        }

    fun recordMachineTranslation(source: String, target: String) {
        database.execSQL(
            "INSERT OR IGNORE INTO translation_memory(source, target, human_reviewed, updated_at) VALUES (?, ?, 0, ?)",
            arrayOf<Any>(source, target, System.currentTimeMillis())
        )
    }

    fun upsertApprovedTerm(
        source: String,
        target: String,
        priority: Int = 100,
        confidence: Double = 1.0,
        universe: String = "",
        domain: String = "",
        scope: String = "",
        entityType: String = "",
        posTag: String = "",
        posSub: String = "",
        grammarRole: String = "",
        contextMarkers: List<String> = emptyList(),
        negativeContextMarkers: List<String> = emptyList(),
        coOccurringEntities: List<String> = emptyList()
    ) {
        writeApprovedTerm(
            OverlayDeltaEntry(
                type = "term",
                source = source,
                target = target,
                priority = priority,
                confidence = confidence,
                universe = universe,
                domain = domain,
                scope = scope,
                entityType = entityType,
                posTag = posTag,
                posSub = posSub,
                grammarRole = grammarRole,
                contextMarkers = contextMarkers,
                negativeContextMarkers = negativeContextMarkers,
                coOccurringEntities = coOccurringEntities
            )
        )
        val nextVersion = version.incrementAndGet()
        database.execSQL(
            "UPDATE overlay_metadata SET value = ? WHERE key = 'version'",
            arrayOf<Any>(nextVersion.toString())
        )
    }

    override fun candidates(text: String, start: Int, maxChars: Int): List<TranslationCandidate> {
        val terms = (1..minOf(maxChars, text.length - start))
            .map { text.substring(start, start + it) }
        if (terms.isEmpty()) return emptyList()
        val placeholders = terms.joinToString(",") { "?" }
        return database.rawQuery(
            """
            SELECT source, target, priority, confidence, universe, domain, scope, entity_type,
                   pos_tag, pos_sub, grammar_role, context_markers, negative_context_markers,
                   co_occurring_entities
            FROM overlay_terms
            WHERE source IN ($placeholders) AND status = 'approved'
            ORDER BY length(source) DESC, priority DESC
            """.trimIndent(),
            terms.toTypedArray()
        ).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    val priority = cursor.getInt(2)
                    val confidence = cursor.getDouble(3)
                    add(
                        TranslationCandidate(
                            sourceText = cursor.getString(0),
                            targetText = cursor.getString(1),
                            source = CandidateSource.PROJECT_OVERLAY,
                            score = 8.0 + confidence + priority * 0.01,
                            priority = priority,
                            confidence = confidence,
                            universe = cursor.getString(4),
                            domain = cursor.getString(5),
                            scope = cursor.getString(6),
                            entityType = cursor.getString(7),
                            posTag = cursor.getString(8),
                            posSub = cursor.getString(9),
                            grammarRole = cursor.getString(10),
                            contextMarkers = cursor.jsonList(11),
                            negativeContextMarkers = cursor.jsonList(12),
                            coOccurringEntities = cursor.jsonList(13),
                            reason = "approved overlay"
                        )
                    )
                }
            }
        }
    }

    override fun close() {
        database.close()
    }

    private fun writeApprovedTerm(entry: OverlayDeltaEntry) {
        database.execSQL(
            """
            INSERT INTO overlay_terms(
              source, target, priority, confidence, status, universe, domain, scope,
              entity_type, pos_tag, pos_sub, grammar_role, context_markers,
              negative_context_markers, co_occurring_entities
            )
            VALUES (?, ?, ?, ?, 'approved', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            ON CONFLICT(source) DO UPDATE SET
              target = excluded.target,
              priority = excluded.priority,
              confidence = excluded.confidence,
              status = excluded.status,
              universe = excluded.universe,
              domain = excluded.domain,
              scope = excluded.scope,
              entity_type = excluded.entity_type,
              pos_tag = excluded.pos_tag,
              pos_sub = excluded.pos_sub,
              grammar_role = excluded.grammar_role,
              context_markers = excluded.context_markers,
              negative_context_markers = excluded.negative_context_markers,
              co_occurring_entities = excluded.co_occurring_entities
            """.trimIndent(),
            arrayOf<Any>(
                entry.source, entry.target, entry.priority, entry.confidence, entry.universe,
                entry.domain, entry.scope, entry.entityType, entry.posTag, entry.posSub,
                entry.grammarRole, JSONArray(entry.contextMarkers).toString(),
                JSONArray(entry.negativeContextMarkers).toString(),
                JSONArray(entry.coOccurringEntities).toString()
            )
        )
    }

    private fun writeReviewedTranslationMemory(source: String, target: String, updatedAt: Long) {
        database.execSQL(
            """
            INSERT INTO translation_memory(source, target, human_reviewed, updated_at)
            VALUES (?, ?, 1, ?)
            ON CONFLICT(source) DO UPDATE SET
              target = excluded.target,
              human_reviewed = 1,
              updated_at = excluded.updated_at
            """.trimIndent(),
            arrayOf<Any>(source, target, updatedAt)
        )
    }

    private fun existingDeltaSha256(deltaVersion: String): String? =
        database.rawQuery(
            "SELECT sha256 FROM applied_overlay_deltas WHERE delta_version = ?",
            arrayOf(deltaVersion)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun count(table: String, where: String = "1 = 1"): Int =
        database.rawQuery("SELECT COUNT(*) FROM $table WHERE $where", emptyArray()).use { cursor ->
            if (cursor.moveToFirst()) cursor.getInt(0) else 0
        }

    private fun ensureOverlayColumns() {
        val columns = database.rawQuery("PRAGMA table_info(overlay_terms)", emptyArray()).use { cursor ->
            buildSet { while (cursor.moveToNext()) add(cursor.getString(1)) }
        }
        overlayColumns.forEach { (name, definition) ->
            if (name !in columns) database.execSQL("ALTER TABLE overlay_terms ADD COLUMN $name $definition")
        }
    }

    private fun android.database.Cursor.jsonList(index: Int): List<String> =
        runCatching {
            JSONArray(getString(index)).let { array ->
                (0 until array.length()).mapNotNull { array.optString(it).takeIf(String::isNotBlank) }
            }
        }.getOrDefault(emptyList())

    companion object {
        private val overlayColumns = linkedMapOf(
            "universe" to "TEXT NOT NULL DEFAULT ''",
            "domain" to "TEXT NOT NULL DEFAULT ''",
            "scope" to "TEXT NOT NULL DEFAULT ''",
            "entity_type" to "TEXT NOT NULL DEFAULT ''",
            "pos_tag" to "TEXT NOT NULL DEFAULT ''",
            "pos_sub" to "TEXT NOT NULL DEFAULT ''",
            "grammar_role" to "TEXT NOT NULL DEFAULT ''",
            "context_markers" to "TEXT NOT NULL DEFAULT '[]'",
            "negative_context_markers" to "TEXT NOT NULL DEFAULT '[]'",
            "co_occurring_entities" to "TEXT NOT NULL DEFAULT '[]'"
        )
    }
}

data class AppliedOverlayDelta(
    val deltaVersion: String,
    val baseGraphVersion: String,
    val sha256: String,
    val entryCount: Int,
    val termCount: Int,
    val tmCount: Int,
    val appliedAt: Long
)

data class OverlayRuntimeStatus(
    val overlayVersion: Long,
    val termCount: Int,
    val reviewedTmCount: Int,
    val appliedDeltas: List<AppliedOverlayDelta>
)

class LegadoDictionaryCandidateStore(context: Context) : CandidateStore {
    private val dictionaryDir = File(context.filesDir, "translate/vietphrase")
    @Volatile private var snapshot: DictionarySnapshot? = null

    override fun candidates(text: String, start: Int, maxChars: Int): List<TranslationCandidate> {
        val data = data()
        val max = minOf(maxChars, text.length - start)
        val result = mutableListOf<TranslationCandidate>()
        longest(data.names, text, start, max)?.let { (source, target) ->
            result += TranslationCandidate(source, target.firstAlternative(), CandidateSource.LEGADO_NAMES, 2.8, fallbackLevel = 1, reason = "Legado Names import")
        }
        longest(data.vietPhrase, text, start, max)?.let { (source, target) ->
            result += TranslationCandidate(source, target.firstAlternative(), CandidateSource.LEGADO_VIETPHRASE, 2.0, fallbackLevel = 2, reason = "Legado VietPhrase import")
        }
        if (start < text.length) {
            val char = text[start].toString()
            data.hanViet[char]?.let {
                result += TranslationCandidate(char, it, CandidateSource.HAN_VIET, 0.8, fallbackLevel = 3, reason = "Han-Viet reading")
            }
        }
        return result
    }

    fun dictionaryVersion(): String = data().version

    fun translateVietPhrase(text: String, maxChars: Int = 24): String =
        translate(text, maxChars, useVietPhrase = true)

    fun translateHanViet(text: String): String =
        translate(text, 1, useVietPhrase = false)

    fun invalidate() {
        snapshot = null
    }

    private fun translate(text: String, maxChars: Int, useVietPhrase: Boolean): String {
        val data = data()
        val output = StringBuilder()
        var index = 0
        while (index < text.length) {
            val char = text[index]
            punctuation[char]?.let {
                output.append(it)
                index++
                return@let
            }
            if (punctuation.containsKey(char)) continue
            if (!char.isCjk()) {
                output.append(char)
                index++
                continue
            }
            val max = minOf(maxChars, text.length - index)
            val match = if (useVietPhrase) {
                longest(data.names, text, index, max) ?: longest(data.vietPhrase, text, index, max)
            } else null
            if (match != null) {
                appendWord(output, match.second.firstAlternative())
                index += match.first.length
            } else {
                appendWord(output, data.hanViet[char.toString()] ?: char.toString())
                index++
            }
        }
        return output.toString().replace(Regex(" +([,.?!:;])"), "$1").replace(Regex(" {2,}"), " ").trim()
    }

    private fun appendWord(output: StringBuilder, word: String) {
        if (word.isBlank()) return
        if (output.isNotEmpty() && !output.last().isWhitespace() && output.last() !in "([{\"") output.append(' ')
        output.append(word)
        if (output.lastOrNull()?.isLetterOrDigit() == true) output.append(' ')
    }

    @Synchronized
    private fun data(): DictionarySnapshot {
        val version = fingerprint()
        snapshot?.takeIf { it.version == version }?.let { return it }
        return DictionarySnapshot(
            version = version,
            names = loadKeyValue(File(dictionaryDir, "Names.txt")),
            vietPhrase = loadKeyValue(File(dictionaryDir, "VietPhrase.txt")),
            hanViet = loadKeyValue(File(dictionaryDir, "ChinesePhienAmWords.txt"))
        ).also { snapshot = it }
    }

    private fun fingerprint(): String {
        val value = DICTIONARY_FILES.joinToString("|") { name ->
            File(dictionaryDir, name).let { "$name:${it.length()}:${it.lastModified()}" }
        }
        return MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
            .joinToString("") { "%02x".format(it) }.take(16)
    }

    private fun longest(map: Map<String, String>, text: String, start: Int, max: Int): Pair<String, String>? {
        for (length in max downTo 1) {
            val source = text.substring(start, start + length)
            map[source]?.let { return source to it }
        }
        return null
    }

    private fun loadKeyValue(file: File): Map<String, String> {
        if (!file.isFile) return emptyMap()
        return file.useLines(Charsets.UTF_8) { lines ->
            lines.mapNotNull { line ->
                val index = line.indexOf('=')
                if (index <= 0) null else line.substring(0, index).trim() to line.substring(index + 1).trim()
            }.filter { it.first.isNotEmpty() && it.second.isNotEmpty() }.toMap()
        }
    }

    private fun String.firstAlternative(): String = substringBefore('/').trim()

    private fun Char.isCjk(): Boolean = code in 0x3400..0x9fff

    private data class DictionarySnapshot(
        val version: String,
        val names: Map<String, String>,
        val vietPhrase: Map<String, String>,
        val hanViet: Map<String, String>
    )

    companion object {
        private val DICTIONARY_FILES = listOf("Names.txt", "VietPhrase.txt", "ChinesePhienAmWords.txt")
        private val punctuation = mapOf(
            '。' to ". ", '，' to ", ", '、' to ", ", '；' to "; ", '：' to ": ",
            '！' to "! ", '？' to "? ", '…' to "...", '　' to " "
        )
    }
}
