package com.drduc.engine

import com.drduc.engine.graph.CandidateStore
import com.drduc.engine.graph.LegadoDictionaryCandidateStore
import com.drduc.engine.graph.MobileGraphStore
import com.drduc.engine.graph.OverlayGraphStore
import java.security.MessageDigest

class DrDucGraphTranslationEngine(
    private val graphStore: MobileGraphStore,
    private val overlayStore: OverlayGraphStore,
    private val legadoStore: LegadoDictionaryCandidateStore
) : OfflineTranslationEngine {
    private var cachedGraphVersion = graphStore.graphVersion
    private val sentenceCache = linkedMapOf<String, DecodedSentence>()
    private val grammarConverter = GrammarConverter()

    override suspend fun translate(request: TranslationRequest): TranslationResult {
        if (request.mode == TranslationMode.RAW || request.translationProfile == TranslationProfile.RAW) {
            return TranslationResult(rawText = request.text, cacheKey = cacheKey(request))
        }
        if (request.translationProfile == TranslationProfile.VIETPHRASE) {
            return dictionaryResult(request, legadoStore.translateVietPhrase(request.text), "vietphrase")
        }
        if (request.translationProfile == TranslationProfile.HAN_VIET) {
            return dictionaryResult(request, legadoStore.translateHanViet(request.text), "han_viet")
        }
        refreshCachesIfGraphChanged()
        val tm = overlayStore.exactTranslationMemory(request.text)
        if (tm != null) {
            return TranslationResult(
                rawText = request.text,
                offlineText = tm,
                graphVersion = graphStore.graphVersion,
                overlayVersion = overlayStore.overlayVersion,
                cacheKey = cacheKey(request),
                trace = if (request.traceLevel == TraceLevel.NONE) null else TranslationTrace(
                    request.config.profile, graphStore.graphVersion, overlayStore.overlayVersion, emptyList(), listOf("tm_exact")
                )
            )
        }

        val (protectedText, tags) = TextProtector.protect(request.text)
        val normalized = SourceNormalizer.normalize(protectedText)
        val stages = mutableListOf("normalize", "layout_split", "sentence_split")
        if (request.config.enablePosTagger) stages += "pos_tag"
        if (request.config.enableGrammarConverter) stages += "grammar_convert"
        if (request.config.activeUniverses.isNotEmpty() || request.config.detectedUniverses.isNotEmpty()) stages += "context_universe"
        val segments = mutableListOf<TranslationSegment>()
        val translated = LayoutBlocks.split(normalized).joinToString("") { block ->
            if (block.isBlank()) block else decodeBlock(block, request.config, segments)
        }
        stages += listOf("lattice_decode", "surface_realize")
        val realized = TextProtector.restore(SurfaceRealizer.realize(translated), tags)
        overlayStore.recordMachineTranslation(request.text, realized)
        return TranslationResult(
            rawText = request.text,
            offlineText = realized,
            segments = segments,
            trace = if (request.traceLevel == TraceLevel.NONE) null else TranslationTrace(
                request.config.profile, graphStore.graphVersion, overlayStore.overlayVersion, segments, stages
            ),
            graphVersion = graphStore.graphVersion,
            overlayVersion = overlayStore.overlayVersion,
            cacheKey = cacheKey(request)
        )
    }

    override suspend fun translateCode(text: String): String {
        if (text.isBlank() || text.trimStart().startsWith("@js:", true) || text.trimStart().startsWith("<js>", true)) return text
        return text.lineSequence().joinToString("\n") { line ->
            if ("::" in line) {
                val name = line.substringBefore("::")
                val url = line.substringAfter("::")
                decodeBlock(name, TranslationConfig(), mutableListOf()) + "::" + url
            } else {
                decodeBlock(line, TranslationConfig(), mutableListOf())
            }
        }
    }

    private fun decodeBlock(text: String, config: TranslationConfig, trace: MutableList<TranslationSegment>): String {
        val input = if (config.enableHeadingNumberRules) HeadingNumberRules.apply(text) else text
        val runtimeConfig = config.copy(
            detectedUniverses = config.detectedUniverses.ifEmpty { graphStore.detectUniverses(input, config.maxLookupChars) },
            contextWindow = config.contextWindow.ifBlank { input }
        )
        val cacheId = "${graphStore.graphVersion}|${overlayStore.overlayVersion}|${runtimeConfig.stableKey()}|$input"
        sentenceCache[cacheId]?.let {
            trace += it.segments
            return it.text
        }
        val converted = if (runtimeConfig.enableGrammarConverter) {
            grammarConverter.convert(input, graphStore.posTokens(input, runtimeConfig.maxLookupChars), runtimeConfig, graphStore.grammarPatterns())
        } else null
        val decoded = if (converted?.changed == true && !runtimeConfig.grammarTraceOnly) {
            decodeGrammarPieces(converted, runtimeConfig)
        } else {
            decodeByMode(input, runtimeConfig)
        }
        if (sentenceCache.size >= 256) sentenceCache.remove(sentenceCache.keys.first())
        sentenceCache[cacheId] = decoded
        trace += decoded.segments
        return decoded.text
    }

    private fun decodeGrammarPieces(conversion: GrammarConversion, config: TranslationConfig): DecodedSentence {
        val segments = mutableListOf<TranslationSegment>()
        val text = conversion.pieces.joinToString(" ") { piece ->
            if (piece.kind == "literal") {
                val candidate = TranslationCandidate(
                    sourceText = piece.text,
                    targetText = piece.text,
                    source = CandidateSource.BUILTIN,
                    score = 4.2,
                    reason = "grammar_convert",
                    grammarRole = piece.ruleId,
                    appliedRule = piece.ruleId,
                    ruleGroup = piece.ruleGroup,
                    matchScore = piece.matchScore
                )
                segments += TranslationSegment(piece.text, piece.text, candidate)
                piece.text
            } else {
                decodeByMode(piece.text, config.copy(enableGrammarConverter = false)).also { segments += it.segments }.text
            }
        }
        return DecodedSentence(text, segments)
    }

    private fun decodeByMode(text: String, config: TranslationConfig): DecodedSentence =
        if (config.decoderMode == "greedy") decodeGreedy(text, config) else decodeLattice(text, config)

    private fun decodeGreedy(text: String, config: TranslationConfig): DecodedSentence {
        var index = 0
        val segments = mutableListOf<TranslationSegment>()
        while (index < text.length) {
            val candidate = candidatesAt(text, index, config).first()
            segments += TranslationSegment(candidate.sourceText, candidate.targetText, candidate)
            index += candidate.sourceText.length
        }
        return DecodedSentence(segments.joinToString(" ") { it.targetText }, segments)
    }

    private fun decodeLattice(text: String, config: TranslationConfig): DecodedSentence {
        var beam = listOf(BeamState(0, 0.0, emptyList()))
        while (beam.any { it.index < text.length }) {
            val next = mutableListOf<BeamState>()
            beam.forEach { state ->
                if (state.index >= text.length) {
                    next += state
                } else {
                    candidatesAt(text, state.index, config).forEach { candidate ->
                        val oneCharPenalty = transitionPenalty(state.segments.lastOrNull()?.candidate, candidate)
                        next += BeamState(
                            index = state.index + candidate.sourceText.length,
                            score = state.score + candidate.score + oneCharPenalty,
                            segments = state.segments + TranslationSegment(candidate.sourceText, candidate.targetText, candidate)
                        )
                    }
                }
            }
            beam = next.sortedWith(compareByDescending<BeamState> { it.score }.thenByDescending { it.index }).take(config.beamSize)
        }
        val best = beam.maxByOrNull { it.score } ?: BeamState(0, 0.0, emptyList())
        return DecodedSentence(best.segments.joinToString(" ") { it.targetText }, best.segments)
    }

    private fun candidatesAt(text: String, start: Int, config: TranslationConfig): List<TranslationCandidate> {
        literalRun(text, start)?.let { return listOf(it) }
        val stores: List<CandidateStore> = if (config.profile == EngineProfile.DRDUC_PARITY) {
            listOf(overlayStore, graphStore)
        } else {
            listOf(overlayStore, graphStore, legadoStore)
        }
        val candidates = stores.flatMap { it.candidates(text, start, config.maxLookupChars) }
            .map { if (config.profile == EngineProfile.HYBRID_PRODUCTION) productionCandidate(it) else it }
            .map { candidate -> graphStore.applyContext(applyPosScoring(candidate, config), config) }
            .toMutableList()
        builtins[text[start].toString()]?.let {
            candidates += TranslationCandidate(text[start].toString(), it, CandidateSource.BUILTIN, 1.0, reason = "builtin function word")
        }
        if (candidates.isEmpty()) {
            candidates += TranslationCandidate(text[start].toString(), text[start].toString(), CandidateSource.LITERAL, -1.0, fallbackLevel = 4, reason = "unresolved literal")
        }
        return candidates.sortedWith(
            compareByDescending<TranslationCandidate> { it.score }
                .thenByDescending { it.sourceText.length }
                .thenByDescending { it.priority }
                .thenByDescending { it.confidence }
        ).take(config.candidateLimit)
    }

    private fun applyPosScoring(candidate: TranslationCandidate, config: TranslationConfig): TranslationCandidate {
        if (!config.enablePosLatticeScoring || candidate.source != CandidateSource.DRDUC_GRAPH) return candidate
        var adjustment = 0.0
        if (candidate.posTag.isNotBlank() || candidate.posSub.isNotBlank()) adjustment += 0.15
        if (candidate.reason == "function" && candidate.sourceText.length <= 2) adjustment += 0.2
        if (candidate.grammarRole.isNotBlank()) adjustment += 0.1
        return candidate.copy(score = candidate.score + adjustment)
    }

    private fun transitionPenalty(previous: TranslationCandidate?, candidate: TranslationCandidate): Double {
        if (previous == null) return 0.0
        val graphSources = setOf(CandidateSource.DRDUC_GRAPH, CandidateSource.PROJECT_OVERLAY)
        return if (previous.sourceText.length == 1 && candidate.sourceText.length == 1 &&
            previous.source in graphSources && candidate.source in graphSources
        ) -0.75 else 0.0
    }

    private fun literalRun(text: String, start: Int): TranslationCandidate? {
        if (text[start].isCjk()) return null
        var end = start + 1
        while (end < text.length && !text[end].isCjk()) end++
        val value = text.substring(start, end)
        return TranslationCandidate(value, value, CandidateSource.LITERAL, 0.0, reason = "non-CJK literal")
    }

    private fun refreshCachesIfGraphChanged() {
        val current = graphStore.graphVersion
        if (cachedGraphVersion != current) {
            cachedGraphVersion = current
            sentenceCache.clear()
        }
    }

    override fun cacheKey(request: TranslationRequest): String {
        val value = listOf(
            graphStore.graphVersion,
            overlayStore.overlayVersion,
            legadoStore.dictionaryVersion(),
            request.mode,
            request.translationProfile,
            request.config.stableKey(),
            request.text
        ).joinToString("|")
        return MessageDigest.getInstance("SHA-256").digest(value.toByteArray()).joinToString("") { "%02x".format(it) }
    }

    override fun runtimeStatus(): TranslationRuntimeStatus = TranslationRuntimeStatus(
        graphVersion = graphStore.graphVersion,
        overlayVersion = overlayStore.overlayVersion,
        dictionaryVersion = legadoStore.dictionaryVersion()
    )

    private fun dictionaryResult(request: TranslationRequest, translated: String, stage: String) = TranslationResult(
        rawText = request.text,
        offlineText = translated,
        trace = if (request.traceLevel == TraceLevel.NONE) null else TranslationTrace(
            request.config.profile,
            graphStore.graphVersion,
            overlayStore.overlayVersion,
            emptyList(),
            listOf(stage)
        ),
        graphVersion = graphStore.graphVersion,
        overlayVersion = overlayStore.overlayVersion,
        cacheKey = cacheKey(request)
    )

    private fun productionCandidate(candidate: TranslationCandidate): TranslationCandidate {
        if (candidate.source != CandidateSource.DRDUC_GRAPH) return candidate
        val layerBoost = when (candidate.reason) {
            "function" -> 1.25
            "compact_dict" -> 0.2
            "phonetic_fallback" -> -1.0
            else -> 0.0
        }
        val (surface, note, alternatives) = SurfaceRealizer.preferredProductionSurface(candidate.targetText, candidate.reason)
        return candidate.copy(
            targetText = surface.ifBlank { candidate.targetText },
            score = candidate.score + layerBoost,
            surfaceNote = note,
            alternatives = alternatives
        )
    }

    private fun Char.isCjk(): Boolean = code in 0x3400..0x9fff

    private data class BeamState(val index: Int, val score: Double, val segments: List<TranslationSegment>)
    private data class DecodedSentence(val text: String, val segments: List<TranslationSegment>)

    companion object {
        private val builtins = mapOf(
            "的" to "", "了" to "", "著" to "", "是" to "la", "不" to "khong",
            "我" to "ta", "你" to "nguoi", "他" to "han", "她" to "nang", "与" to "va"
        )
    }
}
