package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.io.Serializable

@Entity(tableName = "books")
data class Book(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val title: String,
    val author: String,
    val format: String, // "TXT", "EPUB", "PDF"
    val uriString: String,
    val progress: Float = 0f, // 0 to 1
    val addedDate: Long = System.currentTimeMillis(),
    val lastReadDate: Long = 0L,
    val totalSize: Long = 0L
) : Serializable
