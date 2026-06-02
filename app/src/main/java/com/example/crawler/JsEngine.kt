package com.example.crawler

import com.script.buildScriptBindings
import com.script.rhino.RhinoScriptEngine
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import android.util.Log
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.mozilla.javascript.NativeArray
import org.mozilla.javascript.NativeObject
import org.mozilla.javascript.Wrapper

class VbookHttp(
    private val sessions: VbookSessionStore?,
    private val webViewHost: WebViewHost
) {
    fun get(url: String): VbookRequest = VbookRequest(url, sessions, webViewHost)
    fun post(url: String): VbookRequest = VbookRequest(url, sessions, webViewHost).method("POST")
}

class VbookConsole {
    fun log(message: Any?) {
        android.util.Log.d("VBookJs", message?.toString().orEmpty())
    }
}

class VbookHtml {
    fun parse(html: String): Document = Jsoup.parse(html)
}

class VbookRuntime {
    fun sleep(milliseconds: Int) {
        Thread.sleep(milliseconds.coerceIn(0, MAX_SLEEP_MS).toLong())
    }

    companion object {
        private const val MAX_SLEEP_MS = 5_000
    }
}

class VbookRequest(
    private val url: String,
    private val sessions: VbookSessionStore?,
    private val webViewHost: WebViewHost
) {
    private val headers = mutableMapOf<String, String>()
    private val params = mutableMapOf<String, String>()
    private var method = org.jsoup.Connection.Method.GET
    private val response by lazy {
        runCatching {
            connection().execute().also {
                sessions?.capture(it.url().toString(), it.headers("Set-Cookie"))
                sessions?.capture(it.url().toString(), it.cookies())
            }
        }
    }
    private val renderedHtml by lazy {
        if (!shouldRenderFallback()) return@lazy ""
        Log.i(TAG, "Falling back to WebView render for $url")
        webViewHost.render(url, "document.documentElement.outerHTML")
    }

    fun method(value: String): VbookRequest = apply {
        method = runCatching { org.jsoup.Connection.Method.valueOf(value.uppercase()) }
            .getOrDefault(org.jsoup.Connection.Method.GET)
    }

    fun header(name: String, value: String): VbookRequest = apply { headers[name] = value }

    fun headers(values: Map<*, *>): VbookRequest = apply {
        values.forEach { (key, value) ->
            if (key != null && value != null) headers[key.toString()] = value.toString()
        }
    }

    fun param(name: String, value: String): VbookRequest = apply { params[name] = value }

    fun params(values: Map<*, *>): VbookRequest = apply {
        values.forEach { (key, value) ->
            if (key != null && value != null) params[key.toString()] = value.toString()
        }
    }

    fun status(): Int {
        val directStatus = directStatus()
        return if (directStatus in 200..299) directStatus
        else if (renderedHtml.isNotBlank()) 200
        else directStatus
    }

    fun directStatus(): Int = response.getOrNull()?.statusCode() ?: 0

    fun directHtml(charset: String?): Document? = runCatching {
        val direct = response.getOrThrow()
        if (!charset.isNullOrBlank()) direct.charset(charset)
        direct.parse()
    }.getOrNull()

    fun html(charset: String?): Document? = runCatching {
        if (renderedHtml.isNotBlank()) {
            Jsoup.parse(renderedHtml, url)
        } else {
            directHtml(charset)
        }
    }.getOrNull()

    fun string(): String = renderedHtml.ifBlank {
        runCatching { response.getOrThrow().body() }.getOrDefault("")
    }

    private fun shouldRenderFallback(): Boolean {
        if (!webViewHost.isAvailable() || method != org.jsoup.Connection.Method.GET || params.isNotEmpty()) return false
        val status = response.getOrNull()?.statusCode() ?: 0
        return status == 0 || status in WEBVIEW_FALLBACK_STATUS
    }

    private fun connection() = Jsoup.connect(url)
        .userAgent(sessions?.userAgent() ?: DEFAULT_USER_AGENT)
        .timeout(15_000)
        .ignoreContentType(true)
        .ignoreHttpErrors(true)
        .method(method)
        .apply {
            sessions?.cookieHeader(url)?.let { header("Cookie", it) }
            headers.forEach { (name, value) -> header(name, value) }
            params.forEach { (name, value) -> data(name, value) }
        }

    companion object {
        private const val TAG = "VbookRequest"
        private const val DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36 Chrome/120 Mobile Safari/537.36"
        private val WEBVIEW_FALLBACK_STATUS = setOf(401, 403, 429, 503)
    }
}

class VbookBrowserHost(
    private val sessions: VbookSessionStore?,
    private val webViewHost: WebViewHost
) {
    fun launch(url: String): Document {
        val direct = VbookRequest(url, sessions, webViewHost)
        if (direct.directStatus() in 200..299) {
            direct.directHtml(null)?.let { return it }
        }
        return Jsoup.parse(webViewHost.render(url, "document.documentElement.outerHTML"), url)
    }

    fun close() = Unit
}

class JsEngine(
    private val sessions: VbookSessionStore? = null,
    private val webViewEvaluator: com.drduc.legado.WebViewRuleEvaluator? = null
) {
    fun evaluate(js: String): String = runCatching {
        RhinoScriptEngine.eval(js)?.toString().orEmpty()
    }.getOrElse { "Error: ${it.message}" }

    fun evaluateRule(script: String, input: Any?): Any? = runCatching {
        val bindings = buildScriptBindings {
            it["result"] = input
            it["baseResult"] = input
        }
        val scope = RhinoScriptEngine.getRuntimeScope(bindings)
        parseJsResult(RhinoScriptEngine.eval("(function(){\n$script\n})()", scope))
    }.getOrNull()

    fun executeVbook(
        script: String,
        allScripts: Map<String, String>?,
        functionName: String,
        vararg args: Any
    ): Any? = runBlocking {
        executeVbookSuspend(script, allScripts, functionName, *args)
    }

    suspend fun executeVbookSuspend(
        script: String,
        allScripts: Map<String, String>?,
        functionName: String,
        vararg args: Any
    ): Any? = withTimeout(VBOOK_SCRIPT_TIMEOUT_MS) {
        runCatching {
            val webViewHost = WebViewHost(webViewEvaluator)
            val bindings = buildScriptBindings {
                it["HttpHost"] = VbookHttp(sessions, webViewHost)
                it["HtmlHost"] = VbookHtml()
                it["RuntimeHost"] = VbookRuntime()
                it["WebViewHost"] = webViewHost
                it["BrowserHost"] = VbookBrowserHost(sessions, webViewHost)
                it["ConsoleHost"] = VbookConsole()
                it["__args"] = args
            }
            val scope = RhinoScriptEngine.getRuntimeScope(bindings)
            val source = buildString {
                appendLine(PRELUDE)
                appendLine(inlineLoads(script, allScripts))
                appendLine("if (typeof $functionName !== 'function') throw new Error('Missing function: $functionName');")
                appendLine("$functionName.apply(null, Array.prototype.map.call(__args, function(value) { return '' + value; }));")
            }
            parseJsResult(RhinoScriptEngine.evalSuspend(source, scope))
        }.getOrElse { error ->
            mapOf("error" to (error.message ?: error.toString()))
        }
    }

    private fun inlineLoads(script: String, allScripts: Map<String, String>?): String {
        if (allScripts == null) return script
        var source = script
        val loadRegex = Regex("""load\s*\(\s*['"]([^'"]+)['"]\s*\)\s*;?""")
        while (true) {
            val match = loadRegex.find(source) ?: break
            val path = match.groupValues[1]
            val replacement = allScripts[path] ?: allScripts[path.substringAfterLast('/')] ?: ""
            source = source.replaceRange(match.range, replacement + "\n")
        }
        return source
    }

    private fun parseJsResult(obj: Any?, visited: MutableSet<Any> = mutableSetOf()): Any? {
        if (obj == null) return null
        if (!visited.add(obj)) return "[Circular]"
        return try {
            when (obj) {
                is NativeObject -> obj.allIds.associate { id ->
                    id.toString() to parseJsResult(obj.get(id.toString(), obj), visited)
                }
                is NativeArray -> (0 until obj.length.toInt()).map { parseJsResult(obj.get(it, obj), visited) }
                is Wrapper -> obj.unwrap()
                else -> obj
            }
        } finally {
            visited.remove(obj)
        }
    }

    companion object {
        private const val VBOOK_SCRIPT_TIMEOUT_MS = 45_000L
        private val PRELUDE = """
            var console = { log: function(message) { ConsoleHost.log(message); } };
            var Response = {
              success: function(data, next) { return { data: data, next: next }; },
              error: function(message) { return { error: String(message) }; }
            };
            function __wrapElement(element) {
              if (!element) return null;
              var api = {
                select: function(selector) { return __wrapElements(element.select(String(selector))); },
                attr: function(name, value) {
                  if (arguments.length > 1) {
                    element.attr(String(name), String(value));
                    return api;
                  }
                  return '' + element.attr(String(name));
                },
                hasAttr: function(name) { return element.hasAttr(String(name)); },
                text: function(value) {
                  if (arguments.length > 0) {
                    element.text(String(value));
                    return api;
                  }
                  return '' + element.text();
                },
                html: function(value) {
                  if (arguments.length > 0) {
                    element.html(String(value));
                    return api;
                  }
                  return '' + element.html();
                },
                outerHtml: function() { return '' + element.outerHtml(); },
                remove: function() {
                  element.remove();
                  return api;
                }
              };
              return api;
            }
            function __wrapElements(elements) {
              var api = {
                length: elements.size(),
                size: function() { return elements.size(); },
                get: function(index) { return __wrapElement(elements.get(Number(index))); },
                first: function() { return __wrapElement(elements.first()); },
                last: function() { return __wrapElement(elements.last()); },
                select: function(selector) { return __wrapElements(elements.select(String(selector))); },
                attr: function(name, value) {
                  if (arguments.length > 1) {
                    elements.attr(String(name), String(value));
                    return api;
                  }
                  return '' + elements.attr(String(name));
                },
                text: function(value) {
                  if (arguments.length > 0) {
                    elements.text(String(value));
                    return api;
                  }
                  return '' + elements.text();
                },
                html: function(value) {
                  if (arguments.length > 0) {
                    elements.html(String(value));
                    return api;
                  }
                  return '' + elements.html();
                },
                remove: function() {
                  elements.remove();
                  return api;
                },
                forEach: function(callback) {
                  for (var index = 0; index < elements.size(); index++) {
                    callback(__wrapElement(elements.get(index)), index, api);
                  }
                }
              };
              return api;
            }
            function __wrapRequest(request) {
              var api = {
                method: function(value) {
                  request.method(String(value));
                  return api;
                },
                header: function(name, value) {
                  request.header(String(name), String(value));
                  return api;
                },
                headers: function(values) {
                  request.headers(values);
                  return api;
                },
                param: function(name, value) {
                  request.param(String(name), String(value));
                  return api;
                },
                params: function(values) {
                  request.params(values);
                  return api;
                },
                status: function() { return request.status(); },
                html: function(charset) {
                  var document = request.html(charset ? String(charset) : null);
                  return document ? __wrapElement(document) : null;
                },
                text: function() { return '' + request.string(); },
                string: function() { return '' + request.string(); },
                json: function() { return JSON.parse(String(request.string())); }
              };
              return api;
            }
            var Http = {
              get: function(url) { return __wrapRequest(HttpHost.get(String(url))); },
              post: function(url) { return __wrapRequest(HttpHost.post(String(url))); }
            };
            var Html = {
              parse: function(html) { return __wrapElement(HtmlHost.parse(String(html))); }
            };
            function sleep(milliseconds) {
              RuntimeHost.sleep(Math.max(0, Math.min(5000, Number(milliseconds) || 0)));
            }
            function fetch(url, options) {
              var request = options && String(options.method || 'GET').toUpperCase() === 'POST'
                ? Http.post(String(url)) : Http.get(String(url));
              if (options && options.headers) {
                Object.keys(options.headers).forEach(function(key) {
                  request.header(String(key), String(options.headers[key]));
                });
              }
              if (options && options.body) {
                Object.keys(options.body).forEach(function(key) {
                  request.param(String(key), String(options.body[key]));
                });
              }
              if (options && options.queries) {
                Object.keys(options.queries).forEach(function(key) {
                  request.param(String(key), String(options.queries[key]));
                });
              }
              var status = request.status();
              return {
                ok: status >= 200 && status < 300,
                status: status,
                html: function(charset) {
                  return request.html(charset ? String(charset) : null);
                },
                text: function() { return request.string(); },
                json: function() { return request.json(); }
              };
            }
            var Web = {
              render: function(url, script) {
                return WebViewHost.render(String(url), script ? String(script) : 'document.documentElement.outerHTML');
              }
            };
            var Engine = {
              newBrowser: function() {
                return {
                  launch: function(url) { return __wrapElement(BrowserHost.launch(String(url))); },
                  close: function() { BrowserHost.close(); }
                };
              }
            };
        """.trimIndent()
    }
}

class WebViewHost(private val evaluator: com.drduc.legado.WebViewRuleEvaluator?) {
    fun isAvailable(): Boolean = evaluator != null

    fun render(url: String, script: String): String = evaluator?.let {
        runBlocking { it.evaluate(url, "", script) }
    }.orEmpty()
}
