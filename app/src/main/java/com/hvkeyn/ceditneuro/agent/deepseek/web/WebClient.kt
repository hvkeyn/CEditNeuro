package com.hvkeyn.ceditneuro.agent.deepseek.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.TimeZone
import java.util.concurrent.TimeUnit

/** Thrown when chat.deepseek.com no longer accepts the saved session. */
class WebLoginRequired(message: String) : IOException(message)

/**
 * The internal chat.deepseek.com API, the same calls the website makes: create a chat
 * session, solve the proof of work, then stream one completion.
 */
class WebClient(private val http: OkHttpClient = defaultClient()) {
    private val json = Json { ignoreUnknownKeys = true }

    data class Reply(val text: String, val reasoning: String, val sessionId: String, val messageId: Long?)

    fun createSession(session: WebSession): String {
        val body = post(session, "/api/v0/chat_session/create", JsonObject(emptyMap()))
        val chat = (biz(body)["chat_session"] as? JsonObject)
            ?: throw IOException("DeepSeek web answered without a chat session.")
        return (chat["id"] as? JsonPrimitive)?.contentOrNull ?: throw IOException("DeepSeek web chat session has no id.")
    }

    fun complete(
        session: WebSession,
        prompt: String,
        sessionId: String,
        parentId: Long?,
        modelType: String?,
        thinking: Boolean,
        onDelta: (WebStream.Delta) -> Unit,
    ): Reply {
        val challenge = biz(post(session, "/api/v0/chat/create_pow_challenge", buildJsonObject { put("target_path", COMPLETION) }))["challenge"] as? JsonObject
            ?: throw IOException("DeepSeek web sent no proof-of-work challenge.")
        val pow = DeepSeekPow.header(challenge)
        val payload = buildJsonObject {
            put("chat_session_id", sessionId)
            if (parentId != null) put("parent_message_id", parentId) else put("parent_message_id", JsonNull)
            put("prompt", prompt)
            put("ref_file_ids", buildJsonArray { })
            put("thinking_enabled", thinking)
            put("search_enabled", false)
            put("action", JsonNull)
            put("preempt", false)
            if (modelType != null) put("model_type", modelType)
        }
        val request = request(session, COMPLETION, payload).newBuilder().header("x-ds-pow-response", pow).build()
        http.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) throw WebLoginRequired(LOGIN)
            if (!response.isSuccessful) throw IOException("DeepSeek web completion failed: HTTP ${response.code} ${response.body?.string().orEmpty().take(300)}")
            val source = response.body?.source() ?: throw IOException("DeepSeek web returned an empty body.")
            val type = response.header("Content-Type").orEmpty()
            if (!type.contains("event-stream")) {
                val text = source.readUtf8()
                runCatching { biz(json.parseToJsonElement(text) as JsonObject) }.onFailure { throw it }
                throw IOException("DeepSeek web completion did not stream: ${text.take(300)}")
            }
            val stream = WebStream()
            val text = StringBuilder()
            val reasoning = StringBuilder()
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                val delta = stream.feed(line) ?: continue
                if (delta.text.isEmpty() && delta.reasoning.isEmpty()) continue
                text.append(delta.text)
                reasoning.append(delta.reasoning)
                onDelta(delta)
            }
            stream.error?.let { message ->
                if (looksLikeLogin(message)) throw WebLoginRequired(LOGIN)
                throw IOException("DeepSeek web error: $message")
            }
            return Reply(text.toString(), reasoning.toString(), sessionId, stream.messageId)
        }
    }

    /** Proves the session works without opening a chat: fetch and solve one proof of work. */
    fun check(session: WebSession): String {
        val challenge = biz(post(session, "/api/v0/chat/create_pow_challenge", buildJsonObject { put("target_path", COMPLETION) }))["challenge"] as? JsonObject
            ?: throw IOException("DeepSeek web sent no proof-of-work challenge.")
        val started = System.nanoTime()
        DeepSeekPow.header(challenge)
        val ms = (System.nanoTime() - started) / 1_000_000
        return "Signed in. Proof of work solved in $ms ms."
    }

    /** Ends a chat in the web history, so agent turns do not pile up in the user's chat list. */
    fun deleteSession(session: WebSession, sessionId: String) {
        runCatching { post(session, "/api/v0/chat_session/delete", buildJsonObject { put("chat_session_id", sessionId) }) }
    }

    private fun post(session: WebSession, path: String, body: JsonObject): JsonObject {
        http.newCall(request(session, path, body)).execute().use { response ->
            val text = response.body?.string().orEmpty()
            if (response.code == 401 || response.code == 403) throw WebLoginRequired(LOGIN)
            if (!response.isSuccessful) throw IOException("DeepSeek web $path failed: HTTP ${response.code} ${text.take(300)}")
            return runCatching { json.parseToJsonElement(text) as JsonObject }.getOrNull()
                ?: throw IOException("DeepSeek web $path answered something that is not JSON (a human check?). $LOGIN")
        }
    }

    private fun biz(body: JsonObject): JsonObject {
        val code = (body["code"] as? JsonPrimitive)?.intOrNull
        if (code != 0) {
            val message = (body["msg"] as? JsonPrimitive)?.contentOrNull ?: body.toString().take(300)
            if (code == 40003 || looksLikeLogin(message)) throw WebLoginRequired(LOGIN)
            throw IOException("DeepSeek web error: $message")
        }
        val data = body["data"] as? JsonObject
        val bizCode = (data?.get("biz_code") as? JsonPrimitive)?.intOrNull
        if (bizCode != null && bizCode != 0) {
            throw IOException("DeepSeek web error: " + ((data["biz_msg"] as? JsonPrimitive)?.contentOrNull ?: "code $bizCode"))
        }
        return data?.get("biz_data") as? JsonObject ?: throw IOException("DeepSeek web answered an unexpected shape.")
    }

    private fun request(session: WebSession, path: String, body: JsonObject): Request {
        val offset = TimeZone.getDefault().getOffset(System.currentTimeMillis()) / 1000
        return Request.Builder()
            .url(BASE + path)
            .header("authorization", "Bearer ${session.token}")
            .header("accept", "*/*")
            .header("content-type", "application/json")
            .header("user-agent", session.userAgent.ifBlank { "Mozilla/5.0 (Linux; Android 14) Mobile" })
            .header("origin", BASE)
            .header("referer", "$BASE/")
            .header("cookie", session.cookies)
            .header("x-app-version", "2.0.0")
            .header("x-client-version", "2.0.0")
            .header("x-client-platform", "web")
            .header("x-client-locale", "en_US")
            .header("x-client-bundle-id", "com.deepseek.chat")
            .header("x-client-timezone-offset", offset.toString())
            .post(body.toString().toRequestBody(JSON_TYPE))
            .build()
    }

    private fun looksLikeLogin(message: String): Boolean {
        val text = message.lowercase()
        return listOf("auth", "unauthorized", "login", "token", "expired", "not signed").any { it in text }
    }

    companion object {
        const val BASE = "https://chat.deepseek.com"
        const val COMPLETION = "/api/v0/chat/completion"
        const val LOGIN = "The DeepSeek web sign-in on this phone has expired. Open Settings > Models > DeepSeek Web (on this phone) > Sign in."
        private val JSON_TYPE = "application/json".toMediaType()

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .readTimeout(300, TimeUnit.SECONDS)
            .build()
    }
}
