package com.example.ui

import android.speech.tts.TextToSpeech
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.data.Bookmark
import com.example.data.Chapter
import com.example.data.ReaderNote
import com.example.data.UiTextFieldType
import com.example.data.VbookDownloadScheduler
import com.example.crawler.openSourceBrowser
import com.example.viewmodel.ReaderTranslationMode
import com.example.viewmodel.ReaderViewModel
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(viewModel: ReaderViewModel, onBack: () -> Unit) {
    val book by viewModel.book.collectAsStateWithLifecycle()
    val text by viewModel.textContent.collectAsStateWithLifecycle()
    val chapters by viewModel.chapters.collectAsStateWithLifecycle()
    val chapterIndex by viewModel.chapterIndex.collectAsStateWithLifecycle()
    val anchor by viewModel.paragraphAnchor.collectAsStateWithLifecycle()
    val mode by viewModel.readerTranslationMode.collectAsStateWithLifecycle()
    val layout by viewModel.readerLayout.collectAsStateWithLifecycle()
    val fontSize by viewModel.fontSize.collectAsStateWithLifecycle()
    val lineSpacing by viewModel.lineSpacing.collectAsStateWithLifecycle()
    val bgColorIndex by viewModel.bgColorIndex.collectAsStateWithLifecycle()
    val ttsEnabled by viewModel.ttsEnabled.collectAsStateWithLifecycle()
    val refinement by viewModel.msOnlineRefinement.collectAsStateWithLifecycle()
    val bookmarks by viewModel.bookmarks.collectAsStateWithLifecycle()
    val notes by viewModel.notes.collectAsStateWithLifecycle()
    val loading by viewModel.isLoading.collectAsStateWithLifecycle()
    val warning by viewModel.warning.collectAsStateWithLifecycle()
    val translationStatus by viewModel.translationStatus.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    var showDrawer by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showNoteDialog by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    var chapterSeek by remember { mutableFloatStateOf(0f) }
    val activeBookmark = bookmarks.any { it.chapterIndex == chapterIndex && it.paragraphAnchor == anchor }
    val readerMessage = warning ?: translationStatus
    val browserUrl = chapters.getOrNull(chapterIndex)?.sourceUrl.orEmpty()
    val readerTitle = rememberTranslatedUiText(
        chapters.getOrNull(chapterIndex)?.title ?: book?.title.orEmpty(),
        if (chapters.getOrNull(chapterIndex) != null) UiTextFieldType.CHAPTER_TITLE else UiTextFieldType.BOOK_TITLE
    )

    LaunchedEffect(chapterIndex, chapters.size) {
        chapterSeek = chapterIndex.toFloat()
    }

    val context = LocalContext.current
    var tts: TextToSpeech? by remember { mutableStateOf(null) }
    DisposableEffect(context) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale.forLanguageTag("vi-VN")
        }
        onDispose { tts?.shutdown() }
    }
    LaunchedEffect(ttsEnabled, text) {
        if (ttsEnabled && text.isNotBlank()) tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "reader")
        else tts?.stop()
    }

    Box(Modifier.fillMaxSize()) {
        Scaffold(
            topBar = {
                if (showControls) {
                    Column {
                        TopAppBar(
                            title = {
                                Text(
                                    readerTitle,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            navigationIcon = {
                                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại") }
                            },
                            actions = {
                                IconButton(onClick = viewModel::toggleBookmark) {
                                    Icon(if (activeBookmark) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, "Đánh dấu")
                                }
                                IconButton(onClick = { viewModel.setTtsEnabled(!ttsEnabled) }) {
                                    Icon(if (ttsEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff, "TTS")
                                }
                            }
                        )
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
                            ReaderTranslationMode.entries.forEachIndexed { index, item ->
                                SegmentedButton(
                                    selected = mode == item,
                                    onClick = { viewModel.setReaderTranslationMode(item) },
                                    shape = SegmentedButtonDefaults.itemShape(index, ReaderTranslationMode.entries.size)
                                ) { Text(item.name) }
                            }
                        }
                    }
                }
            },
            bottomBar = {
                if (showControls) {
                    Surface(tonalElevation = 3.dp) {
                        Column {
                            if (chapters.size > 1) {
                                Row(
                                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text("${chapterIndex + 1}", style = MaterialTheme.typography.labelSmall)
                                    Slider(
                                        value = chapterSeek.coerceIn(0f, chapters.lastIndex.toFloat()),
                                        onValueChange = { chapterSeek = it },
                                        onValueChangeFinished = { viewModel.openChapter(chapterSeek.roundToInt()) },
                                        valueRange = 0f..chapters.lastIndex.toFloat(),
                                        steps = (chapters.size - 2).coerceAtLeast(0),
                                        modifier = Modifier.weight(1f).height(28.dp)
                                    )
                                    Text("${chapters.size}", style = MaterialTheme.typography.labelSmall)
                                }
                            }
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 52.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                IconButton(onClick = { showDrawer = true }) {
                                    Icon(Icons.AutoMirrored.Filled.MenuBook, "Mục lục")
                                }
                                IconButton(onClick = viewModel::previousChapter, enabled = chapterIndex > 0) {
                                    Icon(Icons.AutoMirrored.Filled.NavigateBefore, "Chương trước")
                                }
                                Text("${chapterIndex + 1}/${chapters.size.coerceAtLeast(1)}", style = MaterialTheme.typography.labelMedium)
                                IconButton(onClick = viewModel::nextChapter, enabled = chapterIndex < chapters.lastIndex) {
                                    Icon(Icons.AutoMirrored.Filled.NavigateNext, "Chương sau")
                                }
                                Spacer(Modifier.weight(1f))
                                IconButton(onClick = { showNoteDialog = true }) { Icon(Icons.Default.EditNote, "Ghi chú") }
                                IconButton(onClick = { showSettings = true }) { Icon(Icons.Default.Settings, "Cài đặt") }
                            }
                        }
                    }
                }
            }
        ) { padding ->
            val background = readerBackground(bgColorIndex)
            val foreground = readerForeground(bgColorIndex)
            Box(
                Modifier.fillMaxSize().padding(padding).background(background)
                    .clickable { showControls = !showControls }
            ) {
                when {
                    loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                    else -> ReaderContent(
                        modifier = Modifier.padding(top = if (readerMessage != null) 48.dp else 0.dp),
                        text = text,
                        layout = layout,
                        fontSize = fontSize,
                        lineSpacing = lineSpacing,
                        foreground = foreground,
                        initialAnchor = anchor,
                        onProgress = viewModel::updateParagraphAnchor
                    )
                }
                readerMessage?.let {
                    ReaderStatusBanner(
                        message = it,
                        isWarning = warning != null,
                        bgColorIndex = bgColorIndex,
                        onOpenBrowser = if (warning != null && book?.sourceId?.isNotBlank() == true && browserUrl.isNotBlank()) {
                            { openSourceBrowser(context, book!!.sourceId, browserUrl) }
                        } else null,
                        modifier = Modifier.align(Alignment.TopStart)
                    )
                }
            }
        }
        if (showDrawer) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.42f)).clickable { showDrawer = false })
            ModalDrawerSheet(modifier = Modifier.fillMaxHeight().fillMaxWidth(0.88f)) {
                ReaderDrawer(
                    chapters = chapters,
                    bookmarks = bookmarks,
                    notes = notes,
                    onOpenChapter = { index, paragraph ->
                        showDrawer = false
                        viewModel.openChapter(index, paragraph)
                    },
                    onClose = { showDrawer = false },
                    onDeleteNote = viewModel::deleteNote,
                    onDownloadChapter = { chapter ->
                        book?.let { currentBook ->
                            scope.launch { VbookDownloadScheduler.enqueueRange(context, currentBook.id, chapter.chapterIndex, chapter.chapterIndex) }
                        }
                    }
                )
            }
        }
    }

    if (showSettings) {
        ReaderSettingsBottomSheet(
            fontSize = fontSize,
            bgColorIndex = bgColorIndex,
            lineSpacing = lineSpacing,
            layout = layout,
            refinement = refinement,
            onFontSize = viewModel::setFontSize,
            onBgColor = viewModel::setBgColorIndex,
            onLineSpacing = viewModel::setLineSpacing,
            onLayout = viewModel::setReaderLayout,
            onRefinement = viewModel::setMsOnlineRefinement,
            onDismiss = { showSettings = false }
        )
    }
    if (showNoteDialog) {
        AddNoteDialog(onDismiss = { showNoteDialog = false }, onSave = {
            viewModel.addNote(it)
            showNoteDialog = false
        })
    }
}

@Composable
private fun ReaderContent(
    modifier: Modifier = Modifier,
    text: String,
    layout: String,
    fontSize: Float,
    lineSpacing: Float,
    foreground: Color,
    initialAnchor: Int,
    onProgress: (Int, Float) -> Unit
) {
    val paragraphs = remember(text) { text.lines().filter(String::isNotBlank).ifEmpty { listOf(text) } }
    if (layout == "SCROLL") {
        val state = rememberLazyListState(initialFirstVisibleItemIndex = initialAnchor.coerceIn(0, paragraphs.lastIndex.coerceAtLeast(0)))
        LaunchedEffect(text) {
            state.scrollToItem(initialAnchor.coerceIn(0, paragraphs.lastIndex.coerceAtLeast(0)))
        }
        LaunchedEffect(state.firstVisibleItemIndex, paragraphs.size) {
            onProgress(state.firstVisibleItemIndex, (state.firstVisibleItemIndex + 1f) / paragraphs.size.coerceAtLeast(1))
        }
        LazyColumn(modifier = modifier.fillMaxSize(), state = state, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            itemsIndexed(paragraphs) { _, paragraph ->
                Text(paragraph, color = foreground, fontSize = fontSize.sp, lineHeight = (fontSize * lineSpacing).sp)
            }
        }
    } else {
        BoxWithConstraints(modifier.fillMaxSize()) {
            val charsPerPage = ((maxWidth.value / (fontSize * 0.62f)) * (maxHeight.value / (fontSize * lineSpacing))).toInt().coerceAtLeast(180)
            val pages = remember(paragraphs, charsPerPage) { paginateReaderText(paragraphs, charsPerPage) }
            key(text, charsPerPage) {
                val firstPage = pages.indexOfFirst { initialAnchor in it.firstParagraph..it.lastParagraph }.coerceAtLeast(0)
                val pager = rememberPagerState(initialPage = firstPage) { pages.size }
                LaunchedEffect(pager.currentPage, pages.size) {
                    val page = pages[pager.currentPage]
                    onProgress(page.firstParagraph, (pager.currentPage + 1f) / pages.size.coerceAtLeast(1))
                }
                HorizontalPager(state = pager, modifier = Modifier.fillMaxSize()) { page ->
                    Text(
                        pages[page].text,
                        modifier = Modifier.fillMaxSize().padding(horizontal = 20.dp, vertical = 16.dp),
                        color = foreground,
                        fontSize = fontSize.sp,
                        lineHeight = (fontSize * lineSpacing).sp
                    )
                }
                Text("${pager.currentPage + 1}/${pages.size}", Modifier.align(Alignment.BottomEnd).padding(12.dp), color = foreground, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun ReaderStatusBanner(
    message: String,
    isWarning: Boolean,
    bgColorIndex: Int,
    onOpenBrowser: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        color = readerBackground(bgColorIndex).copy(alpha = 0.96f),
        tonalElevation = 2.dp
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                message,
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 6.dp),
                style = MaterialTheme.typography.labelSmall,
                color = if (isWarning) readerWarning(bgColorIndex) else readerAccent(bgColorIndex),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            onOpenBrowser?.let {
                IconButton(onClick = it) {
                    Icon(Icons.Default.OpenInBrowser, contentDescription = "Mo trinh duyet nguon")
                }
            }
        }
    }
}

internal data class ReaderPage(val text: String, val firstParagraph: Int, val lastParagraph: Int)

internal fun paginateReaderText(paragraphs: List<String>, charsPerPage: Int): List<ReaderPage> {
    val pageSize = charsPerPage.coerceAtLeast(1)
    val pages = mutableListOf<ReaderPage>()
    var buffer = StringBuilder()
    var first = -1
    var last = -1
    fun flush() {
        if (buffer.isEmpty()) return
        pages += ReaderPage(buffer.toString(), first.coerceAtLeast(0), last.coerceAtLeast(first).coerceAtLeast(0))
        buffer = StringBuilder()
        first = -1
        last = -1
    }
    paragraphs.forEachIndexed { index, paragraph ->
        splitReaderParagraph(paragraph, pageSize).forEach { chunk ->
            if (buffer.isNotEmpty() && buffer.length + chunk.length + 2 > pageSize) flush()
            if (buffer.isNotEmpty()) buffer.append("\n\n")
            if (first < 0) first = index
            last = index
            buffer.append(chunk)
        }
    }
    flush()
    return pages.ifEmpty { listOf(ReaderPage("", 0, 0)) }
}

private fun splitReaderParagraph(paragraph: String, pageSize: Int): List<String> {
    var remaining = paragraph.trim()
    if (remaining.length <= pageSize) return listOf(remaining)
    val chunks = mutableListOf<String>()
    val preferredBreaks = charArrayOf(' ', '\n', '。', '！', '？', '；', '，', '.', '!', '?', ';', ',')
    while (remaining.length > pageSize) {
        val candidate = remaining.lastIndexOfAny(preferredBreaks, startIndex = pageSize - 1)
        val splitAt = if (candidate >= pageSize / 2) candidate + 1 else pageSize
        chunks += remaining.take(splitAt).trimEnd()
        remaining = remaining.drop(splitAt).trimStart()
    }
    if (remaining.isNotBlank()) chunks += remaining
    return chunks
}

@Composable
private fun ReaderDrawer(
    chapters: List<Chapter>,
    bookmarks: List<Bookmark>,
    notes: List<ReaderNote>,
    onOpenChapter: (Int, Int) -> Unit,
    onClose: () -> Unit,
    onDeleteNote: (Long) -> Unit,
    onDownloadChapter: (Chapter) -> Unit
) {
    var tab by remember { mutableIntStateOf(0) }
    var reverse by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Nội dung truyện", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(16.dp).weight(1f))
        IconButton(onClick = onClose) { Icon(Icons.Default.Close, "Đóng mục lục") }
    }
    TabRow(tab) {
        listOf("Mục lục", "Đánh dấu", "Ghi chú").forEachIndexed { index, title ->
            Tab(tab == index, onClick = { tab = index }, text = { Text(title) })
        }
    }
    when (tab) {
        0 -> {
            Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(query, { query = it }, modifier = Modifier.weight(1f), singleLine = true, placeholder = { Text("Tìm chương") })
                IconButton(onClick = { reverse = !reverse }) { Icon(Icons.Default.SwapVert, "Đảo thứ tự") }
            }
            val filtered = chapters.filter { it.title.contains(query, true) }.let { if (reverse) it.reversed() else it }
            LazyColumn {
                items(filtered, key = { it.id }) { chapter ->
                    ListItem(
                        headlineContent = { Text(rememberTranslatedUiText(chapter.title, UiTextFieldType.CHAPTER_TITLE), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = { if (chapter.downloadStatus == "DOWNLOADED") Text("Đã tải") },
                        modifier = Modifier.clickable { onOpenChapter(chapter.chapterIndex, 0) },
                        trailingContent = {
                            IconButton(onClick = { onDownloadChapter(chapter) }) { Icon(Icons.Default.CloudDownload, "Tải chương") }
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
        1 -> LazyColumn {
            items(bookmarks, key = { it.id }) { item ->
                ListItem(
                    headlineContent = { Text("Chương ${item.chapterIndex + 1}") },
                    supportingContent = { Text(rememberTranslatedUiText(item.excerpt, UiTextFieldType.EXCERPT), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.clickable { onOpenChapter(item.chapterIndex, item.paragraphAnchor) }
                )
            }
        }
        else -> LazyColumn {
            items(notes, key = { it.id }) { item ->
                ListItem(
                    headlineContent = { Text(item.content, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = { Text(rememberTranslatedUiText(item.excerpt, UiTextFieldType.EXCERPT), maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    modifier = Modifier.clickable { onOpenChapter(item.chapterIndex, item.paragraphAnchor) },
                    trailingContent = { TextButton(onClick = { onDeleteNote(item.id) }) { Text("Xóa") } }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ReaderSettingsBottomSheet(
    fontSize: Float,
    bgColorIndex: Int,
    lineSpacing: Float,
    layout: String,
    refinement: Boolean,
    onFontSize: (Float) -> Unit,
    onBgColor: (Int) -> Unit,
    onLineSpacing: (Float) -> Unit,
    onLayout: (String) -> Unit,
    onRefinement: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp).padding(bottom = 24.dp)) {
            Text("Cài đặt đọc", style = MaterialTheme.typography.titleLarge)
            Text("Cỡ chữ ${fontSize.toInt()}sp")
            Slider(fontSize, onFontSize, valueRange = 14f..34f)
            Text("Giãn dòng ${"%.1f".format(lineSpacing)}")
            Slider(lineSpacing, onLineSpacing, valueRange = 1f..2f)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(Color.White, Color(0xFFF4ECD8), Color(0xFFE6EEE6), Color(0xFF1E1E1E)).forEachIndexed { index, color ->
                    Box(Modifier.width(42.dp).height(42.dp).background(color).clickable { onBgColor(index) })
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Phân trang", Modifier.weight(1f))
                Switch(layout == "PAGED", { onLayout(if (it) "PAGED" else "SCROLL") })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("Tinh chỉnh online cho MS", Modifier.weight(1f))
                Switch(refinement, onRefinement)
            }
        }
    }
}

@Composable
private fun AddNoteDialog(onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var note by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Thêm ghi chú") },
        text = { OutlinedTextField(note, { note = it }, modifier = Modifier.fillMaxWidth(), minLines = 3) },
        confirmButton = { TextButton(onClick = { onSave(note) }, enabled = note.isNotBlank()) { Text("Lưu") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Đóng") } }
    )
}

private fun readerBackground(index: Int): Color = when (index) {
    1 -> Color(0xFFF4ECD8)
    2 -> Color(0xFFE6EEE6)
    3 -> Color(0xFF1E1E1E)
    else -> Color(0xFFFAFAFA)
}

private fun readerForeground(index: Int): Color = if (index == 3) Color(0xFFE6E1E5) else Color(0xFF25212A)

private fun readerWarning(index: Int): Color = if (index == 3) Color(0xFFFFB4AB) else Color(0xFFB3261E)

private fun readerAccent(index: Int): Color = if (index == 3) Color(0xFFD0BCFF) else Color(0xFF5B3E96)
