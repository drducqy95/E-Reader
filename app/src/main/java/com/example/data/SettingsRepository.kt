package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

class SettingsRepository(private val dataStore: DataStore<Preferences>) {
    private val THEME_MODE_KEY = intPreferencesKey("theme_mode") // 0: Auto, 1: Light, 2: Dark
    private val FONT_SIZE_KEY = floatPreferencesKey("font_size")
    private val BG_COLOR_KEY = intPreferencesKey("bg_color")
    private val TTS_ENABLED_KEY = booleanPreferencesKey("tts_enabled")

    val themeMode: Flow<Int> = dataStore.data.map { it[THEME_MODE_KEY] ?: 0 }
    val fontSize: Flow<Float> = dataStore.data.map { it[FONT_SIZE_KEY] ?: 18f }
    val bgColorIndex: Flow<Int> = dataStore.data.map { it[BG_COLOR_KEY] ?: 0 }
    val ttsEnabled: Flow<Boolean> = dataStore.data.map { it[TTS_ENABLED_KEY] ?: false }

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
}
