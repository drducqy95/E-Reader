package com.example

import com.drduc.legado.AnalyzeRule
import com.drduc.legado.WebViewRuleEvaluator
import org.jsoup.Jsoup
import org.junit.Assert.assertEquals
import org.junit.Test

class LegadoAnalyzeRuleTest {
    private val analyzer = AnalyzeRule()

    @Test
    fun parsesCssJsonXpathAndRegexRules() {
        val html = Jsoup.parse("<div class='books'><a href='/one'>Ten sach</a></div>")
        assertEquals("Ten sach", analyzer.string(html, ".books a@text"))
        assertEquals(listOf("/one"), analyzer.strings(html, ".books a@href"))
        assertEquals(listOf("Mot", "Hai"), analyzer.strings("""{"items":[{"name":"Mot"},{"name":"Hai"}]}""", "$.items[*].name"))
        assertEquals(listOf("Ten sach"), analyzer.strings(html, "@xpath://a"))
        assertEquals(listOf("123"), analyzer.strings("chuong-123", "@regex:(\\d+)"))
    }

    @Test
    fun delegatesWebJsRulesToWebViewEvaluator() {
        val analyzer = AnalyzeRule(
            webViewEvaluator = WebViewRuleEvaluator { url, html, script -> "$url|$html|$script" }
        )
        assertEquals(
            "https://fixture.test|<div>input</div>|return document.title",
            analyzer.string("<div>input</div>", "@webJs:return document.title", "https://fixture.test")
        )
    }
}
