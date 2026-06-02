package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.BottomAppBar
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.EReaderApplication
import com.example.crawler.VbookBookInfo
import com.example.crawler.VbookChapterRef
import com.example.data.UiTextFieldType
import com.example.data.VbookDownloadScheduler
import kotlinx.coroutines.launch
import org.jsoup.Jsoup

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(sourceId: String, bookUrl: String, onBack: () -> Unit, onOpenReader: (Int) -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as EReaderApplication
    val scope = rememberCoroutineScope()
    var info by remember { mutableStateOf<VbookBookInfo?>(null) }
    var chapters by remember { mutableStateOf<List<VbookChapterRef>>(emptyList()) }
    var savedBookId by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    var actionRunning by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var showToc by remember { mutableStateOf(false) }
    var showDownload by remember { mutableStateOf(false) }

    LaunchedEffect(sourceId, bookUrl) {
        loading = true
        runCatching { app.onlineCrawlerService.getBookInfo(sourceId, bookUrl) to app.onlineCrawlerService.getChapters(sourceId, bookUrl) }
            .onSuccess { (book, toc) -> info = book; chapters = toc }
            .onFailure { message = it.message ?: "Không thể tải chi tiết truyện." }
        loading = false
    }
    fun saveAnd(block: suspend (Int) -> Unit) {
        scope.launch {
            actionRunning = true
            runCatching { app.onlineLibraryService.saveBook(sourceId, bookUrl) }
                .onSuccess { savedBookId = it.id; block(it.id) }
                .onFailure { message = it.message ?: "Không thể lưu truyện." }
            actionRunning = false
        }
    }

    Scaffold(
        topBar = { TopAppBar({ Text("Chi tiết truyện") }, navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại") } }) },
        bottomBar = {
            if (info != null) BottomAppBar {
                TextButton(onClick = { showDownload = true }) { Icon(Icons.Default.CloudDownload, null); Text(" Tải xuống") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { showToc = true }) { Icon(Icons.AutoMirrored.Filled.MenuBook, null); Text(" Mục lục") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = { saveAnd { } }, enabled = !actionRunning) {
                    Icon(if (savedBookId > 0) Icons.Default.Check else Icons.Default.Add, null)
                    Text(if (savedBookId > 0) " Đã thêm" else " Thêm kệ")
                }
            }
        }
    ) { padding ->
        when {
            loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            info == null -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { Text(message.ifBlank { "Không tìm thấy truyện." }) }
            else -> LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                item {
                    Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(112.dp, 164.dp).background(MaterialTheme.colorScheme.surfaceVariant)) {
                            if (info!!.coverUrl.isNotBlank()) AsyncImage(info!!.coverUrl, info!!.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        }
                        Spacer(Modifier.width(16.dp))
                        Column {
                            Text(rememberTranslatedUiText(info!!.title, UiTextFieldType.BOOK_TITLE), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                            Text(rememberTranslatedUiText(info!!.author, UiTextFieldType.AUTHOR).ifBlank { "Chưa rõ tác giả" })
                            Text("${chapters.size} chương · ${if (info!!.ongoing) "Đang ra" else "Hoàn thành"}", style = MaterialTheme.typography.bodySmall)
                            Spacer(Modifier.height(12.dp))
                            Button(onClick = { saveAnd(onOpenReader) }, enabled = !actionRunning) { Text("Đọc truyện") }
                        }
                    }
                    HorizontalDivider()
                    Column(Modifier.padding(16.dp)) {
                        Text("Giới thiệu", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        Text(
                            rememberTranslatedUiText(Jsoup.parse(info!!.description).text(), UiTextFieldType.DESCRIPTION)
                                .ifBlank { "Chưa có mô tả." }
                        )
                        if (message.isNotBlank()) Text(message, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 12.dp))
                    }
                }
            }
        }
    }
    if (showToc) {
        ModalBottomSheet(onDismissRequest = { showToc = false }) {
            Text("Mục lục (${chapters.size} chương)", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp))
            LazyColumn {
                items(chapters, key = { it.url }) { chapter ->
                    ListItem(
                        headlineContent = { Text(rememberTranslatedUiText(chapter.title, UiTextFieldType.CHAPTER_TITLE), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        modifier = Modifier.clickable { saveAnd { onOpenReader(it) }; showToc = false },
                        trailingContent = {
                            IconButton(onClick = {
                                saveAnd { id -> VbookDownloadScheduler.enqueueRange(context, id, chapter.index, chapter.index) }
                            }) { Icon(Icons.Default.CloudDownload, "Tải chương") }
                        }
                    )
                }
            }
        }
    }
    if (showDownload) {
        DownloadRangeDialog(chapters.lastIndex, { showDownload = false }) { first, last ->
            saveAnd { id ->
                VbookDownloadScheduler.enqueueRange(context, id, first, last)
                message = "Đã thêm ${last - first + 1} chương vào hàng đợi tải."
            }
            showDownload = false
        }
    }
}

@Composable
private fun DownloadRangeDialog(lastIndex: Int, onDismiss: () -> Unit, onConfirm: (Int, Int) -> Unit) {
    var first by remember { mutableStateOf("1") }
    var last by remember(lastIndex) { mutableStateOf((lastIndex + 1).coerceAtLeast(1).toString()) }
    val firstIndex = (first.toIntOrNull() ?: 1).minus(1).coerceIn(0, lastIndex.coerceAtLeast(0))
    val lastChapter = (last.toIntOrNull() ?: lastIndex + 1).minus(1).coerceIn(firstIndex, lastIndex.coerceAtLeast(0))
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tải truyện") },
        text = {
            Column {
                Text("Chọn khoảng chương cần tải.")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(first, { first = it }, Modifier.weight(1f), label = { Text("Từ chương") })
                    OutlinedTextField(last, { last = it }, Modifier.weight(1f), label = { Text("Đến chương") })
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(firstIndex, lastChapter) }) { Text("Tải") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Đóng") } }
    )
}
