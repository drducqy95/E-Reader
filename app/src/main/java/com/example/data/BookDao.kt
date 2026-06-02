package com.example.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface BookDao {
    @Query("SELECT * FROM books ORDER BY lastReadDate DESC")
    fun getAllBooks(): Flow<List<Book>>

    @Query("SELECT * FROM books ORDER BY lastReadDate DESC")
    suspend fun getAllBooksSnapshot(): List<Book>

    @Query("SELECT * FROM books WHERE id = :id")
    fun getBookById(id: Int): Flow<Book?>

    @Query("SELECT * FROM books WHERE id = :id")
    suspend fun getBookSnapshot(id: Int): Book?

    @Query("SELECT * FROM books WHERE uriString = :uri LIMIT 1")
    suspend fun getBookByUriSnapshot(uri: String): Book?

    @Query("SELECT * FROM books WHERE sourceId = :sourceId AND uriString = :uri LIMIT 1")
    suspend fun getOnlineBook(sourceId: String, uri: String): Book?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertBook(book: Book): Long

    @Update
    suspend fun updateBook(book: Book)

    @Delete
    suspend fun deleteBook(book: Book)
}
