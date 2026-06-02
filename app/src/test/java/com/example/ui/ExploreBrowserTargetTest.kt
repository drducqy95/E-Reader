package com.example.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ExploreBrowserTargetTest {
    @Test
    fun opensBlockedUrlFromVerificationError() {
        assertEquals(
            "https://reader.example.com/rank/1",
            browserTargetUrl(
                "Source browser verification or login required: https://reader.example.com/rank/1",
                "https://reader.example.com"
            )
        )
    }

    @Test
    fun fallsBackToSourceHomeWhenErrorDoesNotContainUrl() {
        assertEquals(
            "https://reader.example.com",
            browserTargetUrl("Could not load source", "https://reader.example.com")
        )
    }
}
