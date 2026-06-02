package com.example

import com.drduc.engine.OfflineTranslationEngine
import com.drduc.engine.TranslationMode
import com.drduc.engine.TranslationOrchestrator
import com.drduc.engine.TranslationRequest
import com.drduc.engine.TranslationResult
import com.drduc.engine.TranslationRuntimeStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class TranslationOrchestratorTest {
    @Test
    fun cacheIdentitySeparatesOfflineAndHybridFallback() = runBlocking {
        val orchestrator = TranslationOrchestrator(FakeEngine())
        val offline = orchestrator.cacheIdentity(TranslationRequest("我是", mode = TranslationMode.OFFLINE))
        val hybrid = orchestrator.cacheIdentity(TranslationRequest("我是", mode = TranslationMode.HYBRID_AUTO))

        assertNotEquals(offline.cacheKey, hybrid.cacheKey)
        assertEquals("offline", offline.providerMode)
        assertEquals("offline-fallback", hybrid.providerMode)
        assertEquals("3", hybrid.graphVersion)
        assertEquals(4, hybrid.overlayVersion)
    }

    private class FakeEngine : OfflineTranslationEngine {
        override suspend fun translate(request: TranslationRequest) =
            TranslationResult(rawText = request.text, offlineText = "Tôi là")

        override suspend fun translateCode(text: String) = text

        override fun cacheKey(request: TranslationRequest) = "base:${request.mode}:${request.text}"

        override fun runtimeStatus() = TranslationRuntimeStatus(graphVersion = "3", overlayVersion = 4)
    }
}
