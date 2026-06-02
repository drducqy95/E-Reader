package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.viewmodel.LibraryViewModel
import com.example.data.Book
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookDetailScreen(
    bookUrl: String, 
    onBack: () -> Unit,
    libraryViewModel: LibraryViewModel,
    onOpenReader: (Int) -> Unit
) {
    var isLoading by remember { mutableStateOf(true) }
    var isDownloading by remember { mutableStateOf(false) }
    var isSaved by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    
    // Simulate fetching book details
    LaunchedEffect(bookUrl) {
        delay(600)
        isLoading = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Chi tiết Sách", color = MaterialTheme.colorScheme.onBackground) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                    navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { padding ->
        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        } else {
            Column(modifier = Modifier.fillMaxSize().padding(padding).padding(16.dp)) {
                // Header Book info
                Row(modifier = Modifier.fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .size(100.dp, 140.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("Bìa", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text("Tên sách Online", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                        Text("URL: $bookUrl", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                        Text("Tác giả: Đang cập nhật", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Nguồn: Web Crawl (Legado Ext)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "Trạng thái: Đang ra\nSố chương: 530 chương", 
                            style = MaterialTheme.typography.bodySmall, 
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))
                
                // Action Buttons
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    Button(
                        onClick = {
                            isSaved = true
                            // Save logic (Read online)
                            coroutineScope.launch {
                                val book = Book(title = "Sách Online", author = "Web", format = "WEB", uriString = bookUrl)
                                libraryViewModel.insertBook(book)
                            }
                        },
                        enabled = !isSaved
                    ) {
                        Icon(Icons.Default.LibraryAdd, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isSaved) "Đã lưu" else "Lưu thư viện")
                    }

                    FilledTonalButton(
                        onClick = {
                            if (!isDownloading) {
                                isDownloading = true
                                isSaved = true
                                coroutineScope.launch {
                                    val book = Book(title = "Sách Tải Về", author = "Web", format = "WEB", uriString = bookUrl, progress = 0.01f) // slight progress to indicate start
                                    libraryViewModel.insertBook(book)
                                    delay(1500) // giả lập thời gian tải chương offline
                                    isDownloading = false
                                }
                            }
                        }
                    ) {
                        if (isDownloading) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onSecondaryContainer)
                        } else {
                            Icon(Icons.Default.CloudDownload, contentDescription = null)
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(if (isDownloading) "Đang tải..." else "Tải offline")
                    }
                }
                
                Spacer(modifier = Modifier.height(24.dp))
                
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                
                // Features
                ListItem(
                    headlineContent = { Text("Mục lục", color = MaterialTheme.colorScheme.onBackground) },
                    supportingContent = { Text("530 chương - Cập nhật gần đây", color = MaterialTheme.colorScheme.onSurfaceVariant) },
                    leadingContent = { Icon(Icons.AutoMirrored.Filled.List, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
                    modifier = Modifier.clickable { /* Show TOC */ },
                    colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.background)
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                
                Spacer(modifier = Modifier.height(24.dp))
                Text("Giới thiệu", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "Đây là phần tóm tắt nội dung sách được crawler quét bằng RegExp và JS mô phỏng theo cấu trúc Legado. Bạn có thể Lưu thư viện để đọc online, hoặc Tải offline để đọc mà không cần kết nối mạng.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
