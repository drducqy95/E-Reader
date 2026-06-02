package com.drduc.engine

import org.json.JSONArray
import org.json.JSONObject

enum class ChinesePos {
    UNKNOWN, NOUN, VERB, ADJECTIVE, ADVERB, PRONOUN, NUMBER, CLASSIFIER,
    FUNCTION, PARTICLE, PREPOSITION, CONJUNCTION, PROPER_NOUN, IDIOM, PUNCTUATION, LITERAL
}

data class PosToken(
    val text: String,
    val start: Int,
    val end: Int,
    val pos: ChinesePos = ChinesePos.UNKNOWN,
    val confidence: Double = 0.0,
    val entityType: String = "",
    val graphLayer: String = "",
    val isFunctionWord: Boolean = false,
    val grammarRole: String = ""
) {
    val isStructural: Boolean
        get() = isFunctionWord || pos in setOf(
            ChinesePos.FUNCTION, ChinesePos.PARTICLE, ChinesePos.PREPOSITION, ChinesePos.CONJUNCTION
        )
}

data class GrammarSlot(
    val name: String,
    val literal: String = "",
    val pos: Set<ChinesePos> = emptySet(),
    val entityTypes: Set<String> = emptySet(),
    val graphLayers: Set<String> = emptySet(),
    val minTokens: Int = 1,
    val maxTokens: Int = 1,
    val optional: Boolean = false,
    val allowUnknown: Boolean = true
)

data class GrammarTemplatePart(val kind: String, val name: String = "", val text: String = "")

data class GrammarPattern(
    val ruleId: String,
    val ruleGroup: String,
    val slots: List<GrammarSlot>,
    val template: List<GrammarTemplatePart>,
    val priority: Int = 80,
    val minConfidence: Double = 0.55
)

data class GrammarPiece(
    val kind: String,
    val text: String,
    val start: Int,
    val end: Int,
    val ruleId: String,
    val ruleGroup: String,
    val matchScore: Double
)

data class GrammarConversion(
    val pieces: List<GrammarPiece>,
    val appliedRule: String = "",
    val ruleGroup: String = "",
    val matchScore: Double = 0.0
) {
    val changed: Boolean get() = appliedRule.isNotBlank()
}

class GrammarConverter {
    fun convert(
        source: String,
        tokens: List<PosToken>,
        config: TranslationConfig,
        extraPatterns: List<GrammarPattern> = emptyList()
    ): GrammarConversion {
        val groups = config.grammarRuleGroups.toSet()
        val patterns = builtInPatterns + extraPatterns
        val match = patterns.asSequence()
            .filter { groups.isEmpty() || it.ruleGroup in groups || it.ruleId in groups }
            .mapNotNull { match(tokens, it) }
            .filter { it.score >= maxOf(config.grammarMinScore, it.pattern.minConfidence) }
            .maxByOrNull { it.score }
            ?: return GrammarConversion(listOf(GrammarPiece("source", source, 0, source.length, "", "", 0.0)))
        val pieces = buildList {
            if (match.start > 0) add(GrammarPiece("source", source.substring(0, match.start), 0, match.start, "", "", 0.0))
            match.pattern.template.forEach { part ->
                if (part.kind == "literal" && part.text.isNotBlank()) {
                    add(GrammarPiece("literal", part.text, match.start, match.end, match.pattern.ruleId, match.pattern.ruleGroup, match.score))
                } else {
                    match.bindings[part.name]?.takeIf { it.tokens.isNotEmpty() }?.let { binding ->
                        add(GrammarPiece("source", binding.text, binding.start, binding.end, match.pattern.ruleId, match.pattern.ruleGroup, match.score))
                    }
                }
            }
            if (match.end < source.length) add(GrammarPiece("source", source.substring(match.end), match.end, source.length, "", "", 0.0))
        }
        return GrammarConversion(pieces, match.pattern.ruleId, match.pattern.ruleGroup, match.score)
    }

    private fun match(tokens: List<PosToken>, pattern: GrammarPattern): Match? {
        var best: Match? = null
        tokens.indices.forEach { startIndex ->
            if (tokens[startIndex].pos == ChinesePos.PUNCTUATION) return@forEach
            val bindings = matchSlots(tokens, pattern.slots, 0, startIndex, emptyMap()) ?: return@forEach
            val populated = bindings.values.filter { it.tokens.isNotEmpty() }
            if (populated.isEmpty()) return@forEach
            val score = score(pattern, populated, tokens) - if (startIndex > 0) 0.03 else 0.0
            val match = Match(pattern, bindings, populated.minOf { it.start }, populated.maxOf { it.end }, score)
            if (best == null || match.score > checkNotNull(best).score) best = match
        }
        return best
    }

    private fun matchSlots(
        tokens: List<PosToken>,
        slots: List<GrammarSlot>,
        slotIndex: Int,
        tokenIndex: Int,
        bindings: Map<String, Binding>
    ): Map<String, Binding>? {
        if (slotIndex >= slots.size) return bindings
        val slot = slots[slotIndex]
        if (slot.literal.isNotBlank()) {
            if (tokenIndex < tokens.size && tokens[tokenIndex].text == slot.literal) {
                val token = tokens[tokenIndex]
                return matchSlots(tokens, slots, slotIndex + 1, tokenIndex + 1, bindings + (slot.name to Binding(slot.name, listOf(token))))
            }
            return if (slot.optional) matchSlots(tokens, slots, slotIndex + 1, tokenIndex, bindings) else null
        }
        for (size in minOf(slot.maxTokens, tokens.size - tokenIndex) downTo maxOf(0, slot.minTokens)) {
            val span = tokens.subList(tokenIndex, tokenIndex + size)
            if (size == 0 && slot.optional) {
                matchSlots(tokens, slots, slotIndex + 1, tokenIndex, bindings + (slot.name to Binding(slot.name, emptyList())))?.let { return it }
            } else if (spanOk(span, slot)) {
                matchSlots(tokens, slots, slotIndex + 1, tokenIndex + size, bindings + (slot.name to Binding(slot.name, span)))?.let { return it }
            }
        }
        return if (slot.optional) matchSlots(tokens, slots, slotIndex + 1, tokenIndex, bindings) else null
    }

    private fun spanOk(tokens: List<PosToken>, slot: GrammarSlot): Boolean = tokens.isNotEmpty() && tokens.all { token ->
        if (slot.pos.isEmpty() && slot.entityTypes.isEmpty() && slot.graphLayers.isEmpty()) true else {
            token.pos in slot.pos ||
                token.entityType.lowercase() in slot.entityTypes ||
                token.graphLayer.lowercase() in slot.graphLayers ||
                (slot.allowUnknown && token.pos == ChinesePos.UNKNOWN)
        }
    }

    private fun score(pattern: GrammarPattern, bindings: List<Binding>, sourceTokens: List<PosToken>): Double {
        val tokens = bindings.flatMap { it.tokens }
        val averageConfidence = tokens.sumOf(PosToken::confidence) / maxOf(1, tokens.size)
        val structuralBonus = tokens.count(PosToken::isStructural) * 0.04
        val roleBonus = tokens.count { it.grammarRole.isNotBlank() } * 0.03
        val unknownPenalty = tokens.count { it.pos == ChinesePos.UNKNOWN } * 0.035
        val partialPenalty = if (tokens.size != sourceTokens.size) 0.05 else 0.0
        return (0.45 + pattern.priority / 200.0 + averageConfidence * 0.35 + structuralBonus + roleBonus - unknownPenalty - partialPenalty)
            .coerceIn(0.0, 1.5)
    }

    private data class Binding(val name: String, val tokens: List<PosToken>) {
        val text: String get() = tokens.joinToString("") { it.text }
        val start: Int get() = tokens.firstOrNull()?.start ?: 0
        val end: Int get() = tokens.lastOrNull()?.end ?: start
    }

    private data class Match(
        val pattern: GrammarPattern,
        val bindings: Map<String, Binding>,
        val start: Int,
        val end: Int,
        val score: Double
    )

    companion object {
        private val nounLike = setOf(ChinesePos.NOUN, ChinesePos.PROPER_NOUN, ChinesePos.PRONOUN, ChinesePos.UNKNOWN)
        private val verbLike = setOf(ChinesePos.VERB, ChinesePos.UNKNOWN)
        private val adjLike = setOf(ChinesePos.ADJECTIVE, ChinesePos.VERB, ChinesePos.UNKNOWN)
        private val openPos = setOf(
            ChinesePos.NOUN, ChinesePos.PROPER_NOUN, ChinesePos.PRONOUN, ChinesePos.VERB,
            ChinesePos.ADJECTIVE, ChinesePos.ADVERB, ChinesePos.NUMBER, ChinesePos.CLASSIFIER, ChinesePos.UNKNOWN
        )
        private val tail = GrammarSlot("tail", pos = setOf(ChinesePos.PUNCTUATION), minTokens = 0, maxTokens = 1, optional = true, allowUnknown = false)
        private val builtInPatterns = listOf(
            pattern("grammar:de_possessive", "de_possessive", 94, slot("owner", setOf(ChinesePos.PROPER_NOUN, ChinesePos.PRONOUN), 1, 4), literal("trigger", "\u7684"), slot("noun", nounLike, 1, 8), tail, template = listOf(ref("noun"), text("của"), ref("owner"), ref("tail"))),
            pattern("grammar:de_modifier_head", "de_modifier", 78, slot("modifier", openPos, 1, 6), literal("trigger", "\u7684"), slot("noun", nounLike, 1, 8), tail, template = listOf(ref("noun"), ref("modifier"), ref("tail"))),
            pattern("grammar:ba_disposal", "ba", 84, slot("subject", nounLike, 1, 8), literal("trigger", "\u628a"), slot("object", openPos, 1, 4), slot("verb", verbLike, 1, 12), tail, template = listOf(ref("subject"), ref("verb"), ref("object"), ref("tail"))),
            pattern("grammar:bei_passive", "bei", 84, slot("patient", nounLike, 1, 8), literal("trigger", "\u88ab"), slot("agent", nounLike, 1, 3), slot("verb", verbLike, 1, 12), tail, template = listOf(ref("patient"), text("bị"), ref("agent"), ref("verb"), ref("tail"))),
            pattern("grammar:bi_comparison", "bi", 86, slot("left", nounLike, 1, 8), literal("trigger", "\u6bd4"), slot("right", nounLike, 1, 6), slot("predicate", adjLike, 1, 8), tail, template = listOf(ref("left"), ref("predicate"), text("hơn"), ref("right"), ref("tail"))),
            pattern("grammar:meiyou_comparison", "meiyou_comparison", 86, slot("left", nounLike, 1, 8), literal("trigger", "\u6ca1\u6709"), slot("right", nounLike, 1, 6), slot("predicate", adjLike, 1, 8), tail, template = listOf(ref("left"), text("không"), ref("predicate"), text("bằng"), ref("right"), ref("tail"))),
            pattern("grammar:equative_yiyang", "equative_yiyang", 84, literal("trigger", "\u50cf"), slot("right", nounLike, 1, 6), literal("marker", "\u4e00\u6837"), slot("predicate", adjLike, 1, 8), tail, template = listOf(ref("predicate"), text("như"), ref("right"), ref("tail"))),
            pattern("grammar:zhi_modifier", "zhi_modifier", 80, slot("modifier", openPos, 1, 6), literal("trigger", "\u4e4b"), slot("noun", nounLike, 1, 8), tail, template = listOf(ref("noun"), ref("modifier"), ref("tail"))),
            pattern("grammar:de_complement", "de_complement", 78, slot("verb", verbLike, 1, 4), literal("trigger", "\u5f97"), slot("complement", openPos, 1, 12), tail, template = listOf(ref("verb"), text("đến mức"), ref("complement"), ref("tail")))
        ) + listOf("\u5728" to "ở", "\u4ece" to "từ", "\u5411" to "về phía").map { (trigger, target) ->
            pattern("grammar:preposition_$trigger", "preposition", 76, literal("trigger", trigger), slot("object", openPos, 1, 12), tail, template = listOf(text(target), ref("object"), ref("tail")))
        } + listOf("\u4e0a" to "trên", "\u4e0b" to "dưới", "\u91cc" to "trong", "\u5185" to "trong", "\u524d" to "trước", "\u540e" to "sau").map { (trigger, target) ->
            pattern("grammar:localizer_$trigger", "localizer", 80, slot("noun", nounLike, 1, 8), literal("trigger", trigger), tail, template = listOf(text(target), ref("noun"), ref("tail")))
        }

        fun patternFromJson(properties: JSONObject): GrammarPattern? = runCatching {
            val payload = properties.optJSONObject("pattern_json") ?: JSONObject(properties.optString("pattern_json", "{}"))
            val slotsJson = payload.optJSONArray("slots") ?: properties.optJSONArray("slot_schema") ?: JSONArray()
            val templateJson = payload.optJSONArray("template") ?: properties.optJSONArray("target_template") ?: JSONArray()
            if (slotsJson.length() == 0 || templateJson.length() == 0) return null
            GrammarPattern(
                ruleId = properties.optString("rule_id", properties.optString("source", "user_pos_rule")),
                ruleGroup = properties.optString("rule_group", payload.optString("rule_group", "user_pos")),
                slots = (0 until slotsJson.length()).map { index ->
                    val row = slotsJson.getJSONObject(index)
                    GrammarSlot(
                        name = row.optString("name", row.optString("slot")),
                        literal = row.optString("literal"),
                        pos = row.optJSONArray("pos").strings().mapNotNull(::normalizePos).toSet(),
                        entityTypes = row.optJSONArray("entity_types").strings().map(String::lowercase).toSet(),
                        graphLayers = row.optJSONArray("graph_layers").strings().map(String::lowercase).toSet(),
                        minTokens = row.optInt("min_tokens", row.optInt("min", if (row.optBoolean("optional")) 0 else 1)),
                        maxTokens = row.optInt("max_tokens", row.optInt("max", 1)).coerceAtLeast(0),
                        optional = row.optBoolean("optional"),
                        allowUnknown = row.optBoolean("allow_unknown", true)
                    )
                },
                template = (0 until templateJson.length()).map { index ->
                    val row = templateJson.getJSONObject(index)
                    GrammarTemplatePart(row.optString("kind", "slot"), row.optString("name"), row.optString("text"))
                },
                priority = properties.optInt("priority", payload.optInt("priority", 80)),
                minConfidence = properties.optDouble("min_confidence", payload.optDouble("min_confidence", 0.55))
            )
        }.getOrNull()

        fun normalizePos(value: String?): ChinesePos? {
            val normalized = value.orEmpty().trim().uppercase()
            return posAliases[normalized] ?: runCatching { ChinesePos.valueOf(normalized) }.getOrNull()
        }

        private fun pattern(id: String, group: String, priority: Int, vararg slots: GrammarSlot, template: List<GrammarTemplatePart>) =
            GrammarPattern(id, group, slots.toList(), template, priority)
        private fun slot(name: String, pos: Set<ChinesePos>, min: Int, max: Int) = GrammarSlot(name, pos = pos, minTokens = min, maxTokens = max)
        private fun literal(name: String, value: String) = GrammarSlot(name, literal = value)
        private fun ref(name: String) = GrammarTemplatePart("slot", name = name)
        private fun text(value: String) = GrammarTemplatePart("literal", text = value)
        private fun JSONArray?.strings(): List<String> = if (this == null) emptyList() else (0 until length()).map { optString(it) }
        private val posAliases = mapOf(
            "N" to ChinesePos.NOUN, "NN" to ChinesePos.NOUN, "V" to ChinesePos.VERB, "VV" to ChinesePos.VERB,
            "A" to ChinesePos.ADJECTIVE, "JJ" to ChinesePos.ADJECTIVE, "ADV" to ChinesePos.ADVERB,
            "D" to ChinesePos.ADVERB, "PN" to ChinesePos.PRONOUN, "R" to ChinesePos.PRONOUN,
            "NR" to ChinesePos.PROPER_NOUN, "NS" to ChinesePos.PROPER_NOUN, "P" to ChinesePos.PREPOSITION,
            "BA" to ChinesePos.PREPOSITION, "SB" to ChinesePos.PREPOSITION, "LB" to ChinesePos.PREPOSITION,
            "DEC" to ChinesePos.PARTICLE, "DEG" to ChinesePos.PARTICLE, "PU" to ChinesePos.PUNCTUATION
        )
    }
}
