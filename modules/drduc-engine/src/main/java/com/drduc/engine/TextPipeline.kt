package com.drduc.engine

import java.text.Normalizer

internal object SourceNormalizer {
    private val cjkEscapes = Regex("(?<=[\\u3400-\\u9fff])\\\\([_*\\-])(?=[\\u3400-\\u9fff])")
    private val compactSpaces = Regex("[ \\t\\u3000]+")

    fun normalize(text: String): String = Normalizer.normalize(text, Normalizer.Form.NFC)
        .replace("\uFEFF", "")
        .replace("\r\n", "\n")
        .replace("\r", "\n")
        .replace(cjkEscapes, "")
        .replace(compactSpaces, " ")
        .trim()
}

internal object SurfaceRealizer {
    private val beforePunctuation = Regex("\\s+([,.;:!?%\\]\\)}])")
    private val afterOpening = Regex("([\\[({])\\s+")
    private val duplicateSpaces = Regex("[ \\t]{2,}")
    private val lineSpaces = Regex("[ \\t]*\\n[ \\t]*")
    private val sentenceStart = Regex("(^|[\\n\\r]|[.!?]\\s+|[.!?][\"']\\s+)([a-zA-Z\\u00C0-\\u024F])")

    fun realize(text: String): String {
        var result = Normalizer.normalize(text, Normalizer.Form.NFC)
        result = result.replace(beforePunctuation, "$1")
        result = result.replace(afterOpening, "$1")
        result = result.replace(duplicateSpaces, " ")
        result = result.replace(lineSpaces, "\n")
        result = sentenceStart.replace(result) { match ->
            match.groupValues[1] + match.groupValues[2].uppercase()
        }
        return result.trim()
    }

    fun stripSurfaceNote(target: String): Pair<String, String> {
        val text = target.trim()
        if (text.isEmpty()) return "" to ""
        val match = COMPLETE_NOTE.matchEntire(text) ?: UNMATCHED_NOTE.matchEntire(text)
        if (match != null) {
            val surface = match.groups["surface"]?.value?.trim().orEmpty()
            val note = match.groups["note"]?.value?.trim().orEmpty()
            if (surface.isNotEmpty() && note.length >= 4) return surface to note
        }
        return text to ""
    }

    fun preferredProductionSurface(target: String, graphLayer: String): Triple<String, String, List<String>> {
        val (withoutNote, note) = stripSurfaceNote(target)
        val alternatives = withoutNote.split(';').map(String::trim).filter(String::isNotEmpty)
        val shouldChooseFirst = graphLayer in setOf("compact_dict", "phonetic_fallback") && alternatives.size > 1
        return Triple(if (shouldChooseFirst) alternatives.first() else withoutNote, note, alternatives)
    }

    private val COMPLETE_NOTE = Regex("^(?<surface>.+?)\\s*\\((?<note>[^)]{4,})\\)\\s*$")
    private val UNMATCHED_NOTE = Regex("^(?<surface>.+?)\\s*\\((?<note>[^)]{4,})$")
}

internal object LayoutBlocks {
    private val whitespace = Regex("(\\s+)")

    fun split(text: String): List<String> {
        if (text.isEmpty()) return emptyList()
        val blocks = mutableListOf<String>()
        var cursor = 0
        whitespace.findAll(text).forEach { match ->
            if (match.range.first > cursor) blocks += text.substring(cursor, match.range.first)
            blocks += match.value
            cursor = match.range.last + 1
        }
        if (cursor < text.length) blocks += text.substring(cursor)
        return blocks
    }
}

internal object TextProtector {
    private val htmlTag = Regex("<[^>]+>")

    fun protect(text: String): Pair<String, List<String>> {
        val values = mutableListOf<String>()
        val protected = htmlTag.replace(text) {
            val index = values.size
            values += it.value
            "[[DRDUC_TAG_$index]]"
        }
        return protected to values
    }

    fun restore(text: String, values: List<String>): String {
        var restored = text
        values.forEachIndexed { index, value ->
            restored = restored.replace("[[DRDUC_TAG_$index]]", value)
        }
        return restored
    }
}

internal object HeadingNumberRules {
    private val units = mapOf(
        "卷" to "Quyen",
        "回" to "Hoi",
        "章" to "Chuong",
        "幕" to "Man",
        "折" to "Chiet",
        "节" to "Tiet",
        "集" to "Tap"
    )
    private val heading = Regex("第\\s*([0-9一二三四五六七八九十百千零〇两]+)\\s*([卷回章节幕折集])")

    fun apply(text: String): String = heading.replace(text) { match ->
        "${units[match.groupValues[2]] ?: "Chuong"} ${readNumber(match.groupValues[1])}"
    }

    private fun readNumber(value: String): Int {
        value.toIntOrNull()?.let { return it }
        val digits = mapOf('零' to 0, '〇' to 0, '一' to 1, '二' to 2, '两' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7, '八' to 8, '九' to 9)
        var result = 0
        var current = 0
        value.forEach { char ->
            when (char) {
                in digits -> current = digits.getValue(char)
                '十' -> { result += (if (current == 0) 1 else current) * 10; current = 0 }
                '百' -> { result += current * 100; current = 0 }
                '千' -> { result += current * 1000; current = 0 }
            }
        }
        return result + current
    }
}
