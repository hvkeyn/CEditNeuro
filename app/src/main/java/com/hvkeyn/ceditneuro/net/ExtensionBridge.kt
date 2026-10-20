package com.hvkeyn.ceditneuro.net

import android.util.Log
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.concurrent.thread

/**
 * A loopback handoff for a browser extension, shaped like the endpoints the VidBee
 * extension already calls: `/status`, `/token`, `/video-info`, plus `/download`.
 * It only listens on 127.0.0.1; a request that changes the phone needs the token from
 * `/token`. A browser on the same desktop reaches it through `adb reverse tcp:27100 tcp:27100`.
 */
object ExtensionBridge {

    private const val TAG = "CEditNeuroBridge"
    private const val PORT_START = 27_100
    private const val PORT_END = 27_120
    private const val MAX_HEADER_LINES = 80
    private const val MAX_BODY = 64 * 1024

    @Volatile
    private var server: ServerSocket? = null

    @Volatile
    private var running = false

    @Volatile
    var port: Int = 0
        private set

    @Volatile
    var token: String = ""
        private set

    private var acceptThread: Thread? = null
    private val workers = Executors.newFixedThreadPool(4) { runnable ->
        Thread(runnable, "extension-bridge-worker").apply { isDaemon = true }
    }

    /** Returns the JSON a page analysis prints, or throws. */
    private var infoProvider: (suspend (String) -> JsonObject)? = null

    /** Starts one agent download for the address. False means a project is missing or busy. */
    private var downloadHandler: ((String, String) -> Boolean)? = null

    fun start(
        info: suspend (String) -> JsonObject,
        download: (String, String) -> Boolean,
    ): Boolean {
        if (running) return true
        infoProvider = info
        downloadHandler = download
        token = newToken()
        port = 0
        var bound: ServerSocket? = null
        for (candidate in PORT_START..PORT_END) {
            val socket = runCatching {
                ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), candidate))
                }
            }.getOrNull() ?: continue
            bound = socket
            port = candidate
            break
        }
        val socket = bound ?: run {
            Log.w(TAG, "No loopback port in $PORT_START-$PORT_END is free.")
            return false
        }
        server = socket
        running = true
        acceptThread = thread(name = "extension-bridge", isDaemon = true) {
            while (running) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                runCatching { workers.execute { handle(client) } }
                    .onFailure { runCatching { client.close() } }
            }
        }
        Log.i(TAG, "Extension bridge on http://127.0.0.1:$port")
        return true
    }

    fun stop() {
        running = false
        runCatching { server?.close() }
        server = null
        acceptThread?.interrupt()
        acceptThread = null
        infoProvider = null
        downloadHandler = null
        port = 0
    }

    fun address(): String = if (port == 0) "" else "http://127.0.0.1:$port"

    private fun handle(client: Socket) {
        client.use { socket ->
            socket.soTimeout = 30_000
            runCatching {
                val request = readRequest(socket) ?: return
                val path = request.path.lowercase(Locale.US)
                if (request.method == "OPTIONS") {
                    if (!originAllowed(request)) {
                        respond(socket, 403, errorJson("This page may not use the phone handoff."), preflight = true)
                        return
                    }
                    respond(socket, 204, "", "text/plain", preflight = true)
                    return
                }
                if (path != "/status" && path != "/" && !originAllowed(request)) {
                    respond(socket, 403, errorJson("This page may not use the phone handoff."))
                    return
                }
                when (path) {
                    "/status" -> respond(socket, 200, statusJson().toString())
                    "/token" -> respond(socket, 200, buildJsonObject { put("token", token) }.toString())
                    "/video-info" -> videoInfo(socket, request)
                    "/download" -> download(socket, request)
                    "/" -> respond(
                        socket,
                        200,
                        buildJsonObject {
                            put("ok", true)
                            put("app", "CEditNeuro")
                            put("endpoints", "/status, /token, /video-info, /download")
                        }.toString(),
                    )
                    else -> respond(socket, 404, errorJson("No endpoint $path."))
                }
            }.onFailure { error ->
                Log.w(TAG, "Request failed: ${error.message}")
                runCatching { respond(socket, 500, errorJson(error.message ?: "Bridge error.")) }
            }
        }
    }

    private fun videoInfo(socket: Socket, request: BridgeRequest) {
        if (!authorized(request)) {
            respond(socket, 403, errorJson("Token is missing. Read /token first."))
            return
        }
        val url = request.arg("url")
        if (url.isNullOrBlank()) {
            respond(socket, 400, errorJson("Pass url."))
            return
        }
        val provider = infoProvider ?: run {
            respond(socket, 503, errorJson("The app is not ready."))
            return
        }
        val result = runCatching {
            runBlocking { withTimeout(120_000) { provider(url) } }
        }
        result.fold(
            onSuccess = { respond(socket, 200, it.toString()) },
            onFailure = { respond(socket, 502, errorJson(it.message ?: "That page could not be read.")) },
        )
    }

    private fun download(socket: Socket, request: BridgeRequest) {
        if (!authorized(request)) {
            respond(socket, 403, errorJson("Token is missing. Read /token first."))
            return
        }
        val url = request.arg("url")
        if (url.isNullOrBlank() || !(url.startsWith("http://") || url.startsWith("https://"))) {
            respond(socket, 400, errorJson("Pass an http(s) url."))
            return
        }
        val quality = request.arg("quality")?.trim()?.ifBlank { "best" } ?: "best"
        val handler = downloadHandler ?: run {
            respond(socket, 503, errorJson("The app is not ready."))
            return
        }
        val accepted = runCatching { handler(url, quality) }.getOrDefault(false)
        if (!accepted) {
            respond(
                socket,
                409,
                errorJson("The phone did not accept the link. Check the app and try again."),
            )
            return
        }
        respond(
            socket,
            202,
            buildJsonObject {
                put("ok", true)
                put("queued", true)
                put("message", "The phone accepted the link. The agent will download it.")
            }.toString(),
        )
    }

    private fun statusJson(): JsonObject = buildJsonObject {
        put("ok", true)
        put("app", "CEditNeuro")
        put("version", "0.99.28") // keep in step with versionName in app/build.gradle.kts
        put("port", port)
        put("capabilities", buildJsonArray {
            add(JsonPrimitive("download"))
            add(JsonPrimitive("hls"))
        })
    }

    private fun errorJson(message: String): String =
        buildJsonObject {
            put("ok", false)
            put("error", message)
        }.toString()

    private fun authorized(request: BridgeRequest): Boolean {
        val supplied = request.arg("token") ?: request.header("x-ceed-token") ?: return false
        return supplied.isNotEmpty() && constantTimeEquals(supplied, token)
    }

    /**
     * A web page must not be able to read the token through a wildcard CORS answer.
     * A browser extension and a loopback test page are allowed; any other Origin is not.
     */
    private fun originAllowed(request: BridgeRequest): Boolean {
        val origin = request.header("origin")?.trim() ?: return true
        return origin.startsWith("chrome-extension://", ignoreCase = true) ||
            origin.startsWith("moz-extension://", ignoreCase = true) ||
            origin.startsWith("safari-web-extension://", ignoreCase = true) ||
            origin.startsWith("http://127.0.0.1") ||
            origin.startsWith("http://localhost") ||
            origin.startsWith("http://[::1]")
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (index in a.indices) diff = diff or (a[index].code xor b[index].code)
        return diff == 0
    }

    private fun readRequest(socket: Socket): BridgeRequest? {
        val reader = BufferedReader(InputStreamReader(socket.getInputStream(), Charsets.ISO_8859_1))
        val line = reader.readLine() ?: return null
        val parts = line.split(' ')
        if (parts.size < 2) return null
        val method = parts[0].uppercase(Locale.US)
        val target = parts[1]
        val headers = linkedMapOf<String, String>()
        var length = 0
        var lines = 0
        while (true) {
            val header = reader.readLine() ?: break
            if (header.isEmpty()) break
            if (++lines > MAX_HEADER_LINES) return null
            val colon = header.indexOf(':')
            if (colon <= 0) continue
            val name = header.substring(0, colon).trim().lowercase(Locale.US)
            val value = header.substring(colon + 1).trim()
            headers[name] = value
            if (name == "content-length") length = value.toIntOrNull() ?: 0
        }
        val body = if (length in 1..MAX_BODY) {
            val chars = CharArray(length)
            var read = 0
            while (read < length) {
                val count = reader.read(chars, read, length - read)
                if (count < 0) break
                read += count
            }
            String(chars, 0, read)
        } else {
            ""
        }
        val query = target.substringAfter('?', "")
        val args = linkedMapOf<String, String>()
        query.split('&').forEach { pair ->
            if (pair.isBlank()) return@forEach
            val name = decode(pair.substringBefore('='))
            val value = decode(pair.substringAfter('=', ""))
            args[name] = value
        }
        if (body.isNotBlank()) {
            body.split('&').forEach { pair ->
                if (pair.isBlank() || !pair.contains('=')) return@forEach
                val name = decode(pair.substringBefore('='))
                val value = decode(pair.substringAfter('=', ""))
                if (name.isNotEmpty()) args[name] = value
            }
            runCatching {
                val json = kotlinx.serialization.json.Json.parseToJsonElement(body).jsonObject
                json.forEach { (name, element) ->
                    (element as? JsonPrimitive)?.content?.let { args[name] = it }
                }
            }
        }
        return BridgeRequest(
            method = method,
            path = target.substringBefore('?'),
            args = args,
            headers = headers,
        )
    }

    private fun decode(raw: String): String =
        runCatching { URLDecoder.decode(raw, "UTF-8") }.getOrDefault(raw)

    private fun respond(
        socket: Socket,
        code: Int,
        body: String,
        contentType: String = "application/json; charset=utf-8",
        preflight: Boolean = false,
    ) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val reason = when (code) {
            200 -> "OK"
            202 -> "Accepted"
            204 -> "No Content"
            400 -> "Bad Request"
            403 -> "Forbidden"
            404 -> "Not Found"
            409 -> "Conflict"
            500 -> "Internal Server Error"
            502 -> "Bad Gateway"
            503 -> "Service Unavailable"
            else -> "Response"
        }
        val head = buildString {
            append("HTTP/1.1 ").append(code).append(' ').append(reason).append("\r\n")
            append("Content-Type: ").append(contentType).append("\r\n")
            append("Content-Length: ").append(bytes.size).append("\r\n")
            append("Cache-Control: no-store\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            append("Access-Control-Allow-Headers: Content-Type, X-Ceed-Token\r\n")
            append("Access-Control-Max-Age: 600\r\n")
            if (preflight) append("Access-Control-Allow-Private-Network: true\r\n")
            append("Connection: close\r\n\r\n")
        }
        val output = socket.getOutputStream()
        output.write(head.toByteArray(Charsets.ISO_8859_1))
        if (bytes.isNotEmpty()) output.write(bytes)
        output.flush()
    }

    private fun newToken(): String {
        val bytes = ByteArray(16)
        SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02x".format(it) }
    }

    private data class BridgeRequest(
        val method: String,
        val path: String,
        val args: Map<String, String>,
        val headers: Map<String, String>,
    ) {
        fun arg(name: String): String? = args[name] ?: args.entries.firstOrNull {
            it.key.equals(name, ignoreCase = true)
        }?.value

        fun header(name: String): String? = headers[name.lowercase(Locale.US)]
    }
}
