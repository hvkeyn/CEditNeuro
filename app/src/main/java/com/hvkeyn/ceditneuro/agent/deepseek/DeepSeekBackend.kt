package com.hvkeyn.ceditneuro.agent.deepseek

import com.hvkeyn.ceditneuro.agent.AgentBackend
import com.hvkeyn.ceditneuro.agent.BackendChunk
import com.hvkeyn.ceditneuro.agent.ChatMessage
import com.hvkeyn.ceditneuro.agent.ContextBudget
import com.hvkeyn.ceditneuro.agent.FunctionCall
import com.hvkeyn.ceditneuro.agent.ToolCall
import com.hvkeyn.ceditneuro.agent.ToolTranscript
import com.hvkeyn.ceditneuro.data.AgentSettings
import com.hvkeyn.ceditneuro.data.ModelCatalog
import com.hvkeyn.ceditneuro.tools.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * OpenAI-compatible chat completions client. DeepSeek is the built-in host; any other
 * provider saved in settings uses the same SSE stream. Tool-call fragments are reassembled
 * by [ToolCallAccumulator].
 */
class DeepSeekBackend(
    private val client: OkHttpClient = defaultClient(),
    private val settingsProvider: () -> AgentSettings,
    /** Serves [ModelCatalog.WEB_PHONE_ID], which speaks the web chat instead of an OpenAI API. */
    private val web: AgentBackend? = null,
) : AgentBackend {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    override fun complete(messages: List<ChatMessage>, tools: List<Tool>): Flow<BackendChunk> = flow {
        val settings = settingsProvider()
        val model = settings.model
        val provider = settings.provider
        if (provider.id == ModelCatalog.WEB_PHONE_ID && web != null) {
            emitAll(web.complete(messages, tools))
            return@flow
        }
        if (!provider.ready) {
            throw IOException("No API key for ${provider.name}. Add one in Settings.")
        }

        val sentTools = tools.takeIf { it.isNotEmpty() && model.supportsTools }.orEmpty()
        val toolChars = sentTools.sumOf { it.name.length + it.description.length + it.parameters.toString().length + 40 }
        val safeMessages = ContextBudget.fit(
            ToolTranscript.seal(messages),
            contextTokens = model.maxContextTokens,
            reserveTokens = model.maxOutputTokens,
            fixedChars = toolChars,
        )
        val payload = buildJsonObject {
            put("model", model.name)
            put("stream", true)
            if (model.maxOutputTokens > 0) put("max_tokens", model.maxOutputTokens)
            put("messages", buildJsonArray {
                safeMessages.forEach { message ->
                    val images = message.imageDataUrls
                    if (images.isNotEmpty() && model.seesImages()) {
                        add(buildJsonObject {
                            put("role", message.role)
                            put("content", buildJsonArray {
                                add(buildJsonObject {
                                    put("type", "text")
                                    put("text", message.content.orEmpty())
                                })
                                images.forEach { url ->
                                    add(buildJsonObject {
                                        put("type", "image_url")
                                        putJsonObject("image_url") { put("url", url) }
                                    })
                                }
                            })
                        })
                    } else {
                        add(json.encodeToJsonElement(ChatMessage.serializer(), message))
                    }
                }
            })
            if (model.supportsReasoning && settings.thinkingEnabled) {
                putJsonObject("thinking") { put("type", "enabled") }
                put("reasoning_effort", settings.reasoningEffort)
            }
            if (settings.baseUrl.contains("deepseek.com", ignoreCase = true)) {
                putJsonObject("stream_options") { put("include_usage", true) }
            }
            if (sentTools.isNotEmpty()) {
                put("tools", buildJsonArray {
                    sentTools.forEach { tool ->
                        add(buildJsonObject {
                            put("type", "function")
                            putJsonObject("function") {
                                put("name", tool.name)
                                put("description", tool.description)
                                put("parameters", tool.parameters)
                            }
                        })
                    }
                })
            }
        }

        val request = Request.Builder()
            .url(chatCompletionsUrl(settings.baseUrl))
            .header("Authorization", "Bearer ${settings.apiKey.ifBlank { "unused" }}")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        val call = try {
            client.newCall(request).execute()
        } catch (error: IOException) {
            if (!provider.keyless) throw error
            throw IOException(bridgeUnreachable(settings.baseUrl, error), error)
        }
        call.use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                val hint = if (provider.keyless) bridgeHint(response.code, body) else ""
                throw IOException("${provider.name} request failed: HTTP ${response.code} ${body.take(500)}$hint")
            }

            val source = response.body?.source()
                ?: throw IOException("${settings.provider.name} returned an empty body.")

            val accumulator = ToolCallAccumulator()
            var finishReason: String? = null
            var cacheNote: String? = null

            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith(SSE_DATA_PREFIX)) continue

                val data = line.removePrefix(SSE_DATA_PREFIX).trim()
                if (data.isEmpty()) continue
                if (data == SSE_DONE) break

                val frame = runCatching { json.parseToJsonElement(data) as? JsonObject }.getOrNull()
                    ?: continue
                streamError(frame)?.let { (type, message) ->
                    val text = message.take(500).trim().trimEnd('.')
                    val hint = if (type == "login_required") " $LOGIN_HINT" else ""
                    throw IOException("${provider.name} error ($type): $text.$hint")
                }
                cacheNote = cacheNote(frame) ?: cacheNote
                val choices = frame["choices"] as? JsonArray ?: continue
                val choice = choices.firstOrNull() as? JsonObject ?: continue

                val reason = choice["finish_reason"]
                if (reason != null && reason !is JsonNull) {
                    finishReason = (reason as? JsonPrimitive)?.contentOrNull ?: finishReason
                }

                val delta = choice["delta"] as? JsonObject ?: continue

                val text = (delta["content"] as? JsonPrimitive)?.takeIf { it.isString }?.content
                if (!text.isNullOrEmpty()) emit(BackendChunk.Text(text))

                val reasoning = ((delta["reasoning_content"] ?: delta["reasoning"]) as? JsonPrimitive)
                    ?.takeIf { it.isString }?.content
                if (!reasoning.isNullOrEmpty()) emit(BackendChunk.Reasoning(reasoning))

                (delta["tool_calls"] as? JsonArray)?.forEach { element ->
                    (element as? JsonObject)?.let(accumulator::accept)
                }
            }

            if (accumulator.hasCalls()) emit(BackendChunk.ToolCalls(accumulator.build()))
            emit(BackendChunk.Finished(finishReason, cacheNote))
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        private const val SSE_DATA_PREFIX = "data:"
        private const val SSE_DONE = "[DONE]"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

        const val LOGIN_HINT = "Sign in again on the bridge computer: python -m deepseek.auth, then restart python app.py."

        /** An OpenAI-style error sent inside the stream, as the web bridge does after HTTP 200. */
        fun streamError(frame: JsonObject): Pair<String, String>? {
            val error = frame["error"] ?: return null
            val body = error as? JsonObject
            val message = (body?.get("message") as? JsonPrimitive)?.contentOrNull
                ?: (error as? JsonPrimitive)?.contentOrNull
                ?: error.toString()
            val type = (body?.get("type") as? JsonPrimitive)?.contentOrNull ?: "error"
            return type to message
        }

        fun bridgeUnreachable(apiUrl: String, error: IOException): String =
            "Cannot reach the DeepSeek web bridge at ${apiUrl.trim()} (${error.message ?: error.javaClass.simpleName}). " +
                "Start it on the computer with python app.py. For the phone, either run adb reverse tcp:8000 tcp:8000 " +
                "and keep 127.0.0.1, or start it with HOST=0.0.0.0 and put the computer's LAN address in the API URL."

        fun bridgeHint(code: Int, body: String): String = when {
            code == 503 || "login_required" in body -> " $LOGIN_HINT"
            code == 429 -> " The bridge allows RATE_LIMIT_PER_MINUTE requests a minute (30 by default); wait or raise it."
            code == 404 && "model" in body -> " The bridge knows deepseek-chat and deepseek-expert."
            else -> ""
        }

        fun cacheNote(frame: JsonObject): String? {
            val usage = frame["usage"] as? JsonObject ?: return null
            val hit = (usage["prompt_cache_hit_tokens"] as? JsonPrimitive)?.intOrNull
            val miss = (usage["prompt_cache_miss_tokens"] as? JsonPrimitive)?.intOrNull
            if (hit == null && miss == null) return null
            return "cache hit ${hit ?: 0}, miss ${miss ?: 0}"
        }

        fun chatCompletionsUrl(apiUrl: String): String {
            val base = apiUrl.trim().trimEnd('/')
            return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
        }

        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            // Streaming responses stay open for as long as the model keeps generating.
            .readTimeout(0, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
            .build()
    }
}

/** Reassembles `delta.tool_calls` fragments, which arrive indexed and split mid-string. */
private class ToolCallAccumulator {

    private val ids = mutableMapOf<Int, String>()
    private val names = mutableMapOf<Int, String>()
    private val arguments = mutableMapOf<Int, StringBuilder>()

    fun accept(delta: JsonObject) {
        val index = (delta["index"] as? JsonPrimitive)?.intOrNull ?: 0

        (delta["id"] as? JsonPrimitive)?.contentOrNull?.let { ids[index] = it }

        (delta["function"] as? JsonObject)?.let { function ->
            (function["name"] as? JsonPrimitive)?.contentOrNull?.let { names[index] = it }
            (function["arguments"] as? JsonPrimitive)?.contentOrNull?.let { fragment ->
                arguments.getOrPut(index) { StringBuilder() }.append(fragment)
            }
        }
    }

    fun hasCalls(): Boolean = ids.isNotEmpty() || names.isNotEmpty() || arguments.isNotEmpty()

    fun build(): List<ToolCall> {
        val indexes = (ids.keys + names.keys + arguments.keys).sorted()
        return indexes.map { index ->
            ToolCall(
                id = ids[index] ?: "call_$index",
                function = FunctionCall(
                    name = names[index].orEmpty(),
                    arguments = arguments[index]?.toString()?.ifBlank { "{}" } ?: "{}",
                ),
            )
        }
    }
}
