package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.drduc.engine.DrDucGraphTranslationEngine
import com.drduc.engine.TraceLevel
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
class DrDucGraphTranslationEngineTest {
    @Test
    fun graphCandidateWinsAndProducesTrace() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        context.deleteDatabase("drduc_overlay.sqlite")
        val graph = File(context.cacheDir, "graph.sqlite").apply { delete() }
        SQLiteDatabase.openOrCreateDatabase(graph, null).use { db ->
            db.execSQL("CREATE TABLE graph_versions (id INTEGER PRIMARY KEY, version INTEGER NOT NULL)")
            db.execSQL("INSERT INTO graph_versions(id, version) VALUES (1, 7)")
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
            db.execSQL(
                "INSERT INTO graph_term_index VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                arrayOf<Any>("我是", "ta la", "ta la", "node-1", 10, 0.95, "fixture", "", "", "zh", "approved", 2)
            )
        }
        val graphStore = MobileGraphStore(graph)
        val overlayStore = OverlayGraphStore(context)
        val engine = DrDucGraphTranslationEngine(
            graphStore,
            overlayStore,
            LegadoDictionaryCandidateStore(context)
        )

        val result = engine.translate(TranslationRequest(text = "我是", mode = TranslationMode.OFFLINE, traceLevel = TraceLevel.FULL))

        assertEquals("Ta la", result.displayText)
        assertEquals("7", result.graphVersion)
        assertTrue(result.trace?.segments?.any { it.candidate.nodeId == "node-1" } == true)
        graphStore.close()
        overlayStore.close()
    }
}
