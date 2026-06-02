package com.example.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.platform.LocalContext
import com.example.EReaderApplication
import com.example.data.UiTextFieldType

@Composable
fun rememberTranslatedUiText(text: String, fieldType: UiTextFieldType): String {
    val app = LocalContext.current.applicationContext as EReaderApplication
    val translated by produceState(initialValue = text, key1 = text, key2 = fieldType) {
        value = app.uiTranslationRepository.translate(text, fieldType)
    }
    return translated
}
