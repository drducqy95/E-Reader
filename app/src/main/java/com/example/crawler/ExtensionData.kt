package com.example.crawler

data class ExtensionData(
    val id: String,
    val name: String,
    val author: String,
    val version: Int,
    val source: String,
    val description: String,
    val iconBase64: String?,
    val scripts: Map<String, String>
)
