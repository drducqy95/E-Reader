package com.example.crawler

import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

class WebCrawler(private val jsEngine: JsEngine = JsEngine()) {
    fun fetchHtml(url: String): Document? {
        return try {
            Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64 AppleWebKit/537.36) Chrome/120.0.0.0 Mobile Safari/537.36")
                .timeout(15000)
                .ignoreContentType(true)
                .get()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Mô phỏng cách phân tích chuỗi quy tắc của Legado (Rule parsing).
     * Rule có thể là "class.title@text" (Jsoup query) hoặc "<js>...</js>" (Rhino Javascript eval)
     */
    fun parseRule(element: Element, rule: String): String {
        if (rule.isBlank()) return ""

        return try {
            if (rule.startsWith("<js>") && rule.endsWith("</js>")) {
                // Rule chạy bằng JS Rhino (gửi html context vào jsEngine nếu được nâng cấp)
                val jsCode = rule.removePrefix("<js>").removeSuffix("</js>")
                jsEngine.evaluate(jsCode)
            } else {
                // Phân tích CSS đơn giản kiểu "div.content@text" hoặc "a@href"
                val parts = rule.split("@")
                val selector = parts[0]
                val attribute = if (parts.size > 1) parts[1] else "text"
                
                val targetElement = if (selector.isNotBlank()) element.selectFirst(selector) else element
                
                when (attribute) {
                    "text" -> targetElement?.text() ?: ""
                    "html" -> targetElement?.html() ?: ""
                    else -> targetElement?.attr(attribute) ?: ""
                }
            }
        } catch (e: Exception) {
            "Lỗi phân tích rule: ${e.message}"
        }
    }

    // Lấy danh sách các element con để tạo List
    fun getElements(html: Document, rule: String): List<Element> {
        return if (rule.isNotBlank() && !rule.startsWith("<js>")) {
            html.select(rule).toList()
        } else {
            emptyList()
        }
    }
}
