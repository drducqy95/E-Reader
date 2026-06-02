package com.example.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.EReaderApplication
import com.example.data.DictionaryType
import com.example.data.GraphDownloadScheduler
import com.example.data.GraphPackageStatus
import com.example.data.ProductionExpansionConfig
import com.example.web.LocalWebService
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit) {
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Cài đặt") },
            navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Quay lại") } }
        )
    }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            SettingSection("Dữ liệu dịch offline") {
                GraphPackageSettingItem()
                DictionaryPackagesSetting()
            }
            SettingSection("Reader dịch") {
                ReaderDefaultsSettingItem()
                OnlineProviderSettingItem()
            }
            SettingSection("Crawl và công cụ") {
                WebConsoleSettingItem()
                SettingItem({ Icon(Icons.Default.Settings, null) }, "Quản lý extension", "Nguồn Legado JSON và VBook JS chạy qua Rhino sandbox.")
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

@Composable
private fun GraphPackageSettingItem() {
    val context = LocalContext.current
    val manager = remember { (context.applicationContext as EReaderApplication).graphPackageManager }
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf(manager.status()) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val production = remember { ProductionExpansionConfig.fromBuild() }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) scope.launch {
            busy = true
            runCatching {
                withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { manager.importGraph(it, source = "manual:$uri") } ?: error("Không thể mở file") }
            }.onSuccess { status = it }.onFailure { error = it.message }
            busy = false
        }
    }
    PackageRow(
        icon = { Icon(Icons.Default.Translate, null) },
        title = "Graph DrDuc",
        subtitle = when {
            error != null -> "Lỗi: $error"
            !status.installed -> "Chưa cài translation_graph.mobile.sqlite"
            else -> "Version ${status.graphVersion} · ${formatBytes(status.bytes)} · context ${if (status.contextUniverseAvailable) "ready" else "thiếu"}"
        },
        onClick = { if (!busy) picker.launch(arrayOf("*/*")) },
        action = {
            when {
                busy -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                status.installed -> IconButton(onClick = { manager.deleteGraph(); status = manager.status() }) { Icon(Icons.Default.Delete, "Xóa graph") }
                production.configured -> IconButton(onClick = { GraphDownloadScheduler.enqueueProduction(context) }) { Icon(Icons.Default.CloudDownload, "Tải graph") }
                else -> Icon(Icons.Default.FolderOpen, null)
            }
        }
    )
}

@Composable
private fun DictionaryPackagesSetting() {
    val context = LocalContext.current
    val app = context.applicationContext as EReaderApplication
    val manager = remember { app.dictionaryPackageManager }
    val packages by app.database.readerDao().observeDictionaryPackages().collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<DictionaryType?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val type = pending
        if (uri != null && type != null) scope.launch {
            runCatching { withContext(Dispatchers.IO) { context.contentResolver.openInputStream(uri)?.use { manager.import(type, it) } ?: error("Không thể mở file") } }
                .onFailure { error = it.message }
            pending = null
        }
    }
    LaunchedEffect(Unit) { manager.scanInstalled() }
    DictionaryType.entries.forEach { type ->
        val status = packages.firstOrNull { it.type == type.name }
        PackageRow(
            icon = { Icon(Icons.Default.Language, null) },
            title = type.fileName,
            subtitle = status?.let { "${it.entryCount} mục · ${it.version}" } ?: "Chưa cài · import file hoặc tải bằng API có checksum",
            onClick = { pending = type; picker.launch(arrayOf("*/*")) },
            action = {
                if (status != null) IconButton(onClick = { scope.launch { manager.delete(type) } }) { Icon(Icons.Default.Delete, "Xóa ${type.fileName}") }
                else Icon(Icons.Default.FolderOpen, null)
            }
        )
    }
    error?.let { Text("Lỗi dictionary: $it", color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 16.dp)) }
}

@Composable
private fun ReaderDefaultsSettingItem() {
    val app = LocalContext.current.applicationContext as EReaderApplication
    val settings = app.settingsRepository
    val scope = rememberCoroutineScope()
    val refinement by settings.msOnlineRefinement.collectAsState(true)
    val concurrency by settings.downloadConcurrency.collectAsState(2)
    val retries by settings.downloadRetries.collectAsState(3)
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Tinh chỉnh online cho MS"); Text("Hiển thị DrDuc offline trước, refined text sau.", style = MaterialTheme.typography.bodySmall) }
            Switch(refinement, { scope.launch { settings.setMsOnlineRefinement(it) } })
        }
        Text("Tải đồng thời: $concurrency luồng", style = MaterialTheme.typography.bodySmall)
        Slider(concurrency.toFloat(), { scope.launch { settings.setDownloadConcurrency(it.toInt()) } }, valueRange = 1f..6f, steps = 4)
        Text("Retry: $retries lần", style = MaterialTheme.typography.bodySmall)
        Slider(retries.toFloat(), { scope.launch { settings.setDownloadRetries(it.toInt()) } }, valueRange = 1f..6f, steps = 4)
    }
}

@Composable
private fun OnlineProviderSettingItem() {
    val context = LocalContext.current
    val store = remember { (context.applicationContext as EReaderApplication).onlineProviderConfigStore }
    var config by remember { mutableStateOf(store.read()) }
    PackageRow(
        icon = { Icon(Icons.Default.Translate, null) },
        title = "Online refinement",
        subtitle = if (config.endpoint.isBlank()) "Cấu hình endpoint qua /api/v1/online-provider" else "${config.model.ifBlank { "Chưa chọn model" }} · ${config.endpoint}",
        action = {
            Switch(config.enabled, {
                config = config.copy(enabled = it)
                store.save(config)
            }, enabled = config.endpoint.isNotBlank() && config.model.isNotBlank())
        }
    )
}

@Composable
private fun WebConsoleSettingItem() {
    val context = LocalContext.current
    var enabled by remember { mutableStateOf(LocalWebService.isEnabled(context)) }
    PackageRow(
        icon = { Icon(Icons.Default.CloudDownload, null) },
        title = "Local web console",
        subtitle = "http://127.0.0.1:1122/admin/",
        action = {
            Switch(enabled, {
                enabled = it
                val intent = Intent(context, LocalWebService::class.java)
                if (it) ContextCompat.startForegroundService(context, intent) else context.stopService(intent)
                LocalWebService.setEnabled(context, it)
            })
        }
    )
}

@Composable
private fun PackageRow(icon: @Composable () -> Unit, title: String, subtitle: String, onClick: (() -> Unit)? = null, action: (@Composable () -> Unit)? = null) {
    Row(
        Modifier.fillMaxWidth().then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.padding(end = 14.dp), contentAlignment = Alignment.Center) { icon() }
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (action != null) { Spacer(Modifier.width(8.dp)); action() }
    }
}

@Composable
fun SettingSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth()) {
        Text(title, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 6.dp))
        content()
        HorizontalDivider(Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
    }
}

@Composable
fun SettingItem(icon: @Composable () -> Unit, title: String, subtitle: String, onClick: () -> Unit = {}) =
    PackageRow(icon, title, subtitle, onClick)

private fun formatBytes(bytes: Long): String = String.format(Locale.US, "%.1f MiB", bytes / (1024.0 * 1024.0))
