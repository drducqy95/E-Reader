package com.drduc.web

import fi.iki.elonen.NanoHTTPD
import java.io.ByteArrayInputStream
import java.security.SecureRandom
import java.util.Base64
import org.json.JSONException
import org.json.JSONObject

data class WebApiResponse(
    val status: NanoHTTPD.Response.Status = NanoHTTPD.Response.Status.OK,
    val mimeType: String = "application/json; charset=utf-8",
    val body: String
)

fun interface WebApi {
    fun handle(session: NanoHTTPD.IHTTPSession): WebApiResponse?
}

class SecureLocalWebServer(
    port: Int = 1122,
    allowLan: Boolean = false,
    private val api: WebApi
) : NanoHTTPD(if (allowLan) "0.0.0.0" else "127.0.0.1", port) {
    val sessionToken: String = newToken()

    override fun serve(session: IHTTPSession): Response {
        if (session.method == Method.OPTIONS) return cors(session, newFixedLengthResponse(""))
        if (session.uri == "/bootstrap") {
            val response = json("""{"isSuccess":true,"errorMsg":"","data":{"csrfToken":"$sessionToken"}}""")
            addSessionCookie(response)
            return cors(session, response)
        }
        staticAsset(session.uri)?.let {
            if (session.uri in EMBEDDED_INDEX_ROUTES) addSessionCookie(it)
            return cors(session, it)
        }
        if (!authorized(session)) {
            return cors(session, json("""{"isSuccess":false,"errorMsg":"Unauthorized","data":null}""", Response.Status.UNAUTHORIZED))
        }
        val response = runCatching { api.handle(session) }.fold(
            onSuccess = {
                it ?: WebApiResponse(
                    status = Response.Status.NOT_FOUND,
                    body = """{"isSuccess":false,"errorMsg":"Not found","data":null}"""
                )
            },
            onFailure = ::handlerFailure
        )
        return cors(session, newFixedLengthResponse(response.status, response.mimeType, response.body))
    }

    private fun staticAsset(uri: String): Response? {
        val asset = when {
            uri == "/" || uri == "/admin" || uri == "/admin/" -> "web/admin/index.html"
            uri == "/reader" || uri == "/reader/" -> "web/reader/index.html"
            uri.startsWith("/admin/") -> "web/admin/" + uri.removePrefix("/admin/")
            uri.startsWith("/reader/") -> "web/reader/" + uri.removePrefix("/reader/")
            else -> return null
        }
        val stream = javaClass.classLoader?.getResourceAsStream(asset)
            ?: if (uri.startsWith("/admin/")) javaClass.classLoader?.getResourceAsStream("web/admin/index.html")
            else if (uri.startsWith("/reader/")) javaClass.classLoader?.getResourceAsStream("web/reader/index.html")
            else null
        return stream?.use {
            val bytes = it.readBytes()
            newFixedLengthResponse(Response.Status.OK, mimeType(asset), ByteArrayInputStream(bytes), bytes.size.toLong())
        }
    }

    private fun authorized(session: IHTTPSession): Boolean {
        val bearer = session.headers["authorization"]?.removePrefix("Bearer ")
        val header = session.headers["x-drduc-token"]
        val cookie = session.headers["cookie"]
            ?.split(';')
            ?.map(String::trim)
            ?.firstOrNull { it.startsWith("drduc_session=") }
            ?.substringAfter('=')
        return sequenceOf(bearer, header, cookie).any { it == sessionToken }
    }

    private fun handlerFailure(error: Throwable): WebApiResponse {
        val status = if (error is IllegalArgumentException || error is JSONException) {
            Response.Status.BAD_REQUEST
        } else {
            Response.Status.INTERNAL_ERROR
        }
        return WebApiResponse(
            status = status,
            body = JSONObject()
                .put("isSuccess", false)
                .put("errorMsg", error.message ?: "Request failed")
                .put("data", JSONObject.NULL)
                .toString()
        )
    }

    private fun cors(session: IHTTPSession, response: Response): Response {
        val origin = session.headers["origin"]
        if (origin == null || origin.startsWith("http://127.0.0.1") || origin.startsWith("http://localhost")) {
            response.addHeader("Access-Control-Allow-Origin", origin ?: "http://127.0.0.1")
        }
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, PUT, DELETE, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "content-type, authorization, x-drduc-token")
        response.addHeader("Access-Control-Allow-Credentials", "true")
        return response
    }

    private fun json(body: String, status: Response.Status = Response.Status.OK): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", body)

    private fun addSessionCookie(response: Response) {
        response.addHeader("Set-Cookie", "drduc_session=$sessionToken; HttpOnly; SameSite=Strict; Path=/")
    }

    private fun mimeType(path: String): String = when {
        path.endsWith(".js") -> "application/javascript; charset=utf-8"
        path.endsWith(".css") -> "text/css; charset=utf-8"
        path.endsWith(".json") -> "application/json; charset=utf-8"
        path.endsWith(".svg") -> "image/svg+xml"
        path.endsWith(".png") -> "image/png"
        path.endsWith(".ico") -> "image/x-icon"
        path.endsWith(".jpg") || path.endsWith(".jpeg") -> "image/jpeg"
        path.endsWith(".webp") -> "image/webp"
        path.endsWith(".woff2") -> "font/woff2"
        path.endsWith(".woff") -> "font/woff"
        else -> "text/html; charset=utf-8"
    }

    companion object {
        private val EMBEDDED_INDEX_ROUTES = setOf("/", "/admin", "/admin/", "/reader", "/reader/")

        private fun newToken(): String {
            val bytes = ByteArray(32)
            SecureRandom().nextBytes(bytes)
            return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        }
    }
}
