package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.drduc.engine.DrDucGraphTranslationEngine
import com.drduc.engine.EngineProfile
import com.drduc.engine.TraceLevel
import com.drduc.engine.TranslationConfig
import com.drduc.engine.TranslationMode
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
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class DrDucProductionSurfaceTest {
    @Test
    fun productionPrefersFunctionLayerOverDictionaryGloss() = runBlocking {
        withEngine { engine ->
            val result = engine.translate(TranslationRequest(text = "我是", mode = TranslationMode.OFFLINE))
            assertEquals("Tôi là", result.displayText)
        }
    }

    @Test
    fun productionStripsSurfaceNoteButParityKeepsIt() = runBlocking {
        withEngine { engine ->
            val production = engine.translate(
                TranslationRequest(text = "道", mode = TranslationMode.OFFLINE, traceLevel = TraceLevel.FULL)
            )
            val parity = engine.translate(
                TranslationRequest(
                    text = "道",
                    mode = TranslationMode.OFFLINE,
                    config = TranslationConfig(profile = EngineProfile.DRDUC_PARITY)
                )
            )
            assertEquals("Đạo", production.displayText)
            assertEquals("Đạo (nguyên lý tu hành)", parity.displayText)
            assertTrue(production.trace?.segments?.single()?.candidate?.surfaceNote == "nguyên lý tu hành")
        }
    }

    private suspend fun withEngine(block: suspend (DrDucGraphTranslationEngine) -> Unit) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("drduc_overlay.sqlite")
        val graph = File(context.cacheDir, "surface-graph.sqlite").apply { delete() }
        SQLiteDatabase.openOrCreateDatabase(graph, null).use { db ->
            db.execSQL("CREATE TABLE graph_versions (id INTEGER PRIMARY KEY, version INTEGER NOT NULL)")
            db.execSQL("INSERT INTO graph_versions(id, version) VALUES (1, 1)")
            db.execSQL(
                """
                CREATE TABLE graph_term_index (
                  normalized_source TEXT NOT NULL, target TEXT NOT NULL, surface TEXT NOT NULL,
                  node_id TEXT NOT NULL, priority INTEGER NOT NULL, confidence REAL NOT NULL,
                  graph_layer TEXT NOT NULL, universe TEXT NOT NULL, domain TEXT NOT NULL,
                  lang TEXT NOT NULL, status TEXT NOT NULL, max_len INTEGER NOT NULL
                )
                """.trimIndent()
            )
            insert(db, "我", "tôi", "function", 90, 0.78)
            insert(db, "我", "Ta (tiếng tự xưng mình)", "phonetic_fallback", 70, 1.0)
            insert(db, "是", "là", "function", 90, 0.78)
            insert(db, "是", "đúng; chính xác", "phonetic_fallback", 70, 1.0)
            insert(db, "道", "Đạo (nguyên lý tu hành)", "phonetic_fallback", 70, 1.0)
        }
        val graphStore = MobileGraphStore(graph)
        val overlayStore = OverlayGraphStore(context)
        try {
            block(DrDucGraphTranslationEngine(graphStore, overlayStore, LegadoDictionaryCandidateStore(context)))
        } finally {
            graphStore.close()
            overlayStore.close()
        }
    }

    private fun insert(db: SQLiteDatabase, source: String, target: String, layer: String, priority: Int, confidence: Double) {
        db.execSQL(
            "INSERT INTO graph_term_index VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            arrayOf<Any>(source, target, target, "$source-$layer-$target", priority, confidence, layer, "", "", "zh", "approved", source.length)
        )
    }
}
