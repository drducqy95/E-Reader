package com.example.crawler

import android.content.Context
import android.net.Uri
import com.drduc.legado.AnalyzeCookieStore
import okhttp3.Cookie
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.json.JSONArray
import org.json.JSONObject

class VbookSessionStore(context: Context) : AnalyzeCookieStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)
    private val lock = Any()

    override fun cookieHeader(url: String): String? =
        cookiesFor(url).takeIf(List<StoredCookie>::isNotEmpty)
            ?.joinToString("; ") { "${it.name}=${it.value}" }

    override fun capture(url: String, setCookieHeaders: List<String>) {
        val httpUrl = url.toHttpUrlOrNull() ?: return
        synchronized(lock) {
            val stored = readCookies().toMutableList()
            setCookieHeaders.forEach { raw ->
                Cookie.parse(httpUrl, raw)
                    ?.takeIf { it.hostOnly || isRegistrableDomain(it.domain) }
                    ?.let { replaceCookie(stored, StoredCookie.from(it)) }
            }
            writeCookies(stored)
        }
    }

    fun capture(url: String, cookies: Map<String, String>) {
        if (cookies.isEmpty()) return
        val httpUrl = url.toHttpUrlOrNull() ?: return
        synchronized(lock) {
            val stored = readCookies().toMutableList()
            cookies.forEach { (name, value) ->
                val matching = stored.filter { it.name == name && it.matches(httpUrl, includeExpired = true) }
                if (matching.isEmpty()) {
                    replaceCookie(stored, StoredCookie.hostOnly(httpUrl.host, name, value, secure = httpUrl.isHttps))
                } else {
                    matching.forEach { cookie ->
                        replaceCookie(stored, cookie.copy(value = value, secure = cookie.secure || httpUrl.isHttps))
                    }
                }
            }
            writeCookies(stored)
        }
    }

    fun putCookieHeader(url: String, cookieHeader: String?) {
        val parsed = parseCookieHeader(cookieHeader)
        if (parsed.isEmpty()) return
        capture(url, parsed)
    }

    fun putUserAgent(userAgent: String?) {
        preferences.edit().apply {
            if (userAgent.isNullOrBlank()) remove(USER_AGENT_KEY) else putString(USER_AGENT_KEY, userAgent)
        }.apply()
    }

    fun userAgent(): String? = preferences.getString(USER_AGENT_KEY, null)?.takeIf(String::isNotBlank)

    fun clear(url: String) {
        val requestHost = host(url) ?: return
        synchronized(lock) {
            writeCookies(readCookies().filterNot { it.hostOnly && it.domain == requestHost })
        }
    }

    fun clearSite(url: String) {
        val requestHost = host(url) ?: return
        synchronized(lock) {
            writeCookies(readCookies().filterNot { it.isSameSite(requestHost) })
        }
    }

    fun hasCookies(url: String): Boolean = cookiesFor(url).isNotEmpty()

    internal fun cookiesFor(url: String): List<StoredCookie> {
        val httpUrl = url.toHttpUrlOrNull() ?: return emptyList()
        synchronized(lock) {
            val stored = readCookies()
            val active = stored.filterNot(StoredCookie::isExpired)
            if (active.size != stored.size) writeCookies(active)
            return active.filter { it.matches(httpUrl) }
                .sortedWith(compareByDescending<StoredCookie> { it.path.length }.thenBy { it.createdAt })
        }
    }

    internal fun cookiesForSite(url: String): List<StoredCookie> {
        val requestHost = host(url) ?: return emptyList()
        synchronized(lock) {
            return readCookies().filterNot(StoredCookie::isExpired).filter { it.isSameSite(requestHost) }
        }
    }

    internal fun cookieDomains(url: String): List<String> =
        cookiesForSite(url).map(StoredCookie::domain).distinct()

    internal fun restoreSetCookieHeaders(url: String): List<String> =
        cookiesFor(url).map(StoredCookie::toSetCookieHeader)

    fun status(url: String): SiteSessionStatus {
        val scoped = cookiesFor(url)
        return SiteSessionStatus(
            host = host(url).orEmpty(),
            cookieCount = scoped.size,
            secureCookieCount = scoped.count(StoredCookie::secure),
            hasUserAgent = userAgent() != null
        )
    }

    private fun replaceCookie(cookies: MutableList<StoredCookie>, replacement: StoredCookie) {
        cookies.removeAll { it.identity == replacement.identity }
        if (!replacement.isExpired() && replacement.value.isNotBlank()) cookies += replacement
    }

    private fun readCookies(): List<StoredCookie> {
        migrateLegacyCookies()
        val raw = preferences.getString(COOKIES_KEY, null) ?: return emptyList()
        return runCatching {
            val array = JSONArray(raw)
            buildList {
                for (index in 0 until array.length()) add(StoredCookie.from(array.getJSONObject(index)))
            }
        }.getOrDefault(emptyList())
    }

    private fun writeCookies(cookies: List<StoredCookie>) {
        val array = JSONArray()
        cookies.filterNot(StoredCookie::isExpired)
            .sortedByDescending(StoredCookie::createdAt)
            .take(MAX_STORED_COOKIES)
            .forEach { array.put(it.toJson()) }
        preferences.edit().putString(COOKIES_KEY, array.toString()).apply()
    }

    private fun migrateLegacyCookies() {
        if (preferences.getBoolean(MIGRATED_KEY, false)) return
        val migrated = mutableListOf<StoredCookie>()
        preferences.all.forEach { (key, value) ->
            if (key.startsWith("__") || value !is String || host("https://$key") == null) return@forEach
            parseCookieHeader(value).forEach { (name, cookieValue) ->
                migrated += StoredCookie.domain(key, name, cookieValue, secure = true)
            }
        }
        val editor = preferences.edit()
        preferences.all.keys.filterNot { it.startsWith("__") }.forEach(editor::remove)
        editor.putBoolean(MIGRATED_KEY, true)
        if (migrated.isNotEmpty()) editor.putString(COOKIES_KEY, JSONArray(migrated.map(StoredCookie::toJson)).toString())
        editor.apply()
    }

    private fun parseCookieHeader(header: String?): Map<String, String> = buildMap {
        header.orEmpty().split(';').forEach { raw ->
            val item = raw.trim()
            val separator = item.indexOf('=')
            if (separator > 0) put(item.substring(0, separator).trim(), item.substring(separator + 1).trim())
        }
    }

    private fun host(url: String): String? = runCatching {
        Uri.parse(url).host?.lowercase()?.takeIf(String::isNotBlank)
    }.getOrNull()

    private fun isRegistrableDomain(domain: String): Boolean = runCatching {
        HttpUrl.Builder().scheme("https").host(domain).build().topPrivateDomain() != null
    }.getOrDefault(false)

    internal data class StoredCookie(
        val name: String,
        val value: String,
        val domain: String,
        val path: String,
        val expiresAt: Long,
        val secure: Boolean,
        val httpOnly: Boolean,
        val hostOnly: Boolean,
        val createdAt: Long
    ) {
        val identity: String get() = "$name|$domain|$path"

        fun matches(url: HttpUrl, includeExpired: Boolean = false): Boolean {
            if (!includeExpired && isExpired()) return false
            if (secure && !url.isHttps) return false
            if (hostOnly) {
                if (url.host != domain) return false
            } else if (url.host != domain && !url.host.endsWith(".$domain")) {
                return false
            }
            return pathMatches(url.encodedPath)
        }

        fun isExpired(now: Long = System.currentTimeMillis()): Boolean = expiresAt <= now

        fun isSameSite(host: String): Boolean = host == domain || host.endsWith(".$domain") || domain.endsWith(".$host")

        fun toSetCookieHeader(): String = buildString {
            append(name).append('=').append(value)
            append("; Path=").append(path)
            if (!hostOnly) append("; Domain=.").append(domain)
            if (secure) append("; Secure")
            if (httpOnly) append("; HttpOnly")
        }

        fun toJson(): JSONObject = JSONObject()
            .put("name", name)
            .put("value", value)
            .put("domain", domain)
            .put("path", path)
            .put("expiresAt", expiresAt)
            .put("secure", secure)
            .put("httpOnly", httpOnly)
            .put("hostOnly", hostOnly)
            .put("createdAt", createdAt)

        private fun pathMatches(requestPath: String): Boolean =
            requestPath == path || requestPath.startsWith(path) && (path.endsWith('/') || requestPath.getOrNull(path.length) == '/')

        companion object {
            fun from(cookie: Cookie): StoredCookie = StoredCookie(
                cookie.name,
                cookie.value,
                cookie.domain,
                cookie.path,
                cookie.expiresAt,
                cookie.secure,
                cookie.httpOnly,
                cookie.hostOnly,
                System.currentTimeMillis()
            )

            fun from(json: JSONObject): StoredCookie = StoredCookie(
                json.getString("name"),
                json.optString("value"),
                json.getString("domain"),
                json.optString("path", "/"),
                json.optLong("expiresAt", Long.MAX_VALUE),
                json.optBoolean("secure"),
                json.optBoolean("httpOnly"),
                json.optBoolean("hostOnly", true),
                json.optLong("createdAt", System.currentTimeMillis())
            )

            fun hostOnly(host: String, name: String, value: String, secure: Boolean = false): StoredCookie =
                StoredCookie(name, value, host, "/", Long.MAX_VALUE, secure, false, true, System.currentTimeMillis())

            fun domain(domain: String, name: String, value: String, secure: Boolean = false): StoredCookie =
                StoredCookie(name, value, domain, "/", Long.MAX_VALUE, secure, false, false, System.currentTimeMillis())
        }
    }

    companion object {
        private const val PREFERENCES_NAME = "vbook_sessions"
        private const val USER_AGENT_KEY = "__webview_user_agent"
        private const val COOKIES_KEY = "__cookies_v2"
        private const val MIGRATED_KEY = "__cookies_v2_migrated"
        private const val MAX_STORED_COOKIES = 512
    }
}

data class SiteSessionStatus(
    val host: String,
    val cookieCount: Int,
    val secureCookieCount: Int,
    val hasUserAgent: Boolean
)
