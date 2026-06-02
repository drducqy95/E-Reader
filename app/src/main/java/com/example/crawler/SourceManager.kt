package com.example.crawler

import com.example.data.Book
import com.example.data.BookSource
import org.jsoup.nodes.Document

class SourceManager(
    private val webCrawler: WebCrawler = WebCrawler()
) {

    // Hàm crawl dữ liệu mô phỏng chức năng Search của Legado
    fun searchBook(keyword: String, source: BookSource): List<Book> {
        val resultList = mutableListOf<Book>()
        
        // Thay placeholder từ khoá vào URL gốc của Rule (VD: https://truyencv.com/search?q={{key}})
        val searchUrl = source.bookSourceUrl.replace("{{key}}", keyword) 
        
        val document: Document = webCrawler.fetchHtml(searchUrl) ?: return emptyList()

        // Lấy danh sách truyện từ CSS query của Rule Search (VD: "div.book-list > div.item")
        val bookElements = webCrawler.getElements(document, source.ruleSearch.bookList)

        for (element in bookElements) {
            val title = webCrawler.parseRule(element, source.ruleSearch.name)
            val author = webCrawler.parseRule(element, source.ruleSearch.author)
            val bookUrl = webCrawler.parseRule(element, source.ruleSearch.bookUrl)
            
            if (title.isNotBlank()) {
                resultList.add(
                    Book(
                        title = title,
                        author = author.ifBlank { "Tác giả ẩn" },
                        format = "WEB",
                        uriString = bookUrl
                    )
                )
            }
        }
        
        return resultList
    }

    // Hàm lấy thông tin nội dung Chương (Mô phỏng Content Rule)
    fun getChapterContent(chapterUrl: String, source: BookSource): String {
        val document = webCrawler.fetchHtml(chapterUrl) ?: return "Lỗi: Không tải được trang."
        
        var content = webCrawler.parseRule(document, source.ruleContent.content)
        
        // Áp dụng Replace Regex nếu có (Mô phỏng Regex filter cấu hình sẵn từ Legado)
        if (source.ruleContent.replaceRegex.isNotBlank()) {
            val replaceRules = source.ruleContent.replaceRegex.split("##")
            if (replaceRules.size == 2) {
                content = content.replace(Regex(replaceRules[0]), replaceRules[1])
            }
        }
        
        return content
    }
}
