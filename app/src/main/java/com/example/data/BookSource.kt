package com.example.data

data class BookSource(
    val name: String,
    val bookSourceUrl: String,
    val ruleSearch: RuleSearch,
    val ruleBookInfo: RuleBookInfo,
    val ruleToc: RuleToc,
    val ruleContent: RuleContent
)

data class RuleSearch(
    val bookList: String,
    val name: String,
    val author: String,
    val bookUrl: String
)

data class RuleBookInfo(
    val intro: String,
    val tocUrl: String
)

data class RuleToc(
    val chapterList: String,
    val chapterName: String,
    val chapterUrl: String
)

data class RuleContent(
    val content: String,
    val replaceRegex: String = ""
)
