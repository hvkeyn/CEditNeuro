package com.hvkeyn.ceditneuro.agent.deepseek.web

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Reads the chat.deepseek.com completion stream. A first frame is a snapshot of the
 * response with its fragments; later frames append to a path such as
 * `response/fragments/-1/content`, or bare `{"v":"text"}` to the last path. Only RESPONSE
 * fragments are the answer; THINK fragments are reasoning.
 */
class WebStream {
    private val json = Json { ignoreUnknownKeys = true }
    private val contentPath = Regex("^response/fragments/(-?\\d+)/content$")
    private val types = mutableListOf<String?>()
    private val emitted = HashMap<Int, Int>()
    private var active: Int? = null

    var messageId: Long? = null
        private set
    var error: String? = null
        private set

    data class Delta(val text: String = "", val reasoning: String = "")

    fun feed(line: String): Delta? {
        if (!line.startsWith("data:")) return null
        val payload = line.removePrefix("data:").trim()
        if (payload.isEmpty() || payload == "[DONE]") return null
        val obj = runCatching { json.parseToJsonElement(payload) as? JsonObject }.getOrNull() ?: return null
        val code = (obj["code"] as? JsonPrimitive)?.intOrNull
        if (code != null && code != 0) {
            error = (obj["msg"] as? JsonPrimitive)?.contentOrNull ?: obj.toString().take(300)
            return null
        }
        val v = obj["v"]
        if (v is JsonObject && v["response"] is JsonObject) {
            val response = v["response"] as JsonObject
            captureId(response) ?: captureId(v)
            val fragments = (response["fragments"] as? JsonArray).orEmpty().filterIsInstance<JsonObject>()
            types.clear()
            fragments.forEach { types += type(it) }
            val out = Delta()
            return fragments.foldIndexed(out) { index, acc, fragment ->
                active = index
                acc + claim(index, fragment)
            }
        }
        val path = (obj["p"] as? JsonPrimitive)?.contentOrNull
        val op = (obj["o"] as? JsonPrimitive)?.contentOrNull
        if (path == "response/fragments" && op == "APPEND") {
            var out = Delta()
            fragmentList(v).forEach { fragment ->
                val index = types.size
                types += type(fragment)
                active = index
                out += claim(index, fragment)
            }
            return out
        }
        if (path != null) {
            val match = contentPath.find(path)
            if (match != null) {
                val raw = match.groupValues[1].toInt()
                active = if (raw == -1) types.size - 1 else raw
            }
            if (path.endsWith("message_id")) (v as? JsonPrimitive)?.longOrNull?.let { messageId = it }
            val text = (v as? JsonPrimitive)?.takeIf { it.isString }?.content
            if (op == "APPEND" && text != null && match != null) return append(text)
            return null
        }
        val text = (v as? JsonPrimitive)?.takeIf { it.isString }?.content ?: return null
        return append(text)
    }

    private fun append(text: String): Delta? {
        val index = active ?: return null
        if (index !in types.indices) return null
        emitted[index] = (emitted[index] ?: 0) + text.length
        return when (types[index]) {
            "RESPONSE" -> Delta(text = text)
            "THINK" -> Delta(reasoning = text)
            else -> null
        }
    }

    private fun claim(index: Int, fragment: JsonObject): Delta {
        val content = (fragment["content"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val seen = emitted[index] ?: 0
        if (content.length <= seen) return Delta()
        emitted[index] = content.length
        val fresh = content.substring(seen)
        return when (type(fragment)) {
            "RESPONSE" -> Delta(text = fresh)
            "THINK" -> Delta(reasoning = fresh)
            else -> Delta()
        }
    }

    private fun type(fragment: JsonObject) = (fragment["type"] as? JsonPrimitive)?.contentOrNull

    private fun fragmentList(v: JsonElement?): List<JsonObject> = when (v) {
        is JsonObject -> listOf(v)
        is JsonArray -> v.filterIsInstance<JsonObject>()
        is JsonPrimitive -> runCatching { fragmentList(json.parseToJsonElement(v.content)) }.getOrDefault(emptyList())
        else -> emptyList()
    }

    private fun captureId(container: JsonObject): Long? {
        val id = ((container["message_id"] ?: container["id"]) as? JsonPrimitive)?.longOrNull ?: return null
        messageId = id
        return id
    }

    private operator fun Delta.plus(other: Delta?) =
        if (other == null) this else Delta(text + other.text, reasoning + other.reasoning)
}
