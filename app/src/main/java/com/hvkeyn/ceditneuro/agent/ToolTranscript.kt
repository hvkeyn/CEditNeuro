package com.hvkeyn.ceditneuro.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * DeepSeek rejects a request in two shapes that both come back as HTTP 400.
 * An assistant message with `tool_calls` must be followed by a result for every id.
 * An assistant message with neither `content` nor `tool_calls` is also rejected, which
 * is what a reasoning-only turn becomes. [seal] closes both holes so the next request
 * can continue.
 *
 * [failedCalls] and [shellOutcomes] remember a command, URL, or login that already
 * failed. The loop refuses that same call instead of sending it again.
 */
object ToolTranscript {
    private const val INTERRUPTED = "Interrupted before this tool returned a result."

    fun seal(messages: List<ChatMessage>): List<ChatMessage> {
        val sealed = build(messages)
        return if (sealed == messages) messages else sealed
    }

    /**
     * Keeps the newest [limit] messages, but does not start in the middle of a tool group.
     * A group that is only a little larger than the limit stays intact.
     */
    fun trim(messages: List<ChatMessage>, limit: Int): List<ChatMessage> {
        if (messages.size <= limit) return seal(messages)
        var start = messages.size - limit
        if (messages[start].role == "tool") {
            var group = start
            while (group > 0 && messages[group - 1].role == "tool") group--
            val assistant = group - 1
            val ownsGroup = assistant >= 0 &&
                messages[assistant].role == "assistant" &&
                !messages[assistant].toolCalls.isNullOrEmpty()
            if (ownsGroup && messages.size - assistant <= limit + 8) {
                start = assistant
            } else {
                while (start < messages.size && messages[start].role == "tool") start++
            }
        }
        return seal(messages.subList(start, messages.size))
    }

    private fun build(messages: List<ChatMessage>): List<ChatMessage> {
        val result = ArrayList<ChatMessage>(messages.size)
        var index = 0
        while (index < messages.size) {
            val message = messages[index]
            val calls = message.toolCalls
            if (message.role == "assistant" && !calls.isNullOrEmpty()) {
                val normalized = normalizeCalls(calls)
                val assistant = if (normalized == calls) message else message.copy(toolCalls = normalized)
                result += assistant
                index++
                val found = LinkedHashMap<String, ChatMessage>()
                while (index < messages.size && messages[index].role == "tool") {
                    val toolMessage = messages[index]
                    val id = toolMessage.toolCallId
                    if (id != null && normalized.any { it.id == id } && id !in found) {
                        found[id] = toolMessage.withBody()
                    }
                    index++
                }
                for (call in normalized) {
                    result += found[call.id] ?: ChatMessage.tool(
                        toolCallId = call.id,
                        name = call.function.name.ifBlank { "tool" },
                        content = INTERRUPTED,
                    )
                }
                continue
            }
            if (message.role == "tool") {
                index++
                continue
            }
            if (message.role == "assistant") {
                val repaired = repairAssistant(message.copy(toolCalls = calls?.takeIf { it.isNotEmpty() }))
                if (repaired != null) result += repaired
            } else {
                result += message
            }
            index++
        }
        return result
    }

    /**
     * Keys of tool calls whose latest result failed. A later success for the same
     * command or URL drops the key, so a fixed run is allowed through.
     */
    fun failedCalls(messages: List<ChatMessage>): Set<String> {
        val byId = HashMap<String, Set<String>>()
        val state = LinkedHashMap<String, Boolean>()
        for (message in messages) {
            if (message.role == "assistant") {
                message.toolCalls.orEmpty().forEach { call ->
                    byId[call.id] = callKeys(call.function.name, call.function.arguments)
                }
            } else if (message.role == "tool") {
                val keys = byId[message.toolCallId] ?: continue
                val failed = isFailedToolResult(message.content.orEmpty())
                keys.forEach { state[it] = failed }
            }
        }
        return state.filterValues { it }.keys
    }

    /** Last outcome of each shell command, true when that last run failed. */
    fun shellOutcomes(commands: List<Pair<String, String>>): Map<String, Boolean> {
        val state = LinkedHashMap<String, Boolean>()
        commands.forEach { (command, output) ->
            val text = command.trim()
            if (text.isEmpty()) return@forEach
            state["run_command\n$text"] = isFailedToolResult(output)
        }
        return state
    }

    fun callKeys(name: String, arguments: String): Set<String> {
        val exact = name + "\n" + arguments.trim()
        val field = when (name) {
            "run_command", "shizuku_exec" -> "command"
            "http_request" -> "url"
            else -> null
        }
        val value = field?.let { argumentValue(arguments, it) } ?: return setOf(exact)
        val coarse = if (name == "http_request") urlIdentity(value) else value.trim()
        if (coarse.isEmpty()) return setOf(exact)
        return setOf(exact, "$name\n$coarse")
    }

    internal fun isFailedToolResult(text: String): Boolean {
        val line = text.trim().lowercase()
        if (line.isEmpty()) return false
        return when {
            line.startsWith("exit=") && !line.startsWith("exit=0") -> true
            line.startsWith("exit=0") && ("do not repeat this login" in line || "do not retry this host" in line) -> true
            line.startsWith("timed out") || line == "timeout" || line.startsWith("timeout\n") -> true
            line.startsWith("read timed out") || line.startsWith("java.net.sockettimeoutexception") -> true
            line.startsWith("chain validation") || line.startsWith("javax.net.ssl") -> true
            line.startsWith("this url already timed out") || line.startsWith("this exact ") ||
                line.startsWith("the certificate or proxy") -> true
            "certificate chain was rejected" in line || "was already rejected" in line -> true
            else -> false
        }
    }

    private fun repairAssistant(message: ChatMessage): ChatMessage? {
        if (!message.toolCalls.isNullOrEmpty()) return message
        if (!message.content.isNullOrBlank()) {
            return if (message.toolCalls == null) message else message.copy(toolCalls = null)
        }
        val fallback = message.reasoningContent
            ?.lineSequence()
            ?.map { it.trim() }
            ?.lastOrNull { it.isNotEmpty() }
            ?.take(180)
            ?: return null
        return message.copy(content = fallback, toolCalls = null)
    }

    private fun argumentValue(arguments: String, field: String): String? {
        val objectValue = runCatching { json.parseToJsonElement(arguments.ifBlank { "{}" }) }.getOrNull() as? JsonObject
            ?: return null
        return (objectValue[field] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
    }

    private fun urlIdentity(url: String): String {
        val noQuery = url.trim().substringBefore('?').trim()
        return noQuery.trimEnd('/')
    }

    private val json = Json { ignoreUnknownKeys = true }

    private fun normalizeCalls(calls: List<ToolCall>): List<ToolCall> {
        val seen = HashSet<String>()
        val normalized = ArrayList<ToolCall>(calls.size)
        calls.forEachIndexed { index, call ->
            val id = call.id.ifBlank { "call_missing_$index" }
            if (seen.add(id)) {
                normalized += if (id == call.id) call else call.copy(id = id)
            }
        }
        return normalized
    }

    private fun ChatMessage.withBody(): ChatMessage {
        val body = content?.takeIf { it.isNotBlank() } ?: INTERRUPTED
        return if (content == body) this else copy(content = body)
    }
}
