package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val dataStore: DataStore<Preferences>) {
    private val THEME_MODE_KEY = intPreferencesKey("theme_mode") // 0: Auto, 1: Light, 2: Dark
    private val FONT_SIZE_KEY = floatPreferencesKey("font_size")
    private val BG_COLOR_KEY = intPreferencesKey("bg_color")
    private val TTS_ENABLED_KEY = booleanPreferencesKey("tts_enabled")
    private val READER_LAYOUT_KEY = stringPreferencesKey("reader_layout")
    private val MS_REFINEMENT_KEY = booleanPreferencesKey("ms_online_refinement")
    private val LINE_SPACING_KEY = floatPreferencesKey("line_spacing")
    private val SIMPLIFIED_CHINESE_KEY = booleanPreferencesKey("simplified_chinese")
    private val ITALIC_DIALOGUE_KEY = booleanPreferencesKey("italic_dialogue")
    private val DOWNLOAD_CONCURRENCY_KEY = intPreferencesKey("download_concurrency")
    private val DOWNLOAD_RETRIES_KEY = intPreferencesKey("download_retries")

    val themeMode: Flow<Int> = dataStore.data.map { it[THEME_MODE_KEY] ?: 0 }
    val fontSize: Flow<Float> = dataStore.data.map { it[FONT_SIZE_KEY] ?: 18f }
    val bgColorIndex: Flow<Int> = dataStore.data.map { it[BG_COLOR_KEY] ?: 0 }
    val ttsEnabled: Flow<Boolean> = dataStore.data.map { it[TTS_ENABLED_KEY] ?: false }
    val readerLayout: Flow<String> = dataStore.data.map { it[READER_LAYOUT_KEY] ?: "PAGED" }
    val msOnlineRefinement: Flow<Boolean> = dataStore.data.map { it[MS_REFINEMENT_KEY] ?: true }
    val lineSpacing: Flow<Float> = dataStore.data.map { it[LINE_SPACING_KEY] ?: 1.35f }
    val simplifiedChinese: Flow<Boolean> = dataStore.data.map { it[SIMPLIFIED_CHINESE_KEY] ?: true }
    val italicDialogue: Flow<Boolean> = dataStore.data.map { it[ITALIC_DIALOGUE_KEY] ?: true }
    val downloadConcurrency: Flow<Int> = dataStore.data.map { it[DOWNLOAD_CONCURRENCY_KEY] ?: 2 }
    val downloadRetries: Flow<Int> = dataStore.data.map { it[DOWNLOAD_RETRIES_KEY] ?: 3 }

    suspend fun setThemeMode(mode: Int) {
        dataStore.edit { it[THEME_MODE_KEY] = mode }
    }

    suspend fun setFontSize(size: Float) {
        dataStore.edit { it[FONT_SIZE_KEY] = size }
    }

    suspend fun setBgColorIndex(index: Int) {
        dataStore.edit { it[BG_COLOR_KEY] = index }
    }
    
    suspend fun setTtsEnabled(enabled: Boolean) {
        dataStore.edit { it[TTS_ENABLED_KEY] = enabled }
    }

    suspend fun setReaderLayout(layout: String) { dataStore.edit { it[READER_LAYOUT_KEY] = layout } }
    suspend fun setMsOnlineRefinement(enabled: Boolean) { dataStore.edit { it[MS_REFINEMENT_KEY] = enabled } }
    suspend fun setLineSpacing(value: Float) { dataStore.edit { it[LINE_SPACING_KEY] = value } }
    suspend fun setSimplifiedChinese(enabled: Boolean) { dataStore.edit { it[SIMPLIFIED_CHINESE_KEY] = enabled } }
    suspend fun setItalicDialogue(enabled: Boolean) { dataStore.edit { it[ITALIC_DIALOGUE_KEY] = enabled } }
    suspend fun setDownloadConcurrency(value: Int) { dataStore.edit { it[DOWNLOAD_CONCURRENCY_KEY] = value.coerceIn(1, 6) } }
    suspend fun setDownloadRetries(value: Int) { dataStore.edit { it[DOWNLOAD_RETRIES_KEY] = value.coerceIn(1, 6) } }
}
