package com.example.data

import android.content.Context
import java.io.File
import java.security.MessageDigest

class ChapterContentStore(context: Context) {
    private val contentDir = File(context.filesDir, "books/content").apply { mkdirs() }

    fun put(text: String): StoredChapterContent {
        val checksum = sha256(text)
        val file = File(contentDir, "$checksum.txt")
        if (!file.isFile) {
            val temporary = File(contentDir, "$checksum.tmp")
            temporary.writeText(text, Charsets.UTF_8)
            if (!temporary.renameTo(file) && !file.isFile) error("Could not cache chapter content")
            temporary.delete()
        }
        return StoredChapterContent(checksum, file.path)
    }

    fun read(contentKey: String): String? =
        contentKey.takeIf(String::isNotBlank)?.let(::File)?.takeIf(File::isFile)?.readText(Charsets.UTF_8)

    fun hasValid(contentKey: String, checksum: String): Boolean {
        val file = contentKey.takeIf(String::isNotBlank)?.let(::File) ?: return false
        return file.isFile && checksum.isNotBlank() && sha256(file.readText(Charsets.UTF_8)) == checksum
    }

    private fun sha256(text: String): String = MessageDigest.getInstance("SHA-256")
        .digest(text.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

data class StoredChapterContent(val checksum: String, val path: String)
