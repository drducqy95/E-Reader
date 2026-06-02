package com.example.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.crawler.ExtensionRepository
import com.example.crawler.ExtensionParser
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceManagementScreen(onBack: () -> Unit) {
    val extensions by ExtensionRepository.extensions.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var extUrl by remember { mutableStateOf("https://raw.githubusercontent.com/duongden/vbook/main/plugin.json") }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    val coroutineScope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Quản lý nguồn") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Thêm nguồn")
            }
        }
    ) { padding ->
        if (extensions.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Chưa có nguồn truyện nào được cài đặt.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
                items(extensions) { ext ->
                    ListItem(
                        headlineContent = { Text(ext.name, color = MaterialTheme.colorScheme.onBackground) },
                        supportingContent = { Text("Tác giả: ${ext.author} - v${ext.version}\n${ext.description}", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                        leadingContent = { Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = {
                            IconButton(onClick = { ExtensionRepository.removeExtension(ext.id) }) {
                                Icon(Icons.Default.Delete, contentDescription = "Xóa", tint = MaterialTheme.colorScheme.error)
                            }
                        },
                        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background)
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }

        if (showAddDialog) {
            AlertDialog(
                onDismissRequest = { if (!isLoading) showAddDialog = false },
                title = { Text("Thêm nguồn từ ZIP") },
                text = {
                    Column {
                        Text("Nhập URL của file plugin.zip (VBook-compatible):")
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = extUrl,
                            onValueChange = { extUrl = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        if (isLoading) {
                            Spacer(modifier = Modifier.height(16.dp))
                            LinearProgressIndicator(modifier = Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.primary)
                        }
                        if (errorMessage.isNotBlank()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(errorMessage, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            isLoading = true
                            errorMessage = ""
                            coroutineScope.launch {
                                val resultList = withContext(Dispatchers.IO) {
                                    ExtensionParser.parseUrlOrRepo(extUrl)
                                }
                                isLoading = false
                                if (resultList.isNotEmpty()) {
                                    val error = resultList.firstOrNull { it.id == "error" }
                                    if (error != null) {
                                        errorMessage = error.description
                                    } else {
                                        resultList.forEach { ext ->
                                            ExtensionRepository.addExtension(ext)
                                        }
                                        showAddDialog = false
                                    }
                                } else {
                                    errorMessage = "Không thể tải hoặc parse nội dung này."
                                }
                            }
                        },
                        enabled = !isLoading && extUrl.isNotBlank()
                    ) {
                        Text("Cài đặt")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showAddDialog = false }, enabled = !isLoading) {
                        Text("Hủy")
                    }
                }
            )
        }
    }
}
