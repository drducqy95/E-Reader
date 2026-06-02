package com.example.crawler

import android.annotation.SuppressLint
import android.content.Context
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebSettings
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import com.drduc.legado.WebViewRuleEvaluator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONArray
import org.json.JSONObject
import kotlin.coroutines.resume

class WebViewPool(
    context: Context,
    private val sessions: VbookSessionStore,
    maxConcurrent: Int = 2
) : WebViewRuleEvaluator {
    private val appContext = context.applicationContext
    private val semaphore = Semaphore(maxConcurrent)
    private val maxIdle = maxConcurrent
    private val idle = ArrayDeque<WebView>()
    private val originLocks = mutableMapOf<String, Mutex>()
    private val verifiedPages = LinkedHashMap<String, VerifiedPage>()
    private val preferredWebViews = mutableMapOf<String, WebView>()
    private val cookieBridge = WebViewCookieBridge(sessions)

    fun adoptVerified(webView: WebView): Boolean {
        if (idle.any { it === webView }) return true
        if (idle.size >= maxIdle) destroyWebView(idle.removeLast())
        sessions.putUserAgent(webView.settings.userAgentString)
        captureVerifiedPage(webView)
        webView.stopLoading()
        webView.onPause()
        webView.webChromeClient = null
        preferredWebViews.entries.removeAll { it.value === webView }
        webView.url?.takeIf(String::isNotBlank)?.let { preferredWebViews[originKey(it)] = webView }
        idle.addFirst(webView)
        return true
    }

    fun clearSite(url: String) {
        cookieBridge.clearSite(url)
        val host = Uri.parse(url).host.orEmpty()
        synchronized(verifiedPages) {
            verifiedPages.keys.removeAll { isSameSite(Uri.parse(it).host.orEmpty(), host) }
        }
        val removed = preferredWebViews.values
            .filter { isSameSite(Uri.parse(it.url.orEmpty()).host.orEmpty(), host) }
            .distinct()
        preferredWebViews.entries.removeAll { it.value in removed }
        removed.filter(idle::remove).forEach { destroyWebView(it, removePreferred = false) }
    }

    fun captureVerifiedPage(webView: WebView) {
        val url = webView.url.orEmpty()
        if (url.isBlank() || webView.title == CLOUDFLARE_CHALLENGE_TITLE) return
        webView.evaluateJavascript("document.documentElement.outerHTML") {
            rememberVerifiedPage(url, decodeJavascriptResult(it))
        }
    }

    override suspend fun evaluate(url: String, html: String, script: String): String {
        if (html.isBlank() && isOuterHtmlScript(script)) {
            verifiedPage(url)?.let { return it }
        }
        return try {
            originLock(url).withLock {
                semaphore.withPermit {
                    withTimeout(RENDER_TIMEOUT_MS) {
                        withContext(Dispatchers.Main.immediate) {
                            val webView = acquireWebView(url)
                            try {
                                webView.onResume()
                                render(webView, url, html, script)
                            } finally {
                                webView.stopLoading()
                                webView.onPause()
                                idle.addLast(webView)
                            }
                        }
                    }
                }
            }
        } catch (error: TimeoutCancellationException) {
            throw BrowserVerificationRequiredException(url, error)
        }
    }

    private fun acquireWebView(url: String): WebView {
        val preferred = preferredWebViews[originKey(url)]
        if (preferred != null &&
            (canReuseLoadedPage(preferred, url) || sameOrigin(preferred.url.orEmpty(), url)) &&
            idle.remove(preferred)
        ) return preferred
        val matching = idle.firstOrNull { canReuseLoadedPage(it, url) }
            ?: idle.firstOrNull { sameOrigin(it.url.orEmpty(), url) && it.title != CLOUDFLARE_CHALLENGE_TITLE }
        if (matching != null) {
            idle.remove(matching)
            return matching
        }
        return idle.removeFirstOrNull() ?: createWebView()
    }

    private fun originLock(url: String): Mutex = synchronized(originLocks) {
        if (originLocks.size >= MAX_ORIGIN_LOCKS) {
            originLocks.entries.removeAll { !it.value.isLocked }
        }
        originLocks.getOrPut(originKey(url)) { Mutex() }
    }

    private fun originKey(url: String): String = runCatching {
        val uri = Uri.parse(url)
        "${uri.scheme.orEmpty().lowercase()}://${uri.host.orEmpty().lowercase()}:${uri.port}"
    }.getOrDefault(url)

    private fun destroyWebView(webView: WebView, removePreferred: Boolean = true) {
        if (removePreferred) preferredWebViews.entries.removeAll { it.value === webView }
        webView.removeAllViews()
        webView.destroy()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView(): WebView = WebView(appContext).apply {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        sessions.putUserAgent(settings.userAgentString)
        CookieManager.getInstance().setAcceptThirdPartyCookies(this, true)
    }

    private suspend fun render(webView: WebView, url: String, html: String, script: String): String =
        suspendCancellableCoroutine { continuation ->
            cookieBridge.restore(url)
            var completed = false
            var pageGeneration = 0
            fun finish(value: String) {
                if (completed || !continuation.isActive) return
                completed = true
                cookieBridge.capture(url)
                webView.url?.let(cookieBridge::capture)
                if (isOuterHtmlScript(script)) rememberVerifiedPage(url, value)
                continuation.resume(value)
            }
            val client = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false

                override fun onPageFinished(view: WebView, loadedUrl: String) {
                    cookieBridge.capture(loadedUrl)
                    val generation = ++pageGeneration
                    evaluateWhenSettled(view, generation, 0)
                }

                override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                    if (request.isForMainFrame) finish("")
                }

                private fun evaluateWhenSettled(view: WebView, generation: Int, attempt: Int) {
                    val delayMs = if (attempt == 0) PAGE_SETTLE_MS else CHALLENGE_RETRY_MS
                    view.postDelayed({
                        if (completed || !continuation.isActive || generation != pageGeneration) return@postDelayed
                        view.evaluateJavascript("document.title") { encodedTitle ->
                            if (completed || !continuation.isActive || generation != pageGeneration) return@evaluateJavascript
                            val title = decodeJavascriptResult(encodedTitle)
                            if (title == CLOUDFLARE_CHALLENGE_TITLE && attempt < MAX_CHALLENGE_RETRIES) {
                                evaluateWhenSettled(view, generation, attempt + 1)
                            } else {
                                val expression = script.ifBlank { "document.documentElement.outerHTML" }
                                view.evaluateJavascript(expression) { finish(decodeJavascriptResult(it)) }
                            }
                        }
                    }, delayMs)
                }
            }
            webView.webViewClient = client
            continuation.invokeOnCancellation { webView.post(webView::stopLoading) }
            val loadedUrl = webView.url.orEmpty()
            if (html.isNotBlank()) {
                webView.loadDataWithBaseURL(url, html, "text/html", Charsets.UTF_8.name(), url)
            } else if (canReuseLoadedPage(webView, url)) {
                client.onPageFinished(webView, loadedUrl)
            } else if (sameOrigin(loadedUrl, url)) {
                webView.evaluateJavascript("window.location.href = ${JSONObject.quote(url)}", null)
            } else {
                webView.loadUrl(url)
            }
        }

    private fun canReuseLoadedPage(webView: WebView, requestedUrl: String): Boolean =
        webView.url == requestedUrl && webView.title != CLOUDFLARE_CHALLENGE_TITLE

    private fun sameOrigin(currentUrl: String, targetUrl: String): Boolean {
        if (currentUrl.isBlank()) return false
        return runCatching {
            val current = Uri.parse(currentUrl)
            val target = Uri.parse(targetUrl)
            current.scheme.equals(target.scheme, ignoreCase = true) &&
                current.host.equals(target.host, ignoreCase = true) &&
                current.port == target.port
        }.getOrDefault(false)
    }

    private fun isSameSite(firstHost: String, secondHost: String): Boolean =
        firstHost.isNotBlank() && secondHost.isNotBlank() &&
            (firstHost == secondHost || firstHost.endsWith(".$secondHost") || secondHost.endsWith(".$firstHost"))

    private fun decodeJavascriptResult(value: String?): String {
        if (value == null || value == "null") return ""
        return runCatching { JSONArray("[$value]").getString(0) }.getOrDefault(value)
    }

    private fun rememberVerifiedPage(url: String, html: String) {
        if (url.isBlank() || html.isBlank() || isChallengeHtml(html)) return
        synchronized(verifiedPages) {
            verifiedPages[url] = VerifiedPage(html.take(MAX_VERIFIED_PAGE_CHARS), System.currentTimeMillis())
            while (verifiedPages.size > MAX_VERIFIED_PAGES) {
                verifiedPages.remove(verifiedPages.keys.first())
            }
        }
    }

    private fun verifiedPage(url: String): String? = synchronized(verifiedPages) {
        val page = verifiedPages[url] ?: return@synchronized null
        if (System.currentTimeMillis() - page.capturedAt > VERIFIED_PAGE_TTL_MS) {
            verifiedPages.remove(url)
            null
        } else {
            page.html
        }
    }

    private fun isOuterHtmlScript(script: String): Boolean =
        script.isBlank() || script == "document.documentElement.outerHTML"

    private fun isChallengeHtml(html: String): Boolean =
        html.contains("challenges.cloudflare.com", ignoreCase = true) ||
            html.contains("cf-chl-", ignoreCase = true)

    private data class VerifiedPage(val html: String, val capturedAt: Long)

    companion object {
        private const val RENDER_TIMEOUT_MS = 30_000L
        private const val PAGE_SETTLE_MS = 350L
        private const val CHALLENGE_RETRY_MS = 1_500L
        private const val MAX_CHALLENGE_RETRIES = 10
        private const val MAX_VERIFIED_PAGES = 12
        private const val MAX_VERIFIED_PAGE_CHARS = 2_000_000
        private const val VERIFIED_PAGE_TTL_MS = 15 * 60_000L
        private const val MAX_ORIGIN_LOCKS = 64
        private const val CLOUDFLARE_CHALLENGE_TITLE = "Just a moment..."
    }
}

class BrowserVerificationRequiredException(
    val url: String,
    cause: Throwable? = null
) : IllegalStateException("Source browser verification or login required: $url", cause)
