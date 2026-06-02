package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.EReaderApplication
import com.example.crawler.ExtensionRepository
import com.example.crawler.VbookBookSummary
import com.example.crawler.VbookHomeTab
import com.example.crawler.openSourceBrowser
import com.example.data.UiTextFieldType
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(onBookClick: (String, String) -> Unit, onManageSourcesClick: () -> Unit) {
    val app = LocalContext.current.applicationContext as EReaderApplication
    val extensions by ExtensionRepository.extensions.collectAsState()
    val legadoSources by app.legadoSourceRepository.sources.collectAsState()
    val sources = remember(extensions, legadoSources) {
        (extensions.map { OnlineExploreSource(it.id, it.name, it.source) } +
            legadoSources.map { OnlineExploreSource(it.id, it.name, it.sourceUrl) }).sortedBy(OnlineExploreSource::name)
    }
    val scope = rememberCoroutineScope()
    var selectedSourceId by remember { mutableStateOf<String?>(null) }
    var selectedTab by remember { mutableIntStateOf(0) }
    var expanded by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var tabs by remember { mutableStateOf<List<VbookHomeTab>>(emptyList()) }
    var tabsSourceId by remember { mutableStateOf<String?>(null) }
    var results by remember { mutableStateOf<List<VbookBookSummary>>(emptyList()) }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var requestVersion by remember { mutableIntStateOf(0) }
    var requestedTabKey by remember { mutableStateOf<String?>(null) }
    val source = sources.firstOrNull { it.id == selectedSourceId } ?: sources.firstOrNull()

    fun load(block: suspend () -> List<VbookBookSummary>) {
        val version = ++requestVersion
        scope.launch {
            loading = true
            error = ""
            runCatching { block() }
                .onSuccess { if (version == requestVersion) results = it }
                .onFailure { if (version == requestVersion) error = it.message ?: "Không thể tải nguồn truyện." }
            if (version == requestVersion) loading = false
        }
    }

    LaunchedEffect(source?.id) {
        requestVersion++
        tabsSourceId = null
        requestedTabKey = null
        tabs = emptyList()
        results = emptyList()
        error = ""
        selectedTab = 0
        val current = source ?: return@LaunchedEffect
        if (selectedSourceId == null) selectedSourceId = current.id
        loading = true
        runCatching { app.onlineCrawlerService.home(current.id) }
            .onSuccess {
                tabs = it
                tabsSourceId = current.id
            }
            .onFailure { error = it.message ?: "Nguồn không có trang khám phá." }
        loading = false
    }
    LaunchedEffect(source?.id, tabsSourceId, selectedTab) {
        val current = source ?: return@LaunchedEffect
        if (tabsSourceId != current.id) return@LaunchedEffect
        val tab = tabs.getOrNull(selectedTab) ?: return@LaunchedEffect
        val tabKey = "${current.id}:${tab.script}:${tab.input}:$selectedTab"
        if (requestedTabKey == tabKey) return@LaunchedEffect
        requestedTabKey = tabKey
        load { app.onlineCrawlerService.list(current.id, tab.script, tab.input) }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("Khám phá") }, actions = {
            IconButton(onClick = onManageSourcesClick) { Icon(Icons.Default.Settings, "Quản lý nguồn") }
        })
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    TextButton(onClick = { expanded = true }) {
                        Column(horizontalAlignment = Alignment.Start) {
                            Text(source?.let { rememberTranslatedUiText(it.name, UiTextFieldType.SOURCE_METADATA) } ?: "Chọn nguồn")
                            source?.host?.takeIf(String::isNotBlank)?.let { Text(it, style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                        }
                    }
                    DropdownMenu(expanded, { expanded = false }) {
                        sources.forEach { item ->
                            DropdownMenuItem(
                                { Text(rememberTranslatedUiText(item.name, UiTextFieldType.SOURCE_METADATA)) },
                                onClick = { selectedSourceId = item.id; expanded = false }
                            )
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                OutlinedTextField(
                    query,
                    { query = it },
                    Modifier.weight(1f),
                    singleLine = true,
                    placeholder = { Text("Tìm truyện") },
                    trailingIcon = {
                        IconButton(onClick = { source?.let { load { app.onlineCrawlerService.search(it.id, query.trim()) } } }, enabled = source != null && query.isNotBlank()) {
                            Icon(Icons.Default.Search, "Tìm")
                        }
                    }
                )
            }
            if (tabs.isNotEmpty()) {
                ScrollableTabRow(selectedTab) {
                    tabs.forEachIndexed { index, tab ->
                        Tab(
                            selectedTab == index,
                            { selectedTab = index },
                            text = { Text(rememberTranslatedUiText(tab.title, UiTextFieldType.EXPLORE_TAB)) }
                        )
                    }
                }
            }
            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                error.isNotBlank() && results.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(error, color = MaterialTheme.colorScheme.error)
                        source?.takeIf { it.host.isNotBlank() }?.let { current ->
                            TextButton(onClick = { openSourceBrowser(app, current.id, browserTargetUrl(error, current.host)) }) {
                                Icon(Icons.Default.OpenInBrowser, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("Mo trinh duyet nguon")
                            }
                        }
                    }
                }
                results.isNotEmpty() -> LazyVerticalGrid(
                    GridCells.Fixed(3),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    items(results, key = { "${it.sourceId}:${it.url}" }) { book -> ExploreBookItem(book) { onBookClick(book.sourceId, book.url) } }
                }
                else -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Language, null)
                        Text(if (sources.isEmpty()) "Chưa có nguồn. Hãy cài extension VBook hoặc Legado JSON." else "Chưa có kết quả.")
                    }
                }
            }
        }
    }
}

@Composable
private fun ExploreBookItem(book: VbookBookSummary, onClick: () -> Unit) {
    val translatedTitle = rememberTranslatedUiText(book.title, UiTextFieldType.BOOK_TITLE)
    val translatedAuthor = rememberTranslatedUiText(book.author, UiTextFieldType.AUTHOR)
    Column(Modifier.clickable(onClick = onClick), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).background(MaterialTheme.colorScheme.surfaceVariant)) {
            if (book.coverUrl.isNotBlank()) AsyncImage(book.coverUrl, book.title, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Text(translatedTitle, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (book.author.isNotBlank()) Text(translatedAuthor, style = MaterialTheme.typography.labelSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

private data class OnlineExploreSource(val id: String, val name: String, val host: String)

internal fun browserTargetUrl(error: String, fallback: String): String =
    Regex("""https?://[^\s]+""").find(error)?.value?.trimEnd('.', ',', ';', ')') ?: fallback
