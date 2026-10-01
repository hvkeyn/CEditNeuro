package com.hvkeyn.ceditneuro.agent.qwen.web

import android.util.Log
import com.hvkeyn.ceditneuro.agent.deepseek.ModelStall
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Thrown when chat.qwen.ai no longer accepts the saved session. */
class QwenLoginRequired(message: String) : IOException(message)

/**
 * The internal chat.qwen.ai API, the same calls the website makes: create a chat, then
 * stream one completion. The body is the SPA envelope (version 2.1); a shorter body is
 * rejected by the site's check.
 */
class QwenClient(private val http: OkHttpClient = defaultClient()) {
    private val json = Json { ignoreUnknownKeys = true }

    data class Reply(val text: String, val reasoning: String, val chatId: String)

    fun createChat(session: QwenSession, model: String): String {
        val body = post(
            session,
            "/api/v2/chats/new",
            "/c/new-chat",
            buildJsonObject {
                put("chatId", "")
                put("models", buildJsonArray { add(JsonPrimitive(model)) })
                put("project_id", "")
                put("timestamp", System.currentTimeMillis())
                put("chat_type", "t2t")
                put("chat_mode", "normal")
            },
        )
        return chatId(body) ?: throw IOException("Qwen web answered without a chat id.")
    }

    fun complete(session: QwenSession, prompt: String, chatId: String, model: String): Reply {
        val payload = chatBody(chatId, model, prompt)
        val request = request(session, "/api/v2/chat/completions?chat_id=$chatId", "/c/$chatId", payload)
        http.newCall(request).execute().use { response ->
            val type = response.header("Content-Type").orEmpty()
            if (response.code == 401 || response.code == 403) throw QwenLoginRequired(LOGIN)
            val source = response.body?.source() ?: throw IOException("Qwen web returned an empty body.")
            if (!response.isSuccessful || type.contains("text/html")) {
                val text = source.readUtf8()
                if (looksLikeHumanCheck(text) || type.contains("text/html")) throw IOException(HUMAN_CHECK)
                throw IOException("Qwen web completion failed: HTTP ${response.code} ${text.take(300)}")
            }
            if (!type.contains("event-stream") && !type.contains("text/plain") && !type.contains("application/json")) {
                val text = source.readUtf8()
                if (looksLikeHumanCheck(text)) throw IOException(HUMAN_CHECK)
                throw IOException("Qwen web completion did not stream: ${text.take(300)}")
            }
            val stream = QwenStream()
            val text = StringBuilder()
            val reasoning = StringBuilder()
            val sample = StringBuilder()
            var lines = 0
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                lines++
                if (sample.length < 240 && line.isNotBlank()) {
                    sample.append(line.trim().take(80)).append('|')
                }
                val delta = stream.feed(line)
                stream.error?.let { message ->
                    if (looksLikeLogin(message)) throw QwenLoginRequired(LOGIN)
                    throw IOException(if (message.startsWith("Qwen")) message else ModelStall.userMessage(message).replace("DeepSeek", "Qwen"))
                }
                if (delta != null) {
                    text.append(delta.text)
                    reasoning.append(delta.reasoning)
                }
                if (stream.finished && text.isNotEmpty()) break
            }
            if (text.isEmpty() && reasoning.isEmpty()) {
                val preview = redact(sample.toString())
                Log.i("QwenClient", "empty type=$type lines=$lines sample=$preview")
                if (looksLikeHumanCheck(preview)) throw IOException(HUMAN_CHECK)
                throw IOException("Qwen did not answer. It may be overloaded. Try again in a moment.")
            }
            return Reply(text.toString(), reasoning.toString(), chatId)
        }
    }

    /** Proves the session works: open one chat and remove it. */
    fun check(session: QwenSession): String {
        val names = session.cookies.split(';').map { it.substringBefore('=').trim() }.filter { it.isNotEmpty() }
        Log.i("QwenClient", "check tokenLen=${session.token.length} names=$names")
        val id = createChat(session, "qwen3.8-max")
        deleteChat(session, id)
        return "Signed in. Qwen accepted the session."
    }

    fun deleteChat(session: QwenSession, chatId: String) {
        runCatching {
            post(session, "/api/v2/chats/delete", "/c/$chatId", buildJsonObject { put("chat_id", chatId) })
        }
    }

    private fun post(session: QwenSession, path: String, refererPath: String, body: JsonObject): JsonObject {
        http.newCall(request(session, path, refererPath, body)).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 401 || response.code == 403 || !response.isSuccessful) {
                Log.i("QwenClient", "http ${response.code} ${redact(text)}")
            }
            if (response.code == 401 || response.code == 403 || sessionExpired(text)) throw QwenLoginRequired(LOGIN)
            if (looksLikeHumanCheck(text) || response.header("Content-Type").orEmpty().contains("text/html")) {
                throw IOException(HUMAN_CHECK)
            }
            if (!response.isSuccessful) throw IOException("Qwen web $path failed: HTTP ${response.code} ${text.take(300)}")
            val parsed = runCatching { json.parseToJsonElement(text) as JsonObject }.getOrNull()
                ?: throw IOException("Qwen web $path answered something that is not JSON. $HUMAN_CHECK")
            val success = parsed["success"]
            if (success is JsonPrimitive && success.contentOrNull == "false") {
                val data = parsed["data"] as? JsonObject
                val code = (data?.get("code") as? JsonPrimitive)?.contentOrNull.orEmpty()
                val details = (data?.get("details") as? JsonPrimitive)?.contentOrNull.orEmpty()
                if (code.equals("RateLimited", ignoreCase = true)) throw IOException(RATE_LIMIT)
                if (sessionExpired("$code $details")) throw QwenLoginRequired(LOGIN)
                val why = listOf(code, details).filter { it.isNotBlank() }.joinToString(" ")
                throw IOException(if (why.isBlank()) "Qwen web error." else "Qwen web error: $why")
            }
            return parsed
        }
    }

    private fun request(session: QwenSession, path: String, refererPath: String, body: JsonObject): Request {
        return Request.Builder()
            .url(BASE + path)
            .header("accept", "application/json")
            .header("accept-language", "en-US,en;q=0.9")
            .header("content-type", "application/json")
            .header("user-agent", session.userAgent.ifBlank { "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 Chrome/140.0.0.0 Mobile Safari/537.36" })
            .header("sec-fetch-dest", "empty")
            .header("sec-fetch-mode", "cors")
            .header("sec-fetch-site", "same-origin")
            .header("x-accel-buffering", "no")
            .header("origin", BASE)
            .header("referer", BASE + refererPath)
            .header("cookie", cookieHeader(session))
            .header("Authorization", "Bearer ${session.token}")
            .header("source", if (session.userAgent.contains("Android") || session.userAgent.contains("iPhone")) "h5" else "web")
            .header("version", session.webVersion.ifBlank { WEB_VERSION })
            .header("timezone", timezoneHeader())
            .header("x-request-id", UUID.randomUUID().toString())
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
    }

    private fun chatId(body: JsonObject): String? {
        val data = body["data"] as? JsonObject
        return text(data?.get("id")) ?: text(body["id"]) ?: text((data?.get("chat") as? JsonObject)?.get("id"))
    }

    private fun text(element: kotlinx.serialization.json.JsonElement?): String? =
        (element as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun looksLikeLogin(message: String): Boolean {
        val text = message.lowercase()
        return listOf("auth", "unauthorized", "login", "token", "expired", "not signed").any { it in text }
    }

    companion object {
        const val BASE = "https://chat.qwen.ai"
        const val WEB_VERSION = "0.3.12"
        const val LOGIN = "The Qwen web sign-in on this phone has expired. Open Settings > Models > Qwen Web (on this phone) > Sign in."
        const val HUMAN_CHECK = "Qwen asked for a human check. Open Settings > Models > Qwen Web (on this phone) > Sign in, finish the page, then Check."
        const val RATE_LIMIT = "Qwen did not answer. It may be rate-limited. Try again in a moment."
        private val JSON_TYPE = "application/json".toMediaType()

        /** Live catalog ids. Older names still selected in Settings map onto one that exists. */
        fun canonicalModel(name: String): String = when (name) {
            "qwen3-coder-plus", "qwen3-max", "qwen-max" -> "qwen3.8-max"
            else -> name.ifBlank { "qwen3.8-max" }
        }

        fun cookieHeader(session: QwenSession): String {
            val parts = session.cookies.split(';')
                .map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("token=") }
                .toMutableList()
            if (session.token.isNotBlank()) parts.add(0, "token=${session.token}")
            return parts.joinToString("; ")
        }

        fun timezoneHeader(): String {
            return SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT'Z", Locale.US).format(Date())
        }

        /** The chat body the live Qwen page sends. A shorter body is treated as a bot. */
        fun chatBody(chatId: String, model: String, content: String): JsonObject = buildJsonObject {
            put("stream", true)
            put("version", "2.1")
            put("incremental_output", true)
            put("chatId", chatId)
            put("parentId", "")
            put("chat_id", chatId)
            put("chat_mode", "normal")
            put("model", canonicalModel(model))
            put("parent_id", JsonNull)
            put("timestamp", System.currentTimeMillis() / 1000)
            put("messages", buildJsonArray {
                add(buildJsonObject {
                    put("id", JsonNull)
                    put("fid", UUID.randomUUID().toString())
                    put("parentId", JsonNull)
                    put("childrenIds", JsonArray(emptyList()))
                    put("role", "user")
                    put("content", content)
                    put("user_action", "chat")
                    put("files", JsonArray(emptyList()))
                    put("timestamp", System.currentTimeMillis() / 1000)
                    put("models", buildJsonArray { add(JsonPrimitive(canonicalModel(model))) })
                    put("model", "")
                    put("chat_type", "t2t")
                    put("feature_config", buildJsonObject {
                        put("thinking_enabled", false)
                        put("output_schema", "phase")
                        put("research_mode", "normal")
                        put("auto_thinking", false)
                        put("thinking_mode", "Auto")
                        put("thinking_format", "summary")
                        put("auto_search", false)
                    })
                    put("extra", buildJsonObject {
                        put("meta", buildJsonObject { put("subChatType", "t2t") })
                    })
                    put("sub_chat_type", "t2t")
                    put("parent_id", JsonNull)
                })
            })
        }

        fun redact(text: String): String = text
            .replace(Regex("eyJ[A-Za-z0-9_\\-]+(?:\\.[A-Za-z0-9_\\-]+){1,2}"), "[jwt]")
            .take(180)

        fun sessionExpired(text: String): Boolean {
            val body = text.lowercase()
            return listOf("unauthorized", "token has expired", "please log in", "login required").any { it in body }
        }

        fun looksLikeHumanCheck(text: String): Boolean {
            val body = text.lowercase()
            return listOf("aliyun_waf", "baxia", "fail_sys_user_validate", "rgv587", "x5secdata", "<html").any { it in body }
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .build()
    }
}
