package com.example.data

import com.example.crawler.OnlineCrawlerService
import java.io.IOException

class OnlineLibraryService(
    private val database: AppDatabase,
    private val crawler: OnlineCrawlerService,
    private val contentStore: ChapterContentStore? = null
) {
    private val books = database.bookDao()
    private val reader = database.readerDao()

    suspend fun saveBook(sourceId: String, url: String): Book {
        books.getOnlineBook(sourceId, url)?.let { existing ->
            ensureToc(existing)
            return existing
        }
        val info = crawler.getBookInfo(sourceId, url)
        val id = books.insertBook(
            Book(
                title = info.title,
                author = info.author,
                format = crawler.sourceFormat(sourceId),
                uriString = info.url,
                sourceId = sourceId,
                coverUrl = info.coverUrl,
                description = info.description,
                status = if (info.ongoing) "ONGOING" else "COMPLETED"
            )
        ).toInt()
        return checkNotNull(books.getBookSnapshot(id)).also { ensureToc(it) }
    }

    suspend fun ensureToc(book: Book): List<Chapter> {
        val cached = reader.getChapters(book.id)
        if (cached.isNotEmpty()) return cached
        require(book.sourceId.isNotBlank()) { "Online book does not have a source extension" }
        val chapters = crawler.getChapters(book.sourceId, book.uriString).map { ref ->
            Chapter(
                bookId = book.id,
                chapterIndex = ref.index,
                title = ref.title,
                sourceUrl = ref.url,
                rawContent = ""
            )
        }
        if (chapters.isEmpty()) throw IOException("Book does not contain chapters")
        reader.insertChapters(chapters)
        if (book.totalChapters != chapters.size || book.latestChapter != chapters.last().title) {
            books.updateBook(book.copy(totalChapters = chapters.size, latestChapter = chapters.last().title))
        }
        return reader.getChapters(book.id)
    }

    suspend fun ensureContent(book: Book, chapter: Chapter): Chapter {
        if (chapter.rawContent.isNotBlank()) return chapter
        contentStore?.read(chapter.contentKey)?.takeIf(String::isNotBlank)?.let { cached ->
            return chapter.copy(rawContent = cached)
        }
        require(book.sourceId.isNotBlank()) { "Online book does not have a source extension" }
        val content = crawler.getContent(book.sourceId, chapter.sourceUrl)
        val stored = contentStore?.put(content)
        val updated = chapter.copy(
            rawContent = content,
            contentKey = stored?.path.orEmpty(),
            checksum = stored?.checksum.orEmpty(),
            downloadStatus = "DOWNLOADED",
            downloadedAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        reader.putChapter(updated)
        return reader.getChapter(book.id, chapter.chapterIndex) ?: updated
    }

    suspend fun downloadBook(
        bookId: Int,
        firstChapter: Int? = null,
        lastChapter: Int? = null,
        onProgress: suspend (Int, Int) -> Unit = { _, _ -> }
    ) {
        val book = books.getBookSnapshot(bookId) ?: error("Book not found: $bookId")
        val allChapters = ensureToc(book)
        val first = firstChapter ?: allChapters.first().chapterIndex
        val last = lastChapter ?: allChapters.last().chapterIndex
        val chapters = allChapters.filter { it.chapterIndex in first..last }
        chapters.forEachIndexed { index, chapter ->
            val taskId = reader.putDownloadTask(
                DownloadTask(bookId = bookId, chapterIndex = chapter.chapterIndex, status = "RUNNING")
            )
            try {
                ensureContent(book, chapter)
                reader.putDownloadTask(
                    DownloadTask(id = taskId, bookId = bookId, chapterIndex = chapter.chapterIndex, status = "COMPLETED")
                )
                onProgress(index + 1, chapters.size)
            } catch (error: Exception) {
                reader.putDownloadTask(
                    DownloadTask(
                        id = taskId,
                        bookId = bookId,
                        chapterIndex = chapter.chapterIndex,
                        status = "FAILED",
                        attempts = 1,
                        lastError = error.message ?: error.javaClass.simpleName
                    )
                )
                throw error
            }
        }
    }
}

typealias VbookLibraryService = OnlineLibraryService
