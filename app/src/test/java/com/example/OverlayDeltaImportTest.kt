package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.drduc.engine.graph.OverlayDeltaManifest
import com.drduc.engine.graph.OverlayGraphStore
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class OverlayDeltaImportTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.deleteDatabase(DATABASE_NAME)
    }

    @After
    fun tearDown() {
        context.deleteDatabase(DATABASE_NAME)
    }

    @Test
    fun importsReviewedTermAndHumanTmAtomicallyAndIsIdempotent() {
        OverlayGraphStore(context).use { store ->
            val manifest = reviewedManifest()
            val imported = store.importReviewedDelta(manifest, GRAPH_VERSION)
            assertEquals(1, imported.overlayVersion)
            assertEquals(1, imported.termCount)
            assertEquals(1, imported.reviewedTmCount)
            assertEquals("delta-fixture-1", imported.appliedDeltas.single().deltaVersion)
            assertEquals("Ban dich da duyet", store.exactTranslationMemory("full source"))

            val term = store.candidates("\u7532", 0, 24).single()
            assertEquals("alpha", term.targetText)
            assertEquals("FixtureUniverse", term.universe)
            assertEquals("N", term.posTag)
            assertEquals(listOf("\u76df\u53cb"), term.contextMarkers)

            val repeated = store.importReviewedDelta(manifest, GRAPH_VERSION)
            assertEquals(1, repeated.overlayVersion)
            assertEquals(1, repeated.appliedDeltas.size)
        }
    }

    @Test
    fun rejectsChecksumBaseGraphAndUnreviewedEntriesWithoutPartialWrites() {
        OverlayGraphStore(context).use { store ->
            val wrongChecksum = reviewedManifest().put("sha256", "0".repeat(64))
            assertTrue(runCatching { store.importReviewedDelta(wrongChecksum, GRAPH_VERSION) }.isFailure)
            assertEquals(0, store.status().termCount)

            assertTrue(runCatching { store.importReviewedDelta(reviewedManifest(), "other-graph") }.isFailure)
            assertEquals(0, store.status().termCount)

            val unreviewed = baseManifest(
                JSONArray().put(
                    JSONObject()
                        .put("type", "term")
                        .put("reviewed", false)
                        .put("source", "\u4e59")
                        .put("target", "beta")
                )
            )
            assertTrue(runCatching { store.importReviewedDelta(unreviewed, GRAPH_VERSION) }.isFailure)
            assertEquals(0, store.status().overlayVersion)
            assertEquals(0, store.status().termCount)
        }
    }

    @Test
    fun rejectsChangedContentForPreviouslyAppliedDeltaVersion() {
        OverlayGraphStore(context).use { store ->
            store.importReviewedDelta(reviewedManifest(), GRAPH_VERSION)
            val changed = baseManifest(
                JSONArray().put(
                    JSONObject()
                        .put("type", "term")
                        .put("reviewed", true)
                        .put("source", "\u7532")
                        .put("target", "changed")
                )
            )
            assertTrue(runCatching { store.importReviewedDelta(changed, GRAPH_VERSION) }.isFailure)
            assertEquals("alpha", store.candidates("\u7532", 0, 24).single().targetText)
            assertEquals(1, store.status().overlayVersion)
        }
    }

    @Test
    fun matchesPythonCanonicalChecksumsForUtf8AndDecimalFixtures() {
        val utf8Fixture = JSONObject()
            .put("schemaVersion", OverlayDeltaManifest.SCHEMA_VERSION)
            .put("baseGraphVersion", "none")
            .put("deltaVersion", "fixture-001")
            .put(
                "entries",
                JSONArray()
                    .put(
                        JSONObject()
                            .put("type", "term")
                            .put("reviewed", true)
                            .put("source", "\u7532")
                            .put("target", "giap")
                            .put("universe", "fixture")
                            .put("scope", "universe")
                            .put("posTag", "N")
                            .put("contextMarkers", JSONArray().put("\u76df\u53cb"))
                    )
                    .put(
                        JSONObject()
                            .put("type", "tm")
                            .put("reviewed", true)
                            .put("source", "\u5b8c\u6574\u539f\u6587")
                            .put("target", "Ban dich da duyet")
                    )
            )
        assertEquals(
            "e0550e3a58053c8ca18bf1bb4f9f81124b0e2330826dc0aac03881d848eb8ce2",
            OverlayDeltaManifest.calculateSha256(utf8Fixture)
        )

        val numericFixture = JSONObject()
            .put("schemaVersion", OverlayDeltaManifest.SCHEMA_VERSION)
            .put("baseGraphVersion", "none")
            .put("deltaVersion", "numeric-fixture")
            .put(
                "entries",
                JSONArray().put(
                    JSONObject()
                        .put("type", "term")
                        .put("reviewed", true)
                        .put("source", "alpha")
                        .put("target", "beta")
                        .put("confidence", 1e-6)
                )
            )
        assertEquals(
            "e36c900f3f1e41aa38132bacf2a99539c67236e5ee8ae4666e20ffb5f2bfeefb",
            OverlayDeltaManifest.calculateSha256(numericFixture)
        )
    }

    private fun reviewedManifest(): JSONObject = baseManifest(
        JSONArray()
            .put(
                JSONObject()
                    .put("type", "term")
                    .put("reviewed", true)
                    .put("source", "\u7532")
                    .put("target", "alpha")
                    .put("universe", "FixtureUniverse")
                    .put("scope", "universe")
                    .put("posTag", "N")
                    .put("contextMarkers", JSONArray().put("\u76df\u53cb"))
            )
            .put(
                JSONObject()
                    .put("type", "tm")
                    .put("reviewed", true)
                    .put("source", "full source")
                    .put("target", "Ban dich da duyet")
            )
    )

    private fun baseManifest(entries: JSONArray): JSONObject = OverlayDeltaManifest.withCalculatedSha256(
        JSONObject()
            .put("schemaVersion", OverlayDeltaManifest.SCHEMA_VERSION)
            .put("baseGraphVersion", GRAPH_VERSION)
            .put("deltaVersion", "delta-fixture-1")
            .put("entries", entries)
    )

    companion object {
        private const val DATABASE_NAME = "drduc_overlay.sqlite"
        private const val GRAPH_VERSION = "graph-fixture-7"
    }
}
