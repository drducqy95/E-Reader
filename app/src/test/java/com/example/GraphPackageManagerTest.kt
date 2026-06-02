package com.example

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import com.example.data.GraphPackageManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class GraphPackageManagerTest {
    @Test
    fun importsValidatedGraphAndRejectsWrongChecksum() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = GraphPackageManager(context)
        manager.deleteGraph()
        val fixture = File(context.cacheDir, "graph-package-fixture.sqlite").apply { delete() }
        createFixture(fixture)
        val checksum = sha256(fixture)

        val installed = fixture.inputStream().use { manager.importGraph(it, checksum, "test") }

        assertTrue(installed.installed)
        assertEquals("9", installed.graphVersion)
        assertEquals(checksum, installed.sha256)
        assertEquals("test", manager.status().source)
        assertFalse(installed.contextUniverseAvailable)
        assertTrue(installed.warnings.any { it.contains("Context-universe") })
        assertFalse(File("${manager.graphFile.path}.tmp-shm").exists())
        assertFalse(File("${manager.graphFile.path}.tmp-wal").exists())
        val error = runCatching {
            fixture.inputStream().use { manager.importGraph(it, "0".repeat(64), "bad-test") }
        }.exceptionOrNull()
        assertTrue(error is IllegalArgumentException)
        assertTrue(manager.status().installed)
        manager.deleteGraph()
        assertFalse(manager.status().installed)
        assertFalse(File("${manager.graphFile.path}-shm").exists())
        assertFalse(File("${manager.graphFile.path}-wal").exists())
    }

    @Test
    fun reportsContextUniverseGrammarAndCooccurrenceCapabilities() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val manager = GraphPackageManager(context)
        manager.deleteGraph()
        val fixture = File(context.cacheDir, "graph-package-context-fixture.sqlite").apply { delete() }
        createContextFixture(fixture)

        val installed = fixture.inputStream().use { manager.importGraph(it, source = "context-test") }

        assertTrue(installed.contextUniverseAvailable)
        assertEquals(1, installed.contextRows)
        assertEquals(1, installed.universeTerms)
        assertEquals(1, installed.grammarNodes)
        assertEquals(1, installed.cooccurrenceRows)
        assertTrue(installed.warnings.isEmpty())
        manager.deleteGraph()
    }

    private fun createFixture(file: File) {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            REQUIRED_TABLES.forEach { table ->
                if (table == "graph_versions") {
                    db.execSQL("CREATE TABLE graph_versions (id INTEGER PRIMARY KEY, version INTEGER NOT NULL)")
                    db.execSQL("INSERT INTO graph_versions(id, version) VALUES (1, 9)")
                } else {
                    db.execSQL("CREATE TABLE $table (id INTEGER PRIMARY KEY)")
                }
            }
        }
    }

    private fun createContextFixture(file: File) {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            db.execSQL("CREATE TABLE graph_versions (id INTEGER PRIMARY KEY, version INTEGER NOT NULL)")
            db.execSQL("INSERT INTO graph_versions(id, version) VALUES (1, 10)")
            db.execSQL("CREATE TABLE graph_nodes (id INTEGER PRIMARY KEY, primary_label TEXT NOT NULL, labels_json TEXT NOT NULL)")
            db.execSQL("INSERT INTO graph_nodes VALUES (1, 'GrammarRule', '[\"GrammarRule\"]')")
            db.execSQL("CREATE TABLE graph_term_index (id INTEGER PRIMARY KEY, universe TEXT NOT NULL)")
            db.execSQL("INSERT INTO graph_term_index VALUES (1, 'fixture')")
            db.execSQL("CREATE TABLE graph_context_index (id INTEGER PRIMARY KEY)")
            db.execSQL("INSERT INTO graph_context_index VALUES (1)")
            db.execSQL("CREATE TABLE graph_cooccurrence (id INTEGER PRIMARY KEY)")
            db.execSQL("INSERT INTO graph_cooccurrence VALUES (1)")
            REQUIRED_TABLES.filterNot {
                it in setOf("graph_versions", "graph_nodes", "graph_term_index", "graph_context_index")
            }.forEach { table -> db.execSQL("CREATE TABLE $table (id INTEGER PRIMARY KEY)") }
        }
    }

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it) }

    companion object {
        private val REQUIRED_TABLES = listOf(
            "graph_nodes",
            "graph_node_labels",
            "graph_term_index",
            "graph_context_index",
            "graph_node_ids",
            "graph_edges_compact",
            "graph_manifest",
            "graph_versions"
        )
    }
}
