package com.example

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.example.data.AppDatabase
import com.example.data.Bookmark
import com.example.data.DictionaryPackage
import com.example.data.DownloadBatch
import com.example.data.ReaderNote
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RoomV5StorageTest {
    @Test
    fun storesDownloadBatchBookmarkNoteAndDictionaryInventory() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val database = Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java).allowMainThreadQueries().build()
        try {
            val reader = database.readerDao()
            reader.putDownloadBatch(DownloadBatch("batch-1", 12, 1, 3, "QUEUED", totalChapters = 3))
            reader.putBookmark(Bookmark(bookId = 12, chapterIndex = 2, paragraphAnchor = 4, excerpt = "bookmark"))
            reader.putNote(ReaderNote(bookId = 12, chapterIndex = 2, paragraphAnchor = 4, excerpt = "excerpt", content = "note"))
            reader.putDictionaryPackage(DictionaryPackage("VIETPHRASE", "VietPhrase.txt", "v1", "sha", 10, "READY"))

            assertEquals("batch-1", reader.getDownloadBatches(12).single().id)
            assertEquals("bookmark", reader.getBookmarks(12).single().excerpt)
            assertEquals("note", reader.getNotes(12).single().content)
            assertEquals(10, reader.getDictionaryPackages().single().entryCount)
        } finally {
            database.close()
        }
    }
}
