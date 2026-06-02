package com.drduc.engine

import java.security.MessageDigest

class TranslationOrchestrator(
    private val offlineEngine: OfflineTranslationEngine,
    private val onlineProvider: OnlineRefinementProvider? = null
) {
    suspend fun translate(request: TranslationRequest): TranslationResult {
        val identity = cacheIdentity(request)
        if (request.mode == TranslationMode.RAW) return offlineEngine.translate(request).copy(cacheKey = identity.cacheKey)
        val offline = offlineEngine.translate(request.copy(mode = TranslationMode.OFFLINE))
        if (request.mode == TranslationMode.OFFLINE) return offline.copy(cacheKey = identity.cacheKey)
        val provider = onlineProvider?.takeIf { it.enabled } ?: return offline.copy(
            cacheKey = identity.cacheKey,
            warning = "Online refinement is not configured; displaying offline translation."
        )
        return runCatching { provider.refine(offline).copy(cacheKey = identity.cacheKey) }.getOrElse {
            offline.copy(cacheKey = identity.cacheKey, warning = "Online refinement failed: ${it.message}")
        }
    }

    fun runtimeStatus(): TranslationRuntimeStatus = offlineEngine.runtimeStatus()

    fun cacheIdentity(request: TranslationRequest): TranslationCacheIdentity {
        val runtime = runtimeStatus()
        val providerMode = when (request.mode) {
            TranslationMode.RAW -> "raw"
            TranslationMode.OFFLINE -> "offline"
            TranslationMode.HYBRID_AUTO, TranslationMode.ONLINE_FORCE -> onlineProvider?.takeIf { it.enabled }?.id ?: "offline-fallback"
        }
        val configHash = sha256(request.config.stableKey())
        val offlineMode = if (request.mode == TranslationMode.RAW) TranslationMode.RAW else TranslationMode.OFFLINE
        val baseKey = offlineEngine.cacheKey(request.copy(mode = offlineMode))
        return TranslationCacheIdentity(
            cacheKey = sha256(listOf(baseKey, request.mode, request.translationProfile, providerMode, runtime.hookVersion, runtime.dictionaryVersion).joinToString("|")),
            graphVersion = runtime.graphVersion,
            overlayVersion = runtime.overlayVersion,
            configHash = configHash,
            hookVersion = runtime.hookVersion,
            dictionaryVersion = runtime.dictionaryVersion,
            providerMode = providerMode
        )
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
}
