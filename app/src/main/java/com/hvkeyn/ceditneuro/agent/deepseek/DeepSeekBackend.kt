package com.hvkeyn.ceditneuro.agent.deepseek

import com.hvkeyn.ceditneuro.agent.AgentBackend
import com.hvkeyn.ceditneuro.agent.BackendChunk
import com.hvkeyn.ceditneuro.agent.ChatMessage
import com.hvkeyn.ceditneuro.agent.FunctionCall
import com.hvkeyn.ceditneuro.agent.ToolCall
import com.hvkeyn.ceditneuro.agent.ToolTranscript
import com.hvkeyn.ceditneuro.data.AgentSettings
import com.hvkeyn.ceditneuro.tools.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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
) : AgentBackend {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
        encodeDefaults = true
    }

    override fun complete(messages: List<ChatMessage>, tools: List<Tool>): Flow<BackendChunk> = flow {
        val settings = settingsProvider()
        val model = settings.model
        if (settings.apiKey.isBlank()) {
            throw IOException("No API key for ${settings.provider.name}. Add one in Settings.")
        }

        val safeMessages = ToolTranscript.seal(messages)
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
            if (tools.isNotEmpty() && model.supportsTools) {
                put("tools", buildJsonArray {
                    tools.forEach { tool ->
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
            .header("Authorization", "Bearer ${settings.apiKey}")
            .header("Content-Type", "application/json")
            .header("Accept", "text/event-stream")
            .post(payload.toString().toRequestBody(JSON_MEDIA_TYPE))
            .build()

        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val body = response.body?.string().orEmpty()
                throw IOException("${settings.provider.name} request failed: HTTP ${response.code} ${body.take(500)}")
            }

            val source = response.body?.source()
                ?: throw IOException("${settings.provider.name} returned an empty body.")

            val accumulator = ToolCallAccumulator()
            var finishReason: String? = null

            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                if (!line.startsWith(SSE_DATA_PREFIX)) continue

                val data = line.removePrefix(SSE_DATA_PREFIX).trim()
                if (data.isEmpty()) continue
                if (data == SSE_DONE) break

                val frame = runCatching { json.parseToJsonElement(data) as? JsonObject }.getOrNull()
                    ?: continue
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
            emit(BackendChunk.Finished(finishReason))
        }
    }.flowOn(Dispatchers.IO)

    companion object {
        private const val SSE_DATA_PREFIX = "data:"
        private const val SSE_DONE = "[DONE]"

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

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
