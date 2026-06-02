package com.drduc.legado

import okhttp3.FormBody
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.Headers
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.io.IOException
import java.nio.charset.Charset
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

data class AnalyzeResponse(
    val body: String,
    val finalUrl: String,
    val revision: String,
    val headers: Headers
)

class AnalyzeUrl(
    private val client: OkHttpClient = defaultClient,
    private val debugSink: DebugSink? = null,
    private val cookieStore: AnalyzeCookieStore? = null,
    private val webViewEvaluator: WebViewRuleEvaluator? = null,
    private val userAgent: String? = null
) {
    private val requestClient = cookieStore?.let { client.newBuilder().cookieJar(StoreCookieJar(it)).build() } ?: client

    suspend fun fetch(template: String, variables: Map<String, String> = emptyMap()): AnalyzeResponse {
        val resolved = replaceVariables(template, variables)
        val (url, options) = splitOptions(resolved)
        val request = createRequest(url, options)
        debugSink?.emit(DebugStep("request", request.method, request.url.toString()))
        return try {
            requestClient.newCall(request).execute().use { response ->
                cookieStore?.capture(response.request.url.toString(), response.headers.values("Set-Cookie"))
                if (!response.isSuccessful) {
                    val rendered = renderBlockedGet(request, response.code)
                    if (rendered != null) return rendered
                    error("HTTP ${response.code}: ${response.message}")
                }
                val charset = options.optString("charset").takeIf(String::isNotBlank)?.let(Charset::forName) ?: Charsets.UTF_8
                val bytes = response.body?.bytes() ?: ByteArray(0)
                val body = bytes.toString(charset)
                val revision = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
                debugSink?.emit(DebugStep("response", response.request.url.toString(), body.take(240)))
                AnalyzeResponse(body, response.request.url.toString(), revision, response.headers)
            }
        } catch (error: IOException) {
            renderBlockedGet(request, 0) ?: throw error
        }
    }

    private fun replaceVariables(template: String, variables: Map<String, String>): String {
        var value = template
        variables.forEach { (key, raw) ->
            value = value.replace("{{$key}}", URLEncoder.encode(raw, Charsets.UTF_8.name()))
            value = value.replace("{{${key}.raw}}", raw)
        }
        return value
    }

    private fun splitOptions(value: String): Pair<String, JSONObject> {
        val index = value.indexOf(",{")
        return if (index < 0) value to JSONObject() else {
            value.substring(0, index) to JSONObject(value.substring(index + 1))
        }
    }

    private fun createRequest(url: String, options: JSONObject): Request {
        val builder = Request.Builder().url(url)
        userAgent?.takeIf(String::isNotBlank)?.let { builder.header("User-Agent", it) }
        cookieStore?.cookieHeader(url)?.let { builder.header("Cookie", it) }
        options.optJSONObject("headers")?.let { headers ->
            headers.keys().forEach { name -> builder.header(name, headers.optString(name)) }
        }
        return when (options.optString("method", "GET").uppercase()) {
            "POST" -> {
                val body = options.opt("body")
                val requestBody = when (body) {
                    is JSONObject -> {
                        val form = FormBody.Builder()
                        body.keys().forEach { key -> form.add(key, body.optString(key)) }
                        form.build()
                    }
                    null -> ByteArray(0).toRequestBody()
                    else -> body.toString().toRequestBody("application/json; charset=utf-8".toMediaType())
                }
                builder.post(requestBody).build()
            }
            else -> builder.get().build()
        }
    }

    private suspend fun renderBlockedGet(request: Request, status: Int): AnalyzeResponse? {
        val evaluator = webViewEvaluator ?: return null
        if (request.method != "GET" || status !in WEBVIEW_FALLBACK_STATUS) return null
        val url = request.url.toString()
        debugSink?.emit(DebugStep("webViewFallback", "HTTP $status", url))
        val body = evaluator.evaluate(url, "", "document.documentElement.outerHTML")
        if (body.isBlank()) return null
        val bytes = body.toByteArray()
        val revision = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        return AnalyzeResponse(body, url, revision, Headers.Builder().build())
    }

    companion object {
        private val defaultClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(20, TimeUnit.SECONDS)
            .followRedirects(true)
            .followSslRedirects(true)
            .build()
        private val WEBVIEW_FALLBACK_STATUS = setOf(0, 401, 403, 429, 503)
    }

    private class StoreCookieJar(private val store: AnalyzeCookieStore) : CookieJar {
        override fun loadForRequest(url: okhttp3.HttpUrl): List<Cookie> =
            store.cookieHeader(url.toString()).orEmpty().split(';').mapNotNull { raw ->
                val pair = raw.trim()
                if ('=' !in pair) null else Cookie.Builder()
                    .name(pair.substringBefore('=').trim())
                    .value(pair.substringAfter('=').trim())
                    .hostOnlyDomain(url.host)
                    .path("/")
                    .build()
            }

        override fun saveFromResponse(url: okhttp3.HttpUrl, cookies: List<Cookie>) {
            store.capture(url.toString(), cookies.map(Cookie::toString))
        }
    }
}
