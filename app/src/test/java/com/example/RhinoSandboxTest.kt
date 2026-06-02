package com.example

import com.example.crawler.JsEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class RhinoSandboxTest {
    @Test
    fun evaluatesNormalJavascript() {
        assertEquals("3.0", JsEngine().evaluate("1 + 2"))
    }

    @Test
    fun blocksRuntimeClass() {
        val value = JsEngine().evaluate("java.lang.Runtime.getRuntime()")
        assertTrue(value.startsWith("Error:"))
    }
}
