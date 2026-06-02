package com.example.ui

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
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
import com.example.EReaderApplication
import com.example.crawler.ExtensionParser
import com.example.crawler.ExtensionRepository
import com.example.crawler.openSourceBrowser
import com.example.data.UiTextFieldType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourceManagementScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val app = context.applicationContext as EReaderApplication
    val extensions by ExtensionRepository.extensions.collectAsState()
    val legadoSources by app.legadoSourceRepository.sources.collectAsState()
    val scope = rememberCoroutineScope()
    var showDialog by remember { mutableStateOf(false) }
    var url by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }

    fun installLocal(uri: Uri) {
        scope.launch {
            loading = true
            error = ""
            notice = ""
            runCatching {
                withContext(Dispatchers.IO) {
                    context.contentResolver.openInputStream(uri)?.use(ExtensionParser::parseZip)
                        ?: error("Could not open ZIP file.")
                }
            }.onSuccess(ExtensionRepository::addExtension)
                .onFailure { error = it.message ?: "Could not install VBook extension." }
            loading = false
        }
    }

    fun installVbookUrl() {
        scope.launch {
            loading = true
            error = ""
            notice = ""
            runCatching {
                withContext(Dispatchers.IO) { ExtensionParser.inspectUrlOrRepo(url) }
            }.onSuccess { report ->
                if (report.extensions.isEmpty()) {
                    error = report.issues.joinToString("\n") { it.message }.ifBlank { "Không thể cài nguồn VBook." }
                } else {
                    report.extensions.forEach(ExtensionRepository::addExtension)
                    if (report.issues.isEmpty()) {
                        showDialog = false
                    } else {
                        notice = "Đã cài ${report.extensions.size} nguồn. Bỏ qua ${report.issues.size} nguồn chưa tương thích."
                    }
                }
            }.onFailure {
                error = it.message ?: "Could not install VBook URL."
            }
            loading = false
        }
    }

    fun installLegadoUrl() {
        scope.launch {
            loading = true
            error = ""
            notice = ""
            runCatching {
                withContext(Dispatchers.IO) { app.legadoSourceRepository.installFromUrl(url) }
            }.onSuccess {
                showDialog = false
            }.onFailure {
                error = it.message ?: "Could not install Legado JSON source."
            }
            loading = false
        }
    }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let(::installLocal)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Nguon online") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Quay lai")
                    }
                },
                actions = {
                    BrowserButton(context, "", "")
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Cai nguon")
            }
        }
    ) { padding ->
        if (extensions.isEmpty() && legadoSources.isEmpty()) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                Text("Chua co nguon VBook hoac Legado JSON nao duoc cai.")
            }
        } else {
            LazyColumn(Modifier.fillMaxSize().padding(padding)) {
                items(extensions, key = { "vbook:${it.id}" }) { extension ->
                    ListItem(
                        headlineContent = { Text(rememberTranslatedUiText(extension.name, UiTextFieldType.SOURCE_METADATA)) },
                        supportingContent = {
                            Text(
                                "${rememberTranslatedUiText(extension.author, UiTextFieldType.AUTHOR)} - v${extension.version}\n" +
                                    rememberTranslatedUiText(extension.description, UiTextFieldType.DESCRIPTION)
                            )
                        },
                        leadingContent = { Icon(Icons.Default.Language, contentDescription = null) },
                        trailingContent = {
                            Row {
                                BrowserButton(context, extension.id, extension.source)
                                IconButton(onClick = { ExtensionRepository.removeExtension(extension.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Xoa", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    )
                    HorizontalDivider()
                }
                items(legadoSources, key = { "legado:${it.id}" }) { source ->
                    ListItem(
                        headlineContent = { Text(rememberTranslatedUiText(source.name, UiTextFieldType.SOURCE_METADATA)) },
                        supportingContent = { Text("Legado JSON\n${source.sourceUrl}") },
                        leadingContent = { Icon(Icons.Default.Language, contentDescription = null) },
                        trailingContent = {
                            Row {
                                BrowserButton(context, source.id, source.sourceUrl)
                                IconButton(onClick = { app.legadoSourceRepository.remove(source.id) }) {
                                    Icon(Icons.Default.Delete, contentDescription = "Xoa", tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    )
                    HorizontalDivider()
                }
            }
        }

        if (showDialog) {
            AlertDialog(
                onDismissRequest = { if (!loading) showDialog = false },
                title = { Text("Cai nguon online") },
                text = {
                    Column {
                        Text("Chon plugin.zip VBook hoac nhap URL HTTPS.")
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream")) }, enabled = !loading) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null)
                            Text(" Chon ZIP VBook")
                        }
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text("https://.../plugin.zip hoac source.json") }
                        )
                        Spacer(Modifier.height(8.dp))
                        Row {
                            Button(onClick = ::installVbookUrl, enabled = !loading && url.isNotBlank()) {
                                Text("Cai VBook URL")
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = ::installLegadoUrl, enabled = !loading && url.isNotBlank()) {
                                Text("Cai Legado JSON")
                            }
                        }
                        if (loading) {
                            Spacer(Modifier.height(12.dp))
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        if (error.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(error, color = MaterialTheme.colorScheme.error)
                        }
                        if (notice.isNotBlank()) {
                            Spacer(Modifier.height(8.dp))
                            Text(notice, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                confirmButton = {},
                dismissButton = {
                    TextButton(onClick = { showDialog = false }, enabled = !loading) {
                        Text("Dong")
                    }
                }
            )
        }
    }
}

@Composable
private fun BrowserButton(context: android.content.Context, sourceId: String, url: String) {
    IconButton(onClick = {
        openSourceBrowser(context, sourceId, url)
    }) {
        Icon(Icons.Default.OpenInBrowser, contentDescription = "Mở trình duyệt nguồn")
    }
}
