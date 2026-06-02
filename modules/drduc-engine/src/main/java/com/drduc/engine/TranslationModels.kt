package com.drduc.engine

enum class EngineProfile { DRDUC_PARITY, HYBRID_PRODUCTION }

enum class TranslationMode { RAW, OFFLINE, HYBRID_AUTO, ONLINE_FORCE }

enum class TranslationProfile { RAW, VIETPHRASE, HAN_VIET, DRDUC }

enum class TraceLevel { NONE, SUMMARY, FULL }

enum class CandidateSource {
    SAMPLE_OVERRIDE,
    HUMAN_TM,
    PROJECT_OVERLAY,
    DRDUC_GRAPH,
    LEGADO_NAMES,
    LEGADO_VIETPHRASE,
    HAN_VIET,
    BUILTIN,
    LITERAL
}

data class TranslationConfig(
    val profile: EngineProfile = EngineProfile.HYBRID_PRODUCTION,
    val beamSize: Int = 6,
    val candidateLimit: Int = 24,
    val maxLookupChars: Int = 24,
    val decoderMode: String = "lattice",
    val enableHeadingNumberRules: Boolean = true,
    val enablePosTagger: Boolean = false,
    val enableGrammarConverter: Boolean = false,
    val enablePosLatticeScoring: Boolean = false,
    val enablePosEntityFilter: Boolean = false,
    val grammarRuleGroups: List<String> = emptyList(),
    val grammarMinScore: Double = 0.55,
    val grammarTraceOnly: Boolean = false,
    val activeUniverses: List<String> = emptyList(),
    val detectedUniverses: List<String> = emptyList(),
    val blockedUniverses: List<String> = emptyList(),
    val contextWindow: String = "",
    val contextMarkers: List<String> = emptyList()
) {
    fun stableKey(): String = listOf(
        profile, beamSize, candidateLimit, maxLookupChars, decoderMode,
        enableHeadingNumberRules, enablePosTagger, enableGrammarConverter,
        enablePosLatticeScoring, enablePosEntityFilter,
        grammarRuleGroups.joinToString(","), grammarMinScore, grammarTraceOnly,
        activeUniverses.joinToString(","), detectedUniverses.joinToString(","),
        blockedUniverses.joinToString(","), contextWindow, contextMarkers.joinToString(",")
    ).joinToString("|")
}

data class TranslationRequest(
    val text: String,
    val projectId: String? = null,
    val mode: TranslationMode = TranslationMode.OFFLINE,
    val translationProfile: TranslationProfile = TranslationProfile.DRDUC,
    val config: TranslationConfig = TranslationConfig(),
    val traceLevel: TraceLevel = TraceLevel.NONE
)

data class TranslationCandidate(
    val sourceText: String,
    val targetText: String,
    val source: CandidateSource,
    val score: Double,
    val nodeId: String = "",
    val priority: Int = 0,
    val confidence: Double = 0.0,
    val fallbackLevel: Int = 0,
    val universe: String = "",
    val domain: String = "",
    val scope: String = "",
    val entityType: String = "",
    val posTag: String = "",
    val posSub: String = "",
    val grammarRole: String = "",
    val contextMarkers: List<String> = emptyList(),
    val negativeContextMarkers: List<String> = emptyList(),
    val coOccurringEntities: List<String> = emptyList(),
    val contextScore: Double = 0.0,
    val penalty: Double = 0.0,
    val scoreBreakdown: Map<String, Double> = emptyMap(),
    val appliedRule: String = "",
    val ruleGroup: String = "",
    val matchScore: Double = 0.0,
    val reason: String = "",
    val surfaceNote: String = "",
    val alternatives: List<String> = emptyList()
)

data class TranslationSegment(
    val sourceText: String,
    val targetText: String,
    val candidate: TranslationCandidate
)

data class TranslationTrace(
    val profile: EngineProfile,
    val graphVersion: String,
    val overlayVersion: Long,
    val segments: List<TranslationSegment>,
    val stages: List<String>
)

data class TranslationResult(
    val rawText: String,
    val offlineText: String? = null,
    val refinedText: String? = null,
    val segments: List<TranslationSegment> = emptyList(),
    val trace: TranslationTrace? = null,
    val graphVersion: String = "none",
    val overlayVersion: Long = 0,
    val cacheKey: String = "",
    val warning: String? = null
) {
    val displayText: String
        get() = refinedText ?: offlineText ?: rawText
}

data class TranslationRuntimeStatus(
    val graphVersion: String,
    val overlayVersion: Long,
    val hookVersion: String = "none",
    val dictionaryVersion: String = "none"
)

data class TranslationCacheIdentity(
    val cacheKey: String,
    val graphVersion: String,
    val overlayVersion: Long,
    val configHash: String,
    val hookVersion: String,
    val dictionaryVersion: String,
    val providerMode: String
)

interface OfflineTranslationEngine {
    suspend fun translate(request: TranslationRequest): TranslationResult
    suspend fun translateCode(text: String): String
    fun cacheKey(request: TranslationRequest): String
    fun runtimeStatus(): TranslationRuntimeStatus
}

interface OnlineRefinementProvider {
    val id: String
    val enabled: Boolean get() = true
    suspend fun refine(offline: TranslationResult): TranslationResult
}
