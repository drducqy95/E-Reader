package com.example.crawler

import org.mozilla.javascript.Context
import org.mozilla.javascript.Scriptable
import org.mozilla.javascript.ScriptableObject
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.NativeArray

class VbookHttp {
    fun get(url: String): VbookRequest {
        return VbookRequest(url)
    }
}

class VbookRequest(private val url: String) {
    private val headers = mutableMapOf<String, String>()
    private val params = mutableMapOf<String, String>()

    fun headers(headersMap: Map<*, *>): VbookRequest {
        headersMap.forEach { (k, v) ->
            if (k != null && v != null) headers[k.toString()] = v.toString()
        }
        return this
    }

    fun params(paramsMap: Map<*, *>): VbookRequest {
        paramsMap.forEach { (k, v) ->
            if (k != null && v != null) params[k.toString()] = v.toString()
        }
        return this
    }

    fun html(): Document? {
        return try {
            val con = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .timeout(15000)
                .ignoreContentType(true)
            headers.forEach { con.header(it.key, it.value) }
            params.forEach { con.data(it.key, it.value) }
            con.get()
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun string(): String {
        return try {
            val con = Jsoup.connect(url)
                .userAgent("Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36")
                .timeout(15000)
                .ignoreContentType(true)
            headers.forEach { con.header(it.key, it.value) }
            params.forEach { con.data(it.key, it.value) }
            con.execute().body()
        } catch (e: Exception) {
            e.printStackTrace()
            ""
        }
    }
}

class JsEngine {
    fun evaluate(js: String): String {
        val cx = Context.enter()
        cx.optimizationLevel = -1
        return try {
            val scope: Scriptable = cx.initStandardObjects()
            val result = cx.evaluateString(scope, js, "JsEngine", 1, null)
            Context.toString(result)
        } catch (e: Exception) {
            "Error: ${e.message}"
        } finally {
            Context.exit()
        }
    }
    
    // Execute Vbook Extension script
    fun executeVbook(script: String, allScripts: Map<String, String>?, functionName: String, vararg args: Any): Any? {
        val cx = Context.enter()
        cx.optimizationLevel = -1
        cx.languageVersion = Context.VERSION_ES6
        return try {
            val scope: Scriptable = cx.initStandardObjects()
            
            // Inject Http object
            ScriptableObject.putProperty(scope, "Http", Context.javaToJS(VbookHttp(), scope))
            
            // Inline all load('script') so they evaluate in the same lexical scope and avoid ReferenceError
            var combinedScript = script
            if (allScripts != null) {
                val loadRegex = Regex("""load\s*\(\s*['"]([^'"]+)['"]\s*\)\s*;?""")
                var match = loadRegex.find(combinedScript)
                while (match != null) {
                    val scriptName = match.groupValues[1]
                    val sName = scriptName.split("/").last()
                    val scriptContent = allScripts[sName] ?: allScripts[scriptName] ?: ""
                    combinedScript = combinedScript.replaceRange(match.range, scriptContent + "\n")
                    match = loadRegex.find(combinedScript)
                }
            }
            
            // Inject environment and Response object via JS Prelude
            val prelude = """
                var Response = {
                    success: function(data, next) {
                        return { data: data, next: next };
                    },
                    error: function(msg) {
                        return { error: msg };
                    }
                };
                function fetch(url, options) {
                    var req = Http.get(url);
                    if (options && options.headers) Object.keys(options.headers).forEach(function(k) { req.headers(k, options.headers[k]); });
                    if (options && options.body) Object.keys(options.body).forEach(function(k) { req.params(k, options.body[k]); });
                    return {
                        ok: true,
                        html: function() { return req.html(); },
                        text: function() { return req.string(); },
                        json: function() { return JSON.parse(req.string()); }
                    };
                }
                var console = {
                    log: function(msg) { java.lang.System.out.println(msg); }
                };
            """.trimIndent()
            cx.evaluateString(scope, prelude, "Prelude", 1, null)
            
            // Evaluate the main script (which contains `function execute(...)`)
            cx.evaluateString(scope, combinedScript, "VbookPlugin", 1, null)
            
            // Call the requested function
            val fObj = scope.get(functionName, scope)
            if (fObj !is org.mozilla.javascript.Function) {
                throw Exception("Function ${'$'}functionName is undefined or not a function.")
            }
            
            // Convert args to JS
            val jsArgs = args.map { Context.javaToJS(it, scope) }.toTypedArray()
            
            val result = fObj.call(cx, scope, scope, jsArgs)
            
            // Extract the result (we expect a NativeObject from Response.success)
            parseJsResult(result)
        } catch (e: org.mozilla.javascript.RhinoException) {
            e.printStackTrace()
            val errorMsg = "${e.message}\nLine: ${e.lineNumber()}\nColumn: ${e.columnNumber()}\nSource: ${e.lineSource()}"
            mapOf("error" to errorMsg)
        } catch (e: Throwable) {
            e.printStackTrace()
            mapOf("error" to e.message.toString())
        } finally {
            Context.exit()
        }
    }
    
    // Recursively parse JS object to Kotlin Map/List
    private fun parseJsResult(obj: Any?, visited: MutableSet<Any> = mutableSetOf()): Any? {
        if (obj == null) return null
        if (!visited.add(obj)) {
            return "[Circular]"
        }
        return try {
            when (obj) {
                is NativeObject -> {
                    val map = mutableMapOf<String, Any?>()
                    for (id in obj.allIds) {
                        val key = id.toString()
                        val value = obj.get(key, obj)
                        map[key] = parseJsResult(value, visited)
                    }
                    map
                }
                is NativeArray -> {
                    val list = mutableListOf<Any?>()
                    for (i in 0 until obj.length.toInt()) {
                        list.add(parseJsResult(obj.get(i, obj), visited))
                    }
                    list
                }
                is org.mozilla.javascript.Wrapper -> obj.unwrap()
                else -> obj
            }
        } finally {
            visited.remove(obj)
        }
    }
}
