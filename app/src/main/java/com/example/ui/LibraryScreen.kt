package com.example.ui

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.data.Book
import com.example.data.UiTextFieldType
import com.example.data.isOnlineBook
import com.example.viewmodel.LibraryViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(viewModel: LibraryViewModel, onBookClick: (Int) -> Unit, onSettingsClick: () -> Unit) {
    val books by viewModel.allBooks.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    var isGrid by remember { mutableStateOf(true) }
    var filter by remember { mutableStateOf("Tất cả") }
    val visibleBooks = remember(books, filter) {
        books.filter {
            when (filter) {
                "Đang đọc" -> it.progress > 0f
                "Đã tải" -> it.totalChapters > 0
                "Local" -> !it.isOnlineBook()
                "Online" -> it.isOnlineBook()
                else -> true
            }
        }
    }
    val context = LocalContext.current
    val documentPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        val name = context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let(cursor::getString) else null
        } ?: "Unknown.txt"
        viewModel.insertBook(
            Book(
                title = name.substringBeforeLast("."),
                author = "Unknown Author",
                format = name.substringAfterLast(".", "TXT").uppercase(),
                uriString = uri.toString()
            )
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Kệ sách") },
                actions = {
                    IconButton(onClick = { viewModel.setThemeMode(if (themeMode != 2) 2 else 1) }) {
                        Icon(if (themeMode == 2) Icons.Default.LightMode else Icons.Default.DarkMode, "Đổi theme")
                    }
                    IconButton(onClick = { isGrid = !isGrid }) {
                        Icon(if (isGrid) Icons.AutoMirrored.Filled.ViewList else Icons.Default.GridView, "Đổi bố cục")
                    }
                    IconButton(onClick = onSettingsClick) { Icon(Icons.Default.Settings, "Cài đặt") }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface)
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { documentPicker.launch(arrayOf("text/plain", "application/pdf", "application/epub+zip")) },
                modifier = Modifier.testTag("add_book_fab")
            ) { Icon(Icons.Default.Add, "Thêm sách") }
        }
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf("Tất cả", "Đang đọc", "Đã tải", "Local", "Online").forEach {
                    FilterChip(selected = filter == it, onClick = { filter = it }, label = { Text(it) })
                }
            }
            if (books.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Chưa có sách. Hãy thêm sách local hoặc mở nguồn online.")
                }
            } else if (isGrid) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(3),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(visibleBooks, key = { it.id }) { book ->
                        BookGridItem(book, { onBookClick(book.id) }, { viewModel.deleteBook(book) })
                    }
                }
            } else {
                LazyColumn(contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(visibleBooks, key = { it.id }) { book ->
                        BookListItem(book, { onBookClick(book.id) }, { viewModel.deleteBook(book) })
                    }
                }
            }
        }
    }
}

@Composable
private fun BookCover(book: Book, modifier: Modifier = Modifier) {
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.BottomStart) {
        if (book.coverUrl.isNotBlank()) AsyncImage(book.coverUrl, book.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        Text(book.format, Modifier.padding(6.dp).background(MaterialTheme.colorScheme.primary).padding(horizontal = 4.dp, vertical = 2.dp), color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun BookGridItem(book: Book, onClick: () -> Unit, onDelete: () -> Unit) {
    val translatedTitle = rememberTranslatedUiText(book.title, UiTextFieldType.BOOK_TITLE)
    Column(Modifier.clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        BookCover(book, Modifier.fillMaxWidth().aspectRatio(2f / 3f))
        Text(translatedTitle, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        LinearProgressIndicator({ book.progress }, Modifier.fillMaxWidth().height(3.dp))
    }
}

@Composable
private fun BookListItem(book: Book, onClick: () -> Unit, onDelete: () -> Unit) {
    val translatedTitle = rememberTranslatedUiText(book.title, UiTextFieldType.BOOK_TITLE)
    val translatedAuthor = rememberTranslatedUiText(book.author, UiTextFieldType.AUTHOR)
    Card(Modifier.fillMaxWidth().clickable(onClick = onClick), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            BookCover(book, Modifier.size(56.dp, 82.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(translatedTitle, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(translatedAuthor, style = MaterialTheme.typography.bodySmall)
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator({ book.progress }, Modifier.fillMaxWidth().height(3.dp))
            }
            IconButton(onClick = onDelete) { Icon(Icons.Default.Delete, "Xóa") }
        }
    }
}
