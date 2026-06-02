package com.example.crawler

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class VbookSessionStoreTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("vbook_sessions", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun sharesDomainCookieWithSubdomainsAndKeepsHostCookieScoped() {
        val sessions = VbookSessionStore(context)
        sessions.capture(
            "https://login.example.com/auth",
            listOf(
                "sid=domain-session; Domain=.example.com; Path=/; HttpOnly",
                "theme=dark; Path=/"
            )
        )

        assertEquals("sid=domain-session", sessions.cookieHeader("https://reader.example.com/chapter"))
        val loginCookies = sessions.cookieHeader("https://login.example.com/account").orEmpty()
        assertTrue(loginCookies.contains("sid=domain-session"))
        assertTrue(loginCookies.contains("theme=dark"))
    }

    @Test
    fun removesExpiredCookieAndRestoresRemainingCookiesAfterStoreRestart() {
        val sessions = VbookSessionStore(context)
        sessions.capture(
            "https://www.example.com/login",
            listOf("sid=first; Domain=.example.com; Path=/", "theme=dark; Domain=.example.com; Path=/")
        )
        sessions.capture(
            "https://www.example.com/logout",
            listOf("sid=; Domain=.example.com; Max-Age=0; Path=/")
        )

        val restored = VbookSessionStore(context).cookieHeader("https://api.example.com/private").orEmpty()
        assertFalse(restored.contains("sid="))
        assertEquals("theme=dark", restored)
    }

    @Test
    fun clearsParentAndHostCookiesForCurrentSite() {
        val sessions = VbookSessionStore(context)
        sessions.capture(
            "https://login.example.com/auth",
            listOf("sid=domain-session; Domain=.example.com; Path=/", "hostOnly=one; Path=/")
        )

        sessions.clearSite("https://login.example.com/account")

        assertFalse(sessions.hasCookies("https://login.example.com"))
        assertFalse(sessions.hasCookies("https://reader.example.com"))
    }

    @Test
    fun rejectsCookieScopedToPublicSuffix() {
        val sessions = VbookSessionStore(context)
        sessions.capture(
            "https://login.example.co.uk/auth",
            listOf("unsafe=value; Domain=.co.uk; Path=/", "sid=domain-session; Domain=.example.co.uk; Path=/")
        )

        assertEquals("sid=domain-session", sessions.cookieHeader("https://reader.example.co.uk/chapter"))
    }

    @Test
    fun normalizesTypedBrowserAddress() {
        assertEquals("https://69shuba.com", WebViewLoginActivity.normalizeAddress("69shuba.com"))
        assertEquals("http://127.0.0.1:8080/login", WebViewLoginActivity.normalizeAddress(" http://127.0.0.1:8080/login "))
    }

    @Test
    fun persistsWebViewUserAgentForCrawlerRequests() {
        val sessions = VbookSessionStore(context)
        sessions.putUserAgent("fixture-webview-user-agent")

        assertEquals("fixture-webview-user-agent", VbookSessionStore(context).userAgent())
    }

    @Test
    fun appliesPathSecureAndExpiryRulesWhenBuildingRequestCookieHeader() {
        val sessions = VbookSessionStore(context)
        sessions.capture(
            "https://reader.example.com/login",
            listOf(
                "scope=root; Path=/; Secure",
                "scope=account; Path=/account; Secure",
                "expired=gone; Path=/; Max-Age=0",
                "plain=http-ok; Path=/"
            )
        )

        assertEquals("scope=account; scope=root; plain=http-ok", sessions.cookieHeader("https://reader.example.com/account/profile"))
        assertEquals("plain=http-ok", sessions.cookieHeader("http://reader.example.com/account/profile"))
        assertFalse(sessions.cookieHeader("https://reader.example.com/chapter").orEmpty().contains("account"))
        assertFalse(sessions.cookieHeader("https://reader.example.com/chapter").orEmpty().contains("expired"))
    }

    @Test
    fun restoresScopedCookiesIntoWebViewAsIndividualSetCookieHeaders() {
        val sessions = VbookSessionStore(context)
        sessions.capture(
            "https://reader.example.com/login",
            listOf("sid=secure-session; Domain=.example.com; Path=/reader; Secure; HttpOnly")
        )

        assertEquals(
            listOf("sid=secure-session; Path=/reader; Domain=.example.com; Secure; HttpOnly"),
            sessions.restoreSetCookieHeaders("https://reader.example.com/reader/chapter")
        )
        assertTrue(sessions.restoreSetCookieHeaders("https://reader.example.com/public").isEmpty())
    }

    @Test
    fun migratesLegacyFlattenedDomainCookiesOnce() {
        context.getSharedPreferences("vbook_sessions", Context.MODE_PRIVATE)
            .edit()
            .putString("example.com", "sid=legacy; theme=dark")
            .commit()

        val restored = VbookSessionStore(context).cookieHeader("https://reader.example.com/chapter").orEmpty()

        assertTrue(restored.contains("sid=legacy"))
        assertTrue(restored.contains("theme=dark"))
        assertEquals(null, VbookSessionStore(context).cookieHeader("http://reader.example.com/chapter"))
        assertFalse(context.getSharedPreferences("vbook_sessions", Context.MODE_PRIVATE).contains("example.com"))
    }

    @Test
    fun treatsNewCookiesCapturedFromHttpsWebViewAsSecureAndReportsSessionWithoutValues() {
        val sessions = VbookSessionStore(context)
        sessions.putUserAgent("fixture-agent")
        sessions.putCookieHeader("https://reader.example.com/chapter", "clearance=secret-value")

        assertEquals("clearance=secret-value", sessions.cookieHeader("https://reader.example.com/chapter"))
        assertEquals(null, sessions.cookieHeader("http://reader.example.com/chapter"))
        assertEquals(
            SiteSessionStatus("reader.example.com", cookieCount = 1, secureCookieCount = 1, hasUserAgent = true),
            sessions.status("https://reader.example.com/chapter")
        )
    }
}
