package com.example.ui

import android.speech.tts.TextToSpeech
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.viewmodel.ReaderViewModel
import java.util.Locale
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(
    viewModel: ReaderViewModel,
    onBack: () -> Unit
) {
    val book by viewModel.book.collectAsStateWithLifecycle()
    val textContent by viewModel.textContent.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val fontSize by viewModel.fontSize.collectAsStateWithLifecycle()
    val ttsEnabled by viewModel.ttsEnabled.collectAsStateWithLifecycle()
    val bgColorIndex by viewModel.bgColorIndex.collectAsStateWithLifecycle()
    
    var showSettings by remember { mutableStateOf(false) }
    var showControls by remember { mutableStateOf(true) }
    
    val scrollState = rememberScrollState()
    
    // Auto-update progress based on scroll
    LaunchedEffect(scrollState.value, scrollState.maxValue) {
        if (scrollState.maxValue > 0) {
            val progress = scrollState.value.toFloat() / scrollState.maxValue
            viewModel.updateProgress(progress)
        }
    }
    
    // TTS Setup
    val context = LocalContext.current
    var tts: TextToSpeech? by remember { mutableStateOf(null) }
    
    DisposableEffect(context) {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.Builder().setLanguage("vi").setRegion("VN").build()
            }
        }
        onDispose {
            tts?.stop()
            tts?.shutdown()
        }
    }

    LaunchedEffect(ttsEnabled, textContent) {
        if (ttsEnabled && textContent.isNotEmpty()) {
            tts?.speak(textContent, TextToSpeech.QUEUE_FLUSH, null, "TTS_ID")
        } else {
            tts?.stop()
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            if (showControls) {
                TopAppBar(
                    title = { Text(book?.title ?: "Đang đọc", color = MaterialTheme.colorScheme.onBackground) },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                        titleContentColor = MaterialTheme.colorScheme.onBackground,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                        actionIconContentColor = MaterialTheme.colorScheme.onSurface
                    ),
                    navigationIcon = {
                        IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
                    },
                    actions = {
                        IconButton(onClick = { viewModel.setTtsEnabled(!ttsEnabled) }) {
                            Icon(
                                imageVector = if (ttsEnabled) Icons.AutoMirrored.Filled.VolumeUp else Icons.AutoMirrored.Filled.VolumeOff,
                                contentDescription = "TTS"
                            )
                        }
                        IconButton(onClick = { showSettings = !showSettings }) {
                            Icon(Icons.Default.Settings, contentDescription = "Settings")
                        }
                    }
                )
            }
        },
        bottomBar = {
            if (showControls) {
                BottomAppBar(
                    containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                    contentColor = MaterialTheme.colorScheme.onSurface
                ) {
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${(scrollState.value.toFloat() / (if(scrollState.maxValue == 0) 1 else scrollState.maxValue) * 100).toInt()}%",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.weight(1f))
                    }
                }
            }
        }
    ) { padding ->
        val backgroundColor = when (bgColorIndex) {
            1 -> Color(0xFFF4ECD8) // Sepia
            2 -> Color(0xFFE6EEE6) // Mint
            3 -> Color(0xFF1E1E1E) // Dark Gray
            else -> MaterialTheme.colorScheme.background // Default
        }
    
        Box(modifier = Modifier
            .fillMaxSize()
            .background(backgroundColor)
            .padding(padding)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) { showControls = !showControls }
        ) {
            if (isLoading) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                Text(
                    text = textContent,
                    fontSize = fontSize.sp,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                        .verticalScroll(scrollState),
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }
        
        if (showSettings) {
            ReaderSettingsBottomSheet(
                currentFontSize = fontSize,
                currentBgColorIndex = bgColorIndex,
                onFontSizeChange = { viewModel.setFontSize(it) },
                onBgColorChange = { viewModel.setBgColorIndex(it) },
                onDismiss = { showSettings = false }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderSettingsBottomSheet(
    currentFontSize: Float,
    currentBgColorIndex: Int,
    onFontSizeChange: (Float) -> Unit,
    onBgColorChange: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(modifier = Modifier.padding(16.dp).padding(bottom = 32.dp)) {
            Text("Cài đặt", style = MaterialTheme.typography.titleLarge)
            Spacer(modifier = Modifier.height(16.dp))
            Text("Cỡ chữ: ${currentFontSize.toInt()}sp")
            Slider(
                value = currentFontSize,
                onValueChange = onFontSizeChange,
                valueRange = 12f..36f
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text("Màu nền:")
            Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(
                    Pair(0, MaterialTheme.colorScheme.surfaceVariant), 
                    Pair(1, Color(0xFFF4ECD8)), 
                    Pair(2, Color(0xFFE6EEE6)), 
                    Pair(3, Color(0xFF1E1E1E))
                ).forEach { (index, color) ->
                    Box(modifier = Modifier
                        .size(48.dp)
                        .background(color, shape = MaterialTheme.shapes.small)
                        .clickable { onBgColorChange(index) }
                        .then(if (currentBgColorIndex == index) Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = 0.3f), shape = MaterialTheme.shapes.small) else Modifier)
                    )
                }
            }
        }
    }
}
