package com.example

import org.junit.Test
import java.net.URL
import java.util.zip.ZipInputStream

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(manifest=org.robolectric.annotation.Config.NONE)
class TestZip {
    @Test
    fun testJson() {
        val url = "https://raw.githubusercontent.com/duongden/vbook/main/FixedDomain/truyenfull/plugin.zip"
        val ext = com.example.crawler.ExtensionParser.parseZipFromUrl(url)
        java.io.File("zip_test_result.txt").writeText("Ext: $ext, scripts: ${ext?.scripts?.size}")
        if (ext == null || ext.scripts.isEmpty()) return
        
        val jsEngine = com.example.crawler.JsEngine()
        val homeScript = ext.scripts["home"] ?: ext.scripts["home.js"] ?: return
        
        val rs = jsEngine.executeVbook(homeScript, ext.scripts, "execute")
        java.io.File("zip_test_result.txt").appendText("\nHome RS: $rs")
        
        val list = mutableListOf<Map<String, String>>()
        if (rs is Map<*, *>) {
             val data = rs["data"] as? List<*>
             data?.forEach { item ->
                 if (item is Map<*, *>) {
                     list.add(mapOf("input" to (item["input"]?.toString() ?: ""), "script" to (item["script"]?.toString() ?: "")))
                 }
             }
        }
        
        if (list.isNotEmpty()) {
            val tab = list[1]
            val genScript = ext.scripts[tab["script"]] ?: return
            val rs2 = jsEngine.executeVbook(genScript, ext.scripts, "execute", tab["input"] ?: "", "1")
            java.io.File("zip_test_result.txt").appendText("\nGen RS: $rs2")
        }
    }
    
    @Test
    fun testJson2() {
        try {
            val url = "https://raw.githubusercontent.com/duongden/vbook/main/FixedDomain/69shu/plugin.zip"
            val ext = com.example.crawler.ExtensionParser.parseZipFromUrl(url)
            if (ext != null) {
                if (ext.id == "error") {
                    java.io.File("zip_test_result2.txt").writeText("69shu Result: Error = ${ext.description}")
                } else {
                    val jsEngine = com.example.crawler.JsEngine()
                    val homeScript = ext.scripts["home"] ?: ext.scripts["home.js"]
                    if (homeScript != null) {
                        val rs = jsEngine.executeVbook(homeScript, ext.scripts, "execute")
                        java.io.File("zip_test_result2.txt").writeText("69shu Result: $rs")
                    }
                }
            }
        } catch(e: Exception) {}
    }
}
