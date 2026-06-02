package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.crawler.JsEngine
import com.example.crawler.ExtensionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// Removed VBOOK_SEARCH_SCRIPT

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExploreScreen(
    onBookClick: (String) -> Unit,
    onManageSourcesClick: () -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }
    var isSearching by remember { mutableStateOf(false) }
    var searchResults by remember { mutableStateOf<List<ExploreBook>>(emptyList()) }
    
    var exploreTabs by remember { mutableStateOf<List<Map<String, String>>>(emptyList()) }
    var selectedTabIndex by remember { mutableStateOf(0) }
    var exploreResults by remember { mutableStateOf<List<ExploreBook>>(emptyList()) }
    var isLoadingExplore by remember { mutableStateOf(false) }
    var errorMsg by remember { mutableStateOf("") }
    
    val coroutineScope = rememberCoroutineScope()
    val focusManager = androidx.compose.ui.platform.LocalFocusManager.current
    val jsEngine = remember { JsEngine() }
    val extensions by ExtensionRepository.extensions.collectAsState()
    
    var selectedExtensionIndex by remember { mutableIntStateOf(0) }
    val selectedExtension = if (extensions.isNotEmpty() && selectedExtensionIndex in extensions.indices) extensions[selectedExtensionIndex] else null
    var isDropdownExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(selectedExtension) {
        exploreTabs = emptyList()
        exploreResults = emptyList()
        searchResults = emptyList()
        selectedTabIndex = 0
        errorMsg = ""
        
        if (selectedExtension != null) {
            isLoadingExplore = true
            val homeScript = selectedExtension.scripts["home"] ?: selectedExtension.scripts["home.js"]
            if (homeScript != null) {
                withContext(Dispatchers.IO) {
                    try {
                        val rs = jsEngine.executeVbook(homeScript, selectedExtension.scripts, "execute")
                        if (rs is Map<*, *>) {
                            if (rs.containsKey("error")) {
                                withContext(Dispatchers.Main) {
                                    isLoadingExplore = false
                                    errorMsg = "JS Error: ${rs["error"]}"
                                }
                            } else {
                                val data = rs["data"]
                                if (data is List<*>) {
                                    val tabs = mutableListOf<Map<String, String>>()
                                    data.forEach { item ->
                                        if (item is Map<*, *>) {
                                            val title = item["title"]?.toString() ?: ""
                                            val input = item["input"]?.toString() ?: ""
                                            val script = item["script"]?.toString() ?: ""
                                            tabs.add(mapOf("title" to title, "input" to input, "script" to script))
                                        }
                                    }
                                    withContext(Dispatchers.Main) {
                                        exploreTabs = tabs
                                        isLoadingExplore = false
                                    }
                                } else {
                                    withContext(Dispatchers.Main) {
                                        isLoadingExplore = false
                                        errorMsg = "Lỗi định dạng dữ liệu (không có data block)\nRS keys: ${rs.keys}"
                                    }
                                }
                            }
                        } else if (rs is List<*>) {
                            // Support Vbook plugins that return list directly
                            val tabs = mutableListOf<Map<String, String>>()
                            rs.forEach { item ->
                                if (item is Map<*, *>) {
                                    val title = item["title"]?.toString() ?: ""
                                    val input = item["input"]?.toString() ?: ""
                                    val script = item["script"]?.toString() ?: ""
                                    tabs.add(mapOf("title" to title, "input" to input, "script" to script))
                                }
                            }
                            withContext(Dispatchers.Main) {
                                exploreTabs = tabs
                                isLoadingExplore = false
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                isLoadingExplore = false
                                errorMsg = "Kết quả không hợp lệ: $rs"
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            isLoadingExplore = false
                            errorMsg = "Không thể tải cấu hình nguồn: ${e.message}"
                        }
                    }
                }
            } else {
                isLoadingExplore = false
            }
        }
    }

    LaunchedEffect(selectedTabIndex, exploreTabs, selectedExtension) {
        if (exploreTabs.isNotEmpty() && !isSearching && selectedExtension != null) {
            isLoadingExplore = true
            errorMsg = ""
            val tab = exploreTabs[selectedTabIndex]
            val input = tab["input"] ?: ""
            val scriptName = tab["script"] ?: ""
            val genScript = selectedExtension.scripts[scriptName] ?: selectedExtension.scripts.entries.find { it.key.endsWith(scriptName) }?.value
            
            if (genScript != null) {
                withContext(Dispatchers.IO) {
                    try {
                        val rs = jsEngine.executeVbook(genScript, selectedExtension.scripts, "execute", input, "1")
                        val list = mutableListOf<ExploreBook>()
                        if (rs is Map<*, *>) {
                            if (rs.containsKey("error")) {
                                withContext(Dispatchers.Main) {
                                    exploreResults = emptyList()
                                    isLoadingExplore = false
                                    errorMsg = "JS Error: ${rs["error"]}"
                                }
                            } else {
                                val data = rs["data"]
                                if (data is List<*>) {
                                    data.forEach { item ->
                                        if (item is Map<*, *>) {
                                            val url = item["link"]?.toString() ?: item["url"]?.toString() ?: ""
                                            list.add(
                                                ExploreBook(
                                                    id = url,
                                                    title = item["name"]?.toString() ?: "No title",
                                                    author = item["author"]?.toString() ?: item["description"]?.toString() ?: "Unknown",
                                                    category = selectedExtension.name,
                                                    url = url
                                                )
                                            )
                                        }
                                    }
                                    withContext(Dispatchers.Main) {
                                        exploreResults = list
                                        isLoadingExplore = false
                                        if (list.isEmpty()) {
                                            errorMsg = "Không có truyện nào."
                                        }
                                    }
                                } else {
                                    withContext(Dispatchers.Main) {
                                        exploreResults = emptyList()
                                        isLoadingExplore = false
                                        errorMsg = "Dữ liệu trả về không hợp lệ (data không phải List)\nkeys: ${rs.keys}"
                                    }
                                }
                            }
                        } else if (rs is List<*>) {
                            rs.forEach { item ->
                                if (item is Map<*, *>) {
                                    val url = item["link"]?.toString() ?: item["url"]?.toString() ?: ""
                                    list.add(
                                        ExploreBook(
                                            id = url,
                                            title = item["name"]?.toString() ?: "No title",
                                            author = item["author"]?.toString() ?: item["description"]?.toString() ?: "Unknown",
                                            category = selectedExtension.name,
                                            url = url
                                        )
                                    )
                                }
                            }
                            withContext(Dispatchers.Main) {
                                exploreResults = list
                                isLoadingExplore = false
                                if (list.isEmpty()) {
                                    errorMsg = "Không có truyện nào."
                                }
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                exploreResults = emptyList()
                                isLoadingExplore = false
                                errorMsg = "Kết quả tải trang không hợp lệ: $rs"
                            }
                        }
                    } catch (e: Exception) {
                        withContext(Dispatchers.Main) {
                            isLoadingExplore = false
                            errorMsg = "Lỗi: ${e.message}"
                        }
                    }
                }
            } else {
                isLoadingExplore = false
                errorMsg = "Không tìm thấy script: $scriptName"
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Khám phá", color = MaterialTheme.colorScheme.onBackground) },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                ),
                actions = {
                    IconButton(onClick = onManageSourcesClick) {
                        Icon(Icons.Default.Settings, contentDescription = "Quản lý nguồn")
                    }
                }
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // Dropdown source selector
            if (extensions.isNotEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    OutlinedButton(
                        onClick = { isDropdownExpanded = true },
                        modifier = Modifier.fillMaxWidth(),
                        shape = MaterialTheme.shapes.medium
                    ) {
                        Text(selectedExtension?.name ?: "Chọn nguồn")
                    }
                    DropdownMenu(
                        expanded = isDropdownExpanded,
                        onDismissRequest = { isDropdownExpanded = false },
                        modifier = Modifier.fillMaxWidth(0.9f)
                    ) {
                        extensions.forEachIndexed { index, ext ->
                            DropdownMenuItem(
                                text = { Text(ext.name) },
                                onClick = {
                                    selectedExtensionIndex = index
                                    isDropdownExpanded = false
                                }
                            )
                        }
                    }
                }
            }

            // Search Bar
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                placeholder = { Text("Tìm kiếm truyện, tác giả...") },
                trailingIcon = {
                    IconButton(onClick = {
                        focusManager.clearFocus()
                        if (searchQuery.isNotBlank() && selectedExtension != null) {
                            isSearching = true
                            coroutineScope.launch {
                                val results = withContext(Dispatchers.IO) {
                                    val list = mutableListOf<ExploreBook>()
                                    val searchScript = selectedExtension.scripts["search"] ?: selectedExtension.scripts["search.js"]
                                    if (searchScript != null) {
                                        try {
                                            val rs = jsEngine.executeVbook(searchScript, selectedExtension.scripts, "execute", searchQuery, "1")
                                            if (rs is Map<*, *>) {
                                                val data = rs["data"]
                                                if (data is List<*>) {
                                                    data.forEach { item ->
                                                        if (item is Map<*, *>) {
                                                            val url = item["link"]?.toString() ?: item["url"]?.toString() ?: ""
                                                            list.add(
                                                                ExploreBook(
                                                                    id = url,
                                                                    title = item["name"]?.toString() ?: "No title",
                                                                    author = item["author"]?.toString() ?: "Unknown",
                                                                    category = selectedExtension.name,
                                                                    url = url
                                                                )
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        } catch(e: Exception) {}
                                    }
                                    list
                                }
                                searchResults = results
                                isSearching = false
                            }
                        }
                    }) {
                        Icon(Icons.Default.Search, contentDescription = "Search")
                    }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.large,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = {
                    focusManager.clearFocus()
                    if (searchQuery.isNotBlank() && selectedExtension != null) {
                        isSearching = true
                        coroutineScope.launch {
                            val results = withContext(Dispatchers.IO) {
                                val list = mutableListOf<ExploreBook>()
                                val searchScript = selectedExtension.scripts["search"] ?: selectedExtension.scripts["search.js"]
                                if (searchScript != null) {
                                    try {
                                        val rs = jsEngine.executeVbook(searchScript, selectedExtension.scripts, "execute", searchQuery, "1")
                                        if (rs is Map<*, *>) {
                                            val data = rs["data"]
                                            if (data is List<*>) {
                                                data.forEach { item ->
                                                    if (item is Map<*, *>) {
                                                        val url = item["link"]?.toString() ?: item["url"]?.toString() ?: ""
                                                        list.add(
                                                            ExploreBook(
                                                                id = url,
                                                                title = item["name"]?.toString() ?: "No title",
                                                                author = item["author"]?.toString() ?: "Unknown",
                                                                category = selectedExtension.name,
                                                                url = url
                                                            )
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    } catch(e: Exception) {}
                                }
                                list
                            }
                            searchResults = results
                            isSearching = false
                        }
                    }
                })
            )

            // Content
            if (isSearching && searchQuery.isNotBlank()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (searchQuery.isNotBlank() && searchResults.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(searchResults) { book ->
                        ExploreBookItem(book = book, onClick = { onBookClick(book.url) })
                    }
                }
            } else if (searchQuery.isNotBlank()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Không tìm thấy kết quả", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else if (isLoadingExplore && exploreTabs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                }
            } else if (errorMsg.isNotBlank() && exploreTabs.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(errorMsg, color = MaterialTheme.colorScheme.error, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(16.dp))
                }
            } else if (exploreTabs.isNotEmpty()) {
                ScrollableTabRow(
                    selectedTabIndex = selectedTabIndex,
                    containerColor = MaterialTheme.colorScheme.surface,
                    contentColor = MaterialTheme.colorScheme.primary,
                    edgePadding = 16.dp
                ) {
                    exploreTabs.forEachIndexed { index, tab ->
                        Tab(
                            selected = index == selectedTabIndex,
                            onClick = { selectedTabIndex = index },
                            text = { Text(tab["title"] ?: "", maxLines = 1, overflow = TextOverflow.Ellipsis) }
                        )
                    }
                }
                
                if (isLoadingExplore) {
                    Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                } else if (errorMsg.isNotBlank()) {
                    Box(modifier = Modifier.fillMaxSize().weight(1f), contentAlignment = Alignment.Center) {
                        Text(errorMsg, color = MaterialTheme.colorScheme.error, textAlign = androidx.compose.ui.text.style.TextAlign.Center, modifier = Modifier.padding(16.dp))
                    }
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize().weight(1f),
                        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(exploreResults) { book ->
                            ExploreBookItem(book = book, onClick = { onBookClick(book.url) })
                        }
                    }
                }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            if (extensions.isEmpty()) "Chưa có nguồn nào. Hãy thêm ở Quản lý Nguồn" 
                            else "Nguồn này không có dữ liệu khám phá", 
                            style = MaterialTheme.typography.bodyLarge, 
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

data class ExploreBook(
    val id: String,
    val title: String,
    val author: String,
    val category: String,
    val url: String
)

@Composable
fun ExploreBookItem(book: ExploreBook, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(60.dp, 80.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.small),
                contentAlignment = Alignment.Center
            ) {
                Text("WEB", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(text = book.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onBackground)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = book.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(text = book.category, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
