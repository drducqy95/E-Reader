package com.example.data

import android.content.Context
import com.drduc.engine.OnlineRefinementProvider
import com.drduc.engine.TranslationResult
import com.example.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import org.json.JSONArray
import org.json.JSONObject

data class OnlineProviderConfig(
    val enabled: Boolean,
    val endpoint: String,
    val model: String,
    val apiToken: String
)

class OnlineProviderConfigStore(context: Context) {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    fun read(): OnlineProviderConfig = OnlineProviderConfig(
        enabled = preferences.getBoolean(KEY_ENABLED, false),
        endpoint = preferences.getString(KEY_ENDPOINT, BuildConfig.PRODUCTION_ONLINE_URL).orEmpty(),
        model = preferences.getString(KEY_MODEL, BuildConfig.PRODUCTION_ONLINE_MODEL).orEmpty(),
        apiToken = preferences.getString(KEY_TOKEN, "").orEmpty()
    )

    fun save(config: OnlineProviderConfig) {
        preferences.edit()
            .putBoolean(KEY_ENABLED, config.enabled)
            .putString(KEY_ENDPOINT, config.endpoint.trim())
            .putString(KEY_MODEL, config.model.trim())
            .putString(KEY_TOKEN, config.apiToken.trim())
            .apply()
    }

    companion object {
        private const val PREFERENCES_NAME = "online_provider"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_ENDPOINT = "endpoint"
        private const val KEY_MODEL = "model"
        private const val KEY_TOKEN = "apiToken"
    }
}

class OpenAiCompatibleRefinementProvider(
    private val store: OnlineProviderConfigStore,
    private val client: OkHttpClient = OkHttpClient()
) : OnlineRefinementProvider {
    override val enabled: Boolean get() = store.read().enabled
    override val id: String get() = "openai-compatible:${store.read().model}"

    override suspend fun refine(offline: TranslationResult): TranslationResult = withContext(Dispatchers.IO) {
        val config = store.read()
        require(config.enabled) { "Online refinement is disabled" }
        require(config.model.isNotBlank()) { "Online model is not configured" }
        val url = config.endpoint.toHttpUrl()
        require(url.isHttps || url.host in setOf("127.0.0.1", "localhost")) {
            "Online provider requires HTTPS, except for loopback development URLs"
        }
        val payload = JSONObject()
            .put("model", config.model)
            .put("temperature", 0)
            .put("messages", JSONArray()
                .put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
                .put(JSONObject().put("role", "user").put("content", offline.offlineText ?: offline.rawText)))
        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .apply { if (config.apiToken.isNotBlank()) header("Authorization", "Bearer ${config.apiToken}") }
            .build()
        client.newCall(request).execute().use { response ->
            check(response.isSuccessful) { "Online provider returned HTTP ${response.code}" }
            val body = JSONObject(response.body?.string().orEmpty())
            val refined = body.optJSONArray("choices")
                ?.optJSONObject(0)
                ?.optJSONObject("message")
                ?.optString("content")
                .orEmpty()
                .trim()
            check(refined.isNotBlank()) { "Online provider returned an empty translation" }
            offline.copy(refinedText = refined)
        }
    }

    companion object {
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val SYSTEM_PROMPT =
            "Polish the supplied offline Chinese-to-Vietnamese novel translation. Return only the refined Vietnamese text. Preserve paragraph breaks, names, numbers, HTML placeholders and meaning."
    }
}
