package com.example.crawler

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.EReaderApplication
import com.example.ui.theme.MyApplicationTheme

fun openSourceBrowser(context: Context, sourceId: String, url: String) {
    context.startActivity(
        Intent(context, WebViewLoginActivity::class.java)
            .putExtra(WebViewLoginActivity.EXTRA_SOURCE_ID, sourceId)
            .putExtra(WebViewLoginActivity.EXTRA_URL, url)
    )
}

class WebViewLoginActivity : ComponentActivity() {
    private lateinit var sessions: VbookSessionStore
    private lateinit var cookieBridge: WebViewCookieBridge
    private var browser: WebView? = null
    private val homeUrl by lazy { normalizeAddress(intent.getStringExtra(EXTRA_URL).orEmpty()) }
    private val sourceId by lazy { intent.getStringExtra(EXTRA_SOURCE_ID).orEmpty() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sessions = VbookSessionStore(applicationContext)
        cookieBridge = WebViewCookieBridge(sessions)
        setContent {
            MyApplicationTheme {
                BrowserScreen()
            }
        }
    }

    override fun onPause() {
        browser?.url?.let(cookieBridge::capture)
        super.onPause()
    }

    override fun onDestroy() {
        browser?.let {
            it.stopLoading()
            (it.parent as? ViewGroup)?.removeView(it)
            val adopted = (application as? EReaderApplication)?.webViewPool?.adoptVerified(it) == true
            if (!adopted) {
                it.removeAllViews()
                it.destroy()
            }
        }
        browser = null
        super.onDestroy()
    }

    @OptIn(ExperimentalMaterial3Api::class)
    @SuppressLint("SetJavaScriptEnabled")
    @Composable
    private fun BrowserScreen() {
        var address by rememberSaveable { mutableStateOf(homeUrl) }
        var currentUrl by rememberSaveable { mutableStateOf(homeUrl) }
        var title by rememberSaveable { mutableStateOf("") }
        var status by rememberSaveable { mutableStateOf("Cookie của site sẽ được lưu cho crawler nền.") }
        var loading by remember { mutableStateOf(false) }
        var progress by remember { mutableIntStateOf(0) }
        var canGoBack by remember { mutableStateOf(false) }
        var canGoForward by remember { mutableStateOf(false) }
        val focusManager = LocalFocusManager.current

        fun updateNavigation(view: WebView) {
            canGoBack = view.canGoBack()
            canGoForward = view.canGoForward()
        }

        fun navigate(view: WebView, rawUrl: String) {
            val url = normalizeAddress(rawUrl)
            if (url.isBlank()) return
            address = url
            currentUrl = url
            cookieBridge.restore(url)
            view.loadUrl(url)
            focusManager.clearFocus()
        }

        BackHandler {
            val view = browser
            if (view != null && view.canGoBack()) view.goBack() else finish()
        }

        Scaffold(
            topBar = {
                Column {
                    TopAppBar(
                        title = {
                            Column {
                                Text(title.ifBlank { "Trình duyệt nguồn" }, maxLines = 1)
                                if (sourceId.isNotBlank()) {
                                    Text(
                                        sourceId,
                                        style = MaterialTheme.typography.labelSmall,
                                        maxLines = 1
                                    )
                                }
                            }
                        },
                        actions = {
                            IconButton(onClick = { finish() }) {
                                Icon(Icons.Default.Close, contentDescription = "Đóng trình duyệt")
                            }
                        }
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = address,
                            onValueChange = { address = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            leadingIcon = {
                                Icon(
                                    if (address.startsWith("https://", ignoreCase = true)) Icons.Default.Lock
                                    else Icons.Default.Warning,
                                    contentDescription = if (address.startsWith("https://", ignoreCase = true)) {
                                        "Kết nối HTTPS"
                                    } else {
                                        "Kết nối không mã hóa"
                                    }
                                )
                            },
                            trailingIcon = {
                                IconButton(onClick = { browser?.let { navigate(it, address) } }) {
                                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Mở địa chỉ")
                                }
                            },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                            keyboardActions = KeyboardActions(onGo = {
                                browser?.let { navigate(it, address) }
                            })
                        )
                    }
                    if (loading) {
                        LinearProgressIndicator(
                            progress = { progress / 100f },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            },
            bottomBar = {
                Column {
                    Text(
                        status,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium,
                        maxLines = 2
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = { browser?.goBack() },
                            enabled = canGoBack
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Trang trước")
                        }
                        IconButton(
                            onClick = { browser?.goForward() },
                            enabled = canGoForward
                        ) {
                            Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Trang sau")
                        }
                        IconButton(
                            onClick = { browser?.let { navigate(it, homeUrl) } },
                            enabled = homeUrl.isNotBlank()
                        ) {
                            Icon(Icons.Default.Home, contentDescription = "Trang nguồn")
                        }
                        IconButton(onClick = {
                            browser?.let { view ->
                                if (loading) view.stopLoading() else view.reload()
                            }
                        }) {
                            Icon(
                                if (loading) Icons.Default.Stop else Icons.Default.Refresh,
                                contentDescription = if (loading) "Dừng tải trang" else "Tải lại"
                            )
                        }
                        IconButton(onClick = {
                            val url = browser?.url ?: currentUrl
                            val pool = (application as? EReaderApplication)?.webViewPool
                            if (pool != null) pool.clearSite(url) else cookieBridge.clearSite(url)
                            status = "Đã xóa cookie của site hiện tại."
                            browser?.reload()
                        }) {
                            Icon(Icons.Default.DeleteSweep, contentDescription = "Xóa cookie site")
                        }
                    }
                }
            }
        ) { padding ->
            AndroidView(
                modifier = Modifier.fillMaxSize().padding(padding),
                factory = { context ->
                    WebView(context).apply {
                        browser = this
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.allowFileAccess = false
                        settings.allowContentAccess = false
                        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                        sessions.putUserAgent(settings.userAgentString)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            settings.safeBrowsingEnabled = true
                        }
                        val browserView = this
                        CookieManager.getInstance().apply {
                            setAcceptCookie(true)
                            setAcceptThirdPartyCookies(browserView, true)
                        }
                        webViewClient = object : WebViewClient() {
                            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean = false

                            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                                loading = true
                                address = url
                                currentUrl = url
                                updateNavigation(view)
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                cookieBridge.capture(url)
                                (application as? EReaderApplication)?.webViewPool?.captureVerifiedPage(view)
                                loading = false
                                address = url
                                currentUrl = url
                                status = if (sessions.hasCookies(url)) {
                                    "Cookie site đã được lưu. Crawler nền sẽ dùng cùng phiên đăng nhập."
                                } else {
                                    "Chưa có cookie site. Bạn có thể đăng nhập hoặc duyệt trang bình thường."
                                }
                                updateNavigation(view)
                            }

                            override fun onReceivedError(view: WebView, request: WebResourceRequest, error: WebResourceError) {
                                if (request.isForMainFrame) {
                                    loading = false
                                    status = "Không tải được trang: ${error.description}"
                                }
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onProgressChanged(view: WebView, newProgress: Int) {
                                progress = newProgress
                                loading = newProgress < 100
                            }

                            override fun onReceivedTitle(view: WebView, pageTitle: String?) {
                                title = pageTitle.orEmpty()
                            }
                        }
                        if (homeUrl.isNotBlank()) navigate(this, homeUrl)
                    }
                }
            )
        }
    }

    companion object {
        const val EXTRA_SOURCE_ID = "sourceId"
        const val EXTRA_URL = "url"

        internal fun normalizeAddress(raw: String): String {
            val value = raw.trim()
            if (value.isBlank()) return ""
            return if ("://" in value || value.startsWith("about:")) value else "https://$value"
        }
    }
}
