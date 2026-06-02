package com.example.data

import com.example.BuildConfig

data class ProductionExpansionConfig(
    val url: String,
    val sha256: String
) {
    val configured: Boolean
        get() = url.isNotBlank() && sha256.matches(Regex("[0-9a-fA-F]{64}"))

    companion object {
        fun fromBuild(): ProductionExpansionConfig = ProductionExpansionConfig(
            url = BuildConfig.PRODUCTION_GRAPH_URL,
            sha256 = BuildConfig.PRODUCTION_GRAPH_SHA256
        )
    }
}
