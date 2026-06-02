package com.example.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Cài đặt", color = MaterialTheme.colorScheme.onBackground) },
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
        ) {
            // User Info Section
            SettingSection(title = "Thông tin User") {
                SettingItem(
                    icon = { Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    title = "Tài khoản",
                    subtitle = "drducqy95@gmail.com"
                )
            }

            // System App Settings
            SettingSection(title = "Cài đặt hệ thống của App") {
                SettingItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    title = "Giao diện & Hiển thị",
                    subtitle = "Sáng, tối, tự động, cỡ chữ, phông chữ"
                )
                SettingItem(
                    icon = { Icon(Icons.Default.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    title = "Đồng bộ đám mây",
                    subtitle = "Google Drive (Đang bật)"
                )
            }

            // Offline Packages Management (TTS & Dictionaries)
            SettingSection(title = "Quản lý dữ liệu Offline (Tải trong App)") {
                DownloadableSettingItem(
                    icon = { Icon(Icons.Default.Translate, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    title = "Từ điển Trung - Việt (125MB)",
                    subtitle = "Từ điển đồ thị ngữ nghĩa (Cần tải xuống để dùng offline)"
                )
                DownloadableSettingItem(
                    icon = { Icon(Icons.Default.Language, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    title = "Từ điển Anh - Việt (15MB)",
                    subtitle = "Từ điển cơ bản offline (Cần tải xuống để dùng offline)"
                )
                DownloadableSettingItem(
                    icon = { Icon(Icons.Default.CloudDownload, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    title = "Gói giọng nói tiếng Việt TTS (45MB)",
                    subtitle = "Trình đọc sách Offline chất lượng cao"
                )
            }

            // Backend & Crawl Data Configuration
            SettingSection(title = "Backend dịch và crawl data (Legado-QT)") {
                SettingItem(
                    icon = { Icon(Icons.Default.CloudDownload, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    title = "Cấu hình Server Remote",
                    subtitle = "http://localhost:8080 (Trạng thái: Hoạt động)"
                )
                SettingItem(
                    icon = { Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                    title = "Quản lý Extension (Crawl Data)",
                    subtitle = "Đã kích hoạt 4 extension lấy truyện"
                )
            }
            
            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun SettingSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 16.dp, top = 24.dp, bottom = 8.dp, end = 16.dp),
            fontWeight = FontWeight.Bold
        )
        content()
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp, start = 16.dp, end = 16.dp), color = MaterialTheme.colorScheme.outline.copy(alpha = 0.5f))
    }
}

@Composable
fun SettingItem(icon: @Composable () -> Unit, title: String, subtitle: String, onClick: () -> Unit = {}) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.padding(end = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            icon()
        }
        Column {
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun DownloadableSettingItem(icon: @Composable () -> Unit, title: String, subtitle: String) {
    var downloadState by remember { mutableStateOf(0) } // 0: Not downloaded, 1: Downloading, 2: Downloaded
    var progress by remember { mutableStateOf(0f) }
    val coroutineScope = rememberCoroutineScope()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable {
                if (downloadState == 0) {
                    downloadState = 1
                    coroutineScope.launch {
                        for (i in 1..100) {
                            delay(30)
                            progress = i / 100f
                        }
                        downloadState = 2
                    }
                }
            }
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier.padding(end = 16.dp),
            contentAlignment = Alignment.Center
        ) {
            icon()
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = if (downloadState == 2) "Đã tải xuống" else subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = if (downloadState == 2) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (downloadState == 1) {
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier.fillMaxWidth().height(4.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
        Box(modifier = Modifier.padding(start = 16.dp)) {
            when (downloadState) {
                0 -> Icon(Icons.Default.Download, contentDescription = "Tải xuống", tint = MaterialTheme.colorScheme.primary)
                1 -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.primary)
                2 -> Icon(Icons.Default.CheckCircle, contentDescription = "Hoàn tất", tint = MaterialTheme.colorScheme.primary)
            }
        }
    }
}
