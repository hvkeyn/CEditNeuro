package com.hvkeyn.ceditneuro.agent

/**
 * DeepSeek rejects a request when an assistant message carries `tool_calls` and the
 * following messages do not answer every `tool_call_id`. That hole shows up after a stop
 * mid-tool, a crashed run, or a history trim that slices a tool group, and then every
 * later message fails with HTTP 400.
 *
 * [seal] closes those holes. Completed tool results stay. Missing ones become a short
 * interrupted result so the next request is valid and the model can continue.
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
            if (message.role == "assistant" && calls != null) {
                val stripped = message.copy(toolCalls = null)
                if (stripped.content != null || stripped.reasoningContent != null) result += stripped
            } else {
                result += message
            }
            index++
        }
        return result
    }

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
