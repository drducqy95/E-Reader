package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.drduc.engine.ChinesePos
import com.drduc.engine.DrDucGraphTranslationEngine
import com.drduc.engine.GrammarConverter
import com.drduc.engine.PosToken
import com.drduc.engine.TraceLevel
import com.drduc.engine.TranslationConfig
import com.drduc.engine.TranslationRequest
import com.drduc.engine.graph.LegadoDictionaryCandidateStore
import com.drduc.engine.graph.MobileGraphStore
import com.drduc.engine.graph.OverlayGraphStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DrDucParityContextGrammarTest {
    @Test
    fun activeUniverseAndIndexedMarkersUsePythonWeights() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("drduc_overlay.sqlite")
        val graph = File(context.cacheDir, "context-graph.sqlite").apply { delete() }
        SQLiteDatabase.openOrCreateDatabase(graph, null).use(::createContextFixture)
        val store = MobileGraphStore(graph)
        val overlay = OverlayGraphStore(context)
        try {
            val result = DrDucGraphTranslationEngine(store, overlay, LegadoDictionaryCandidateStore(context)).translate(
                TranslationRequest(
                    text = "\u7532",
                    config = TranslationConfig(activeUniverses = listOf("Alpha"), contextWindow = "\u76df\u53cb"),
                    traceLevel = TraceLevel.FULL
                )
            )
            val selected = checkNotNull(result.trace).segments.single().candidate
            assertEquals("alpha", selected.universe)
            assertEquals(2.55, selected.contextScore, 0.0001)
            assertEquals(0.0, selected.penalty, 0.0001)
            assertEquals("an", selected.targetText)
            assertEquals(listOf("\u76df\u53cb"), selected.contextMarkers)
            assertEquals(listOf("\u76df\u53cb"), selected.coOccurringEntities)
        } finally {
            store.close()
            overlay.close()
        }
    }

    @Test
    fun reviewedOverlayKeepsUniversePosAndContextMetadata() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("drduc_overlay.sqlite")
        val graph = File(context.cacheDir, "overlay-context-graph.sqlite").apply { delete() }
        SQLiteDatabase.openOrCreateDatabase(graph, null).use(::createContextFixture)
        val store = MobileGraphStore(graph)
        val overlay = OverlayGraphStore(context)
        try {
            overlay.upsertApprovedTerm(
                source = "\u4e59",
                target = "overlay",
                universe = "Beta",
                scope = "universe",
                posTag = "N",
                contextMarkers = listOf("\u76df\u53cb"),
                coOccurringEntities = listOf("\u76df\u53cb")
            )
            val result = DrDucGraphTranslationEngine(store, overlay, LegadoDictionaryCandidateStore(context)).translate(
                TranslationRequest(
                    text = "\u4e59",
                    config = TranslationConfig(activeUniverses = listOf("Beta"), contextWindow = "\u76df\u53cb"),
                    traceLevel = TraceLevel.FULL
                )
            )
            val selected = checkNotNull(result.trace).segments.single().candidate
            assertEquals("beta", selected.universe)
            assertEquals("N", selected.posTag)
            assertEquals(2.55, selected.contextScore, 0.0001)
            assertEquals(listOf("\u76df\u53cb"), selected.contextMarkers)
            assertEquals(listOf("\u76df\u53cb"), selected.coOccurringEntities)
        } finally {
            store.close()
            overlay.close()
        }
    }

    @Test
    fun possessiveGrammarReordersLikePythonPattern() {
        val source = "\u5f20\u4e09\u7684\u5251"
        val conversion = GrammarConverter().convert(
            source,
            listOf(
                PosToken("\u5f20\u4e09", 0, 2, ChinesePos.PROPER_NOUN, 0.9, entityType = "person"),
                PosToken("\u7684", 2, 3, ChinesePos.FUNCTION, 0.9, isFunctionWord = true),
                PosToken("\u5251", 3, 4, ChinesePos.NOUN, 0.8)
            ),
            TranslationConfig(enableGrammarConverter = true)
        )
        assertTrue(conversion.changed)
        assertEquals("grammar:de_possessive", conversion.appliedRule)
        assertEquals("\u5251c\u1ee7a\u5f20\u4e09", conversion.pieces.joinToString("") { it.text })
    }

    @Test
    fun compiledPythonGrammarSchemaPreservesMinAndMaxTokenCounts() {
        val properties = JSONObject(
            """
            {
              "source": "yaml_rule_fixture",
              "rule_group": "yaml_rule_fixture",
              "pattern_json": {
                "slots": [
                  {"name": "modifier", "pos": ["ADJECTIVE"], "min": 1, "max": 2, "allow_unknown": false},
                  {"name": "noun", "pos": ["NOUN"], "min": 1, "max": 3, "allow_unknown": false}
                ],
                "template": [
                  {"kind": "slot", "name": "noun"},
                  {"kind": "slot", "name": "modifier"}
                ]
              }
            }
            """.trimIndent()
        )
        val pattern = checkNotNull(GrammarConverter.patternFromJson(properties))
        assertEquals(2, pattern.slots.first().maxTokens)
        assertEquals(3, pattern.slots.last().maxTokens)
    }

    private fun createContextFixture(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE graph_versions (id INTEGER PRIMARY KEY, version INTEGER NOT NULL)")
        db.execSQL("INSERT INTO graph_versions(id, version) VALUES (1, 1)")
        db.execSQL(
            """
            CREATE TABLE graph_nodes (
              id TEXT PRIMARY KEY, labels_json TEXT NOT NULL, primary_label TEXT NOT NULL,
              key TEXT NOT NULL, properties_json TEXT NOT NULL, confidence REAL NOT NULL,
              status TEXT NOT NULL, scope TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE graph_term_index (
              normalized_source TEXT NOT NULL, target TEXT NOT NULL, surface TEXT NOT NULL,
              node_id TEXT NOT NULL, priority INTEGER NOT NULL, confidence REAL NOT NULL,
              graph_layer TEXT NOT NULL, universe TEXT NOT NULL, domain TEXT NOT NULL,
              lang TEXT NOT NULL, status TEXT NOT NULL, max_len INTEGER NOT NULL,
              scope TEXT NOT NULL, kind TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL(
            """
            CREATE TABLE graph_context_index (
              node_id TEXT NOT NULL, marker_type TEXT NOT NULL, marker TEXT NOT NULL,
              universe TEXT NOT NULL, work TEXT NOT NULL, domain TEXT NOT NULL
            )
            """.trimIndent()
        )
        db.execSQL("INSERT INTO graph_nodes VALUES ('alpha', '[]', 'Lexeme', 'alpha', '{}', 0.8, 'approved', 'universe')")
        db.execSQL("INSERT INTO graph_nodes VALUES ('beta', '[]', 'Lexeme', 'beta', '{}', 0.8, 'approved', 'universe')")
        db.execSQL("INSERT INTO graph_term_index VALUES ('\u7532', 'an', 'an', 'alpha', 1, 0.8, 'fixture', 'Alpha', '', 'zh', 'approved', 1, 'universe', 'lexeme')")
        db.execSQL("INSERT INTO graph_term_index VALUES ('\u7532', 'sai', 'sai', 'beta', 50, 0.8, 'fixture', 'Beta', '', 'zh', 'approved', 1, 'universe', 'lexeme')")
        db.execSQL("INSERT INTO graph_context_index VALUES ('alpha', 'context', '\u76df\u53cb', 'Alpha', '', '')")
        db.execSQL("INSERT INTO graph_context_index VALUES ('alpha', 'co_entity', '\u76df\u53cb', 'Alpha', '', '')")
    }
}
