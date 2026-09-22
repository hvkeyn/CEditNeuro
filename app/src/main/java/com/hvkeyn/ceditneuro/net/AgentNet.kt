package com.hvkeyn.ceditneuro.net

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * The agent's internet. The app already holds the install-time INTERNET permission;
 * this client is what the tools and the shell downloader share.
 */
class AgentNet {

    private val client = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(90, TimeUnit.SECONDS)
        .callTimeout(120, TimeUnit.SECONDS)
        .build()

    fun download(url: String, dest: File, maxBytes: Long, timeoutSeconds: Long = 120) {
        val httpUrl = parseUrl(url)
        dest.parentFile?.mkdirs()
        val request = Request.Builder().url(httpUrl).header("User-Agent", USER_AGENT).build()
        val caller = client.newBuilder()
            .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
            .build()
        caller.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IOException("HTTP ${response.code} for $url")
            }
            val body = response.body ?: throw IOException("Empty response from $url")
            val length = body.contentLength()
            if (length > maxBytes) throw IOException("Response is $length bytes, limit is $maxBytes.")
            body.byteStream().use { input ->
                dest.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    var total = 0L
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > maxBytes) throw IOException("Download exceeded $maxBytes bytes.")
                        output.write(buffer, 0, read)
                    }
                }
            }
        }
    }

    fun exchange(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?,
        maxBytes: Long,
    ): HttpExchange {
        val httpUrl = parseUrl(url)
        val verb = method.trim().uppercase().ifBlank { "GET" }
        if (verb !in ALLOWED_METHODS) throw IOException("Unsupported method $verb.")
        val requestBody = when {
            verb == "GET" || verb == "HEAD" -> null
            else -> (body ?: "").toRequestBody("text/plain; charset=utf-8".toMediaType())
        }
        val builder = Request.Builder().url(httpUrl).method(verb, requestBody).header("User-Agent", USER_AGENT)
        headers.forEach { (name, value) -> builder.header(name, value) }
        client.newCall(builder.build()).execute().use { response ->
            val bytes = response.body?.bytes() ?: ByteArray(0)
            if (bytes.size > maxBytes) {
                throw IOException("Response is ${bytes.size} bytes, limit is $maxBytes.")
            }
            return HttpExchange(
                code = response.code,
                contentType = response.body?.contentType()?.toString().orEmpty(),
                body = bytes,
            )
        }
    }

    companion object {
        const val USER_AGENT = "CEditNeuro"
        const val MAX_DOWNLOAD_BYTES = 32L * 1024L * 1024L
        private val ALLOWED_METHODS = setOf("GET", "HEAD", "POST", "PUT", "DELETE")

        fun parseUrl(raw: String): okhttp3.HttpUrl {
            val trimmed = raw.trim()
            val url = trimmed.toHttpUrlOrNull()
                ?: throw IOException("Not an http(s) URL: $trimmed")
            if (url.scheme != "http" && url.scheme != "https") {
                throw IOException("Only http and https URLs are allowed.")
            }
            return url
        }

        fun parseHeaders(raw: String?): Map<String, String> {
            if (raw.isNullOrBlank()) return emptyMap()
            val headers = linkedMapOf<String, String>()
            raw.lineSequence().forEach { line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty()) return@forEach
                val colon = trimmed.indexOf(':')
                if (colon <= 0) throw IOException("Header must look like Name: value.")
                val name = trimmed.substring(0, colon).trim()
                if (name.equals("Host", ignoreCase = true) || name.equals("Content-Length", ignoreCase = true)) {
                    return@forEach
                }
                headers[name] = trimmed.substring(colon + 1).trim()
            }
            return headers
        }
    }
}

data class HttpExchange(
    val code: Int,
    val contentType: String,
    val body: ByteArray,
)
