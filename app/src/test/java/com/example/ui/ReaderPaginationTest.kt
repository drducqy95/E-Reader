package com.example.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaderPaginationTest {
    @Test
    fun splitsLongChineseParagraphWithoutLosingText() {
        val source = "天地玄黄".repeat(160)
        val pages = paginateReaderText(listOf(source), 120)

        assertTrue(pages.size > 1)
        assertTrue(pages.all { it.text.length <= 120 })
        assertEquals(source, pages.joinToString("") { it.text })
        assertTrue(pages.all { it.firstParagraph == 0 && it.lastParagraph == 0 })
    }

    @Test
    fun keepsParagraphAnchorsWhenPageBoundaryIsReached() {
        val pages = paginateReaderText(listOf("alpha", "beta", "gamma"), 11)

        assertEquals(2, pages.size)
        assertEquals(0, pages.first().firstParagraph)
        assertEquals(1, pages.first().lastParagraph)
        assertEquals(2, pages.last().firstParagraph)
        assertEquals("alpha\n\nbeta", pages.first().text)
        assertEquals("gamma", pages.last().text)
    }
}
