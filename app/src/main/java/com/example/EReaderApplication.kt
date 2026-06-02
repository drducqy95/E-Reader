package com.example

import android.app.Application
import com.example.data.AppDatabase
import com.example.data.BookRepository
import com.example.data.SettingsRepository
import com.example.data.dataStore

class EReaderApplication : Application() {
    val database by lazy { AppDatabase.getDatabase(this) }
    val bookRepository by lazy { BookRepository(database.bookDao()) }
    val settingsRepository by lazy { SettingsRepository(dataStore) }
}
