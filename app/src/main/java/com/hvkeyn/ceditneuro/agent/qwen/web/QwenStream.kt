package com.hvkeyn.ceditneuro.agent.qwen.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * Reads the chat.qwen.ai completion stream. Frames are OpenAI-shaped SSE: `choices[0].delta`
 * carries `content`, a think `phase`, and `status: finished`. `data: true` and `[DONE]` end the reply.
 */
class QwenStream {
    private val json = Json { ignoreUnknownKeys = true }

    var error: String? = null
        private set
    var finished: Boolean = false
        private set

    data class Delta(val text: String = "", val reasoning: String = "")

    fun feed(line: String): Delta? {
        val trimmed = line.trim()
        val payload = when {
            trimmed.startsWith("data:") -> trimmed.removePrefix("data:").trim()
            trimmed.startsWith("{") -> trimmed
            else -> return null
        }
        if (payload.isEmpty()) return null
        if (payload == "[DONE]" || payload == "true") {
            finished = true
            return null
        }
        val obj = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return null
        noteError(obj)?.let { message ->
            error = message
            finished = true
            return null
        }
        val data = obj["data"]
        if (data is JsonPrimitive && (data.contentOrNull == "true" || data.contentOrNull == "True")) {
            finished = true
            return null
        }
        if (obj.containsKey("response.stopped")) {
            finished = true
            return null
        }
        val choice = firstChoice(obj) ?: return null
        val delta = (choice["delta"] as? JsonObject) ?: (choice["message"] as? JsonObject) ?: return null
        val phase = text(delta["phase"])
        val status = text(delta["status"])
        val raw = text(delta["content"]).ifEmpty { text(delta["text"]) }
        val reasoning = text(delta["reasoning_content"])
        val thinking = phase in THINK
        if (text(choice["finish_reason"]).isNotEmpty()) finished = true
        if (status == "finished" && (phase == "answer" || (phase.isEmpty() && delta.containsKey("content")))) finished = true
        if (raw.isEmpty() && reasoning.isEmpty()) return null
        return if (thinking) Delta(reasoning = raw.ifEmpty { reasoning }) else Delta(text = raw, reasoning = reasoning)
    }

    private fun firstChoice(obj: JsonObject): JsonObject? {
        val direct = (obj["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
        if (direct != null) return direct
        val nested = obj["data"] as? JsonObject ?: return null
        return (nested["choices"] as? JsonArray)?.firstOrNull() as? JsonObject
    }

    private fun noteError(obj: JsonObject): String? {
        val ret = (obj["ret"] as? JsonArray)?.joinToString("") { text(it) }.orEmpty()
        if ("RGV587" in ret || "FAIL_SYS_USER_VALIDATE" in obj.toString()) return QwenClient.HUMAN_CHECK
        val nested = obj["data"] as? JsonObject
        val code = text(obj["code"]).ifEmpty { text(nested?.get("code")) }
        if (code.equals("RateLimited", ignoreCase = true)) return QwenClient.RATE_LIMIT
        val err = obj["error"]
        val errObject = err as? JsonObject
        val errCode = text(errObject?.get("code"))
        if (errCode.equals("RateLimited", ignoreCase = true)) return QwenClient.RATE_LIMIT
        val details = text(errObject?.get("details"))
            .ifEmpty { text(errObject?.get("message")) }
            .ifEmpty { text(nested?.get("details")) }
            .ifEmpty { text(obj["message"]) }
            .ifEmpty { if (err is JsonPrimitive) err.contentOrNull.orEmpty() else "" }
        val named = errCode.ifEmpty { code }
        if (named.isNotBlank() || details.isNotBlank()) {
            val success = obj["success"]
            val failed = success is JsonPrimitive && success.contentOrNull == "false"
            if (failed || err != null) return "Qwen web error: ${listOf(named, details).filter { it.isNotBlank() }.joinToString(" ")}"
        }
        val success = obj["success"]
        if (success is JsonPrimitive && success.contentOrNull == "false") return QwenClient.RATE_LIMIT
        return null
    }

    private fun text(element: JsonElement?): String = when (element) {
        is JsonPrimitive -> element.contentOrNull.orEmpty()
        is JsonArray -> element.joinToString("") { part ->
            when (part) {
                is JsonPrimitive -> part.contentOrNull.orEmpty()
                is JsonObject -> text(part["text"]).ifEmpty { text(part["content"]) }
                else -> ""
            }
        }
        is JsonObject -> text(element["text"]).ifEmpty { text(element["content"]) }
        else -> ""
    }

    private companion object {
        val THINK = setOf("think", "thinking", "thinking_summary")
    }
}
