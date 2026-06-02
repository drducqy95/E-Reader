package com.example.crawler

import android.webkit.CookieManager

class WebViewCookieBridge(
    private val sessions: VbookSessionStore,
    private val cookieManager: CookieManager = CookieManager.getInstance()
) {
    fun restore(url: String) {
        if (url.isBlank()) return
        cookieManager.setAcceptCookie(true)
        sessions.restoreSetCookieHeaders(url).forEach { cookieManager.setCookie(url, it) }
        cookieManager.flush()
    }

    fun capture(url: String) {
        if (url.isBlank()) return
        cookieManager.getCookie(url)?.let { sessions.putCookieHeader(url, it) }
        cookieManager.flush()
    }

    fun clearSite(url: String) {
        if (url.isBlank()) return
        val cookies = sessions.cookiesForSite(url)
        cookies.forEach { cookie ->
            cookieManager.setCookie(url, "${cookie.name}=; Max-Age=0; Path=${cookie.path}")
            if (!cookie.hostOnly) {
                cookieManager.setCookie(url, "${cookie.name}=; Max-Age=0; Path=${cookie.path}; Domain=.${cookie.domain}")
            }
        }
        val knownNames = cookies.map { it.name }.toSet()
        cookieManager.getCookie(url).orEmpty().split(';').forEach { raw ->
            val name = raw.substringBefore('=').trim()
            if (name.isNotBlank() && name !in knownNames) cookieManager.setCookie(url, "$name=; Max-Age=0; Path=/")
        }
        sessions.clearSite(url)
        cookieManager.flush()
    }
}
