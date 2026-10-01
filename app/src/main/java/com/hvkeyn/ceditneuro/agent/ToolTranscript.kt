package com.hvkeyn.ceditneuro.agent

import com.hvkeyn.ceditneuro.shell.ShellShape
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
        val byId = HashMap<String, Pair<Set<String>, String?>>()
        val state = LinkedHashMap<String, Boolean>()
        for (message in messages) {
            if (message.role == "assistant") {
                message.toolCalls.orEmpty().forEach { call ->
                    byId[call.id] = callKeys(call.function.name, call.function.arguments) to
                        hostKey(call.function.name, call.function.arguments)
                }
            } else if (message.role == "tool") {
                val memory = byId[message.toolCallId] ?: continue
                val content = message.content.orEmpty()
                val failed = isFailedToolResult(content)
                memory.first.forEach { state[it] = failed }
                val host = memory.second
                if (host != null && failed && hostFailure(content)) state[host] = true
                else if (host != null && !failed) state[host] = false
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
            val failed = isFailedToolResult(output)
            state["run_command\n$text"] = failed
            val shape = ShellShape.shapeKey(text) ?: return@forEach
            if (failed) state[shape] = true else if (state[shape] != true) state[shape] = false
        }
        return state
    }

    fun blocked(name: String, arguments: String, failed: Set<String>): Boolean {
        if (callKeys(name, arguments).any { it in failed }) return true
        val host = hostKey(name, arguments) ?: return false
        return host in failed
    }

    /** Keys to remember after a failed call. A certificate or a 522 blocks the host, not only that path. */
    fun failureKeys(name: String, arguments: String, content: String): Set<String> {
        val keys = callKeys(name, arguments).toMutableSet()
        if (hostFailure(content)) hostKey(name, arguments)?.let { keys += it }
        return keys
    }

    fun callKeys(name: String, arguments: String): Set<String> {
        val exact = name + "\n" + arguments.trim()
        val keys = mutableSetOf(exact)
        when (name) {
            "run_command", "shizuku_exec" -> {
                val command = argumentValue(arguments, "command") ?: return keys
                if (command.isNotEmpty()) keys += "$name\n$command"
                if (name == "run_command") ShellShape.shapeKey(command)?.let { keys += it }
            }
            "http_request" -> {
                val url = argumentValue(arguments, "url") ?: return keys
                val coarse = urlIdentity(url)
                if (coarse.isNotEmpty()) keys += "$name\n$coarse"
            }
            "reference" -> {
                val source = argumentValue(arguments, "source")?.lowercase().orEmpty()
                val query = argumentValue(arguments, "query")?.trim()?.lowercase().orEmpty()
                if (query.isNotEmpty()) keys += "reference\n$source\n$query"
            }
            "research_run" -> {
                if (argumentValue(arguments, "action")?.equals("source", ignoreCase = true) != true) return keys
                val locator = argumentValue(arguments, "locator").orEmpty().trim()
                keys += "research_run\nsource\n${locator.lowercase()}"
                if (!ResearchRun.acceptableLocator(locator)) keys += "research_run\nbad-locator"
            }
        }
        return keys
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
            "chain validation" in line || "http 522" in line || "do not call the proxy" in line -> true
            "locator is a url" in line || "do not repeat the same lookup" in line -> true
            "do not use <<" in line || "do not pipe through sed" in line || "do not repeat the pipe" in line ||
                "do not repeat this wrapper" in line -> true
            else -> false
        }
    }

    internal fun hostFailure(text: String): Boolean {
        val line = text.lowercase()
        return "chain validation" in line ||
            "certificate chain was rejected" in line ||
            "http 522" in line ||
            "do not retry this host" in line ||
            "do not call the proxy" in line ||
            "was already rejected" in line
    }

    fun hostKey(name: String, arguments: String): String? {
        if (name != "http_request") return null
        val host = hostOf(argumentValue(arguments, "url") ?: return null) ?: return null
        return "http_request\nhost\n$host"
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

    private fun hostOf(url: String): String? {
        val rest = url.trim().substringAfter("://", "")
        if (rest.isEmpty()) return null
        val host = rest.substringBefore('/').substringBefore('?').substringBefore(':').lowercase()
        return host.takeIf { it.contains('.') }
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
