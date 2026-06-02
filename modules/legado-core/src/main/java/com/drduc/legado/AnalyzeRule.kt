package com.drduc.legado

import com.jayway.jsonpath.JsonPath
import org.apache.commons.text.StringEscapeUtils
import org.jsoup.Jsoup
import org.jsoup.nodes.Element

class AnalyzeRule(
    private val jsEvaluator: JsRuleEvaluator? = null,
    private val webViewEvaluator: WebViewRuleEvaluator? = null,
    private val debugSink: DebugSink? = null
) {
    fun strings(content: Any?, rule: String, baseUrl: String = ""): List<String> =
        elements(content, rule, baseUrl).flatMap {
            when (it) {
                is List<*> -> it.map(Any?::toString)
                is Element -> listOf(it.text())
                else -> listOf(it.toString())
            }
        }

    fun string(content: Any?, rule: String, baseUrl: String = ""): String =
        strings(content, rule, baseUrl).joinToString("\n").let(StringEscapeUtils::unescapeHtml4)

    fun elements(content: Any?, rule: String, baseUrl: String = ""): List<Any> {
        if (content == null || rule.isBlank()) return emptyList()
        val parts = rule.split("&&").map(String::trim).filter(String::isNotEmpty)
        var current: List<Any> = listOf(content)
        parts.forEach { part ->
            current = current.flatMap { applyRule(it, part, baseUrl) }
            debugSink?.emit(DebugStep("rule", part, current.joinToString(" | ").take(240)))
        }
        return current
    }

    private fun applyRule(input: Any, rawRule: String, baseUrl: String): List<Any> {
        val (rule, replacement) = splitReplacement(rawRule)
        val result = when {
            rule.startsWith("@js:", true) ->
                listOfNotNull(jsEvaluator?.evaluate(rule.substringAfter(':'), input))
            rule.startsWith("<js>", true) && rule.endsWith("</js>", true) ->
                listOfNotNull(jsEvaluator?.evaluate(rule.removePrefix("<js>").removeSuffix("</js>"), input))
            rule.startsWith("@webJs:", true) ->
                listOfNotNull(webViewEvaluator?.let { evaluator ->
                    kotlinx.coroutines.runBlocking { evaluator.evaluate(baseUrl, input.toString(), rule.substringAfter(':')) }
                })
            rule.startsWith("@json:", true) || rule.startsWith("$") ->
                jsonPath(input, rule.removePrefix("@json:"))
            rule.startsWith("@xpath:", true) ->
                xpath(input, rule.substringAfter(':'))
            rule.startsWith("@regex:", true) ->
                regex(input.toString(), rule.substringAfter(':'))
            else -> css(input, rule)
        }
        return if (replacement == null) result else {
            result.map { replacement.first.replace(it.toString(), replacement.second) }
        }
    }

    private fun css(input: Any, rule: String): List<Any> {
        val element = when (input) {
            is Element -> input
            else -> Jsoup.parse(input.toString())
        }
        val at = rule.lastIndexOf('@')
        val selector = if (at > 0) rule.substring(0, at) else rule
        val attribute = if (at > 0) rule.substring(at + 1) else ""
        val selected = if (selector.isBlank()) listOf(element) else element.select(selector)
        return selected.map {
            when (attribute.lowercase()) {
                "" -> it
                "text" -> it.text()
                "html" -> it.html()
                "outerhtml" -> it.outerHtml()
                else -> it.attr(attribute)
            }
        }
    }

    private fun jsonPath(input: Any, rule: String): List<Any> {
        val value = JsonPath.read<Any>(input.toString(), rule)
        return when (value) {
            is List<*> -> value.filterNotNull()
            null -> emptyList()
            else -> listOf(value)
        }
    }

    private fun xpath(input: Any, rule: String): List<Any> {
        val element = if (input is Element) input else Jsoup.parse(input.toString())
        return element.selectXpath(rule).map(Element::text)
    }

    private fun regex(input: String, rule: String): List<Any> {
        val regex = Regex(rule)
        return regex.findAll(input).map { match ->
            match.groupValues.getOrElse(1) { match.value }.ifBlank { match.value }
        }.toList()
    }

    private fun splitReplacement(rule: String): Pair<String, Pair<Regex, String>?> {
        val values = rule.split("##", limit = 3)
        return if (values.size < 3) rule to null else values[0] to (Regex(values[1]) to values[2])
    }
}
