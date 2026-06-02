package com.example.crawler

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

object ExtensionRepository {
    private val _extensions = MutableStateFlow<List<ExtensionData>>(emptyList())
    val extensions: StateFlow<List<ExtensionData>> = _extensions.asStateFlow()

    fun addExtension(ext: ExtensionData) {
        val current = _extensions.value.toMutableList()
        current.removeAll { it.id == ext.id }
        current.add(ext)
        _extensions.value = current
    }

    fun removeExtension(extId: String) {
        _extensions.value = _extensions.value.filter { it.id != extId }
    }
}
