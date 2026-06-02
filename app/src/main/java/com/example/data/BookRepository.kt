package com.example.data

import kotlinx.coroutines.flow.Flow

class BookRepository(private val bookDao: BookDao) {
    val allBooks: Flow<List<Book>> = bookDao.getAllBooks()

    fun getBook(id: Int): Flow<Book?> = bookDao.getBookById(id)

    suspend fun insert(book: Book): Long = bookDao.insertBook(book)

    suspend fun update(book: Book) = bookDao.updateBook(book)

    suspend fun delete(book: Book) = bookDao.deleteBook(book)
}
