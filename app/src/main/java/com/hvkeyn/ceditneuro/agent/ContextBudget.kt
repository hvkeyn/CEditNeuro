package com.hvkeyn.ceditneuro.agent

/**
 * Fits a request into a small context window, such as the 64K of the DeepSeek web bridge.
 * Old tool results are cut first, then the oldest turns are dropped. System messages and the
 * newest turn always stay. A 1M model never reaches the limit, so its prefix stays cacheable.
 */
object ContextBudget {
    /** Russian text runs near 3 characters per token, English near 4. */
    const val CHARS_PER_TOKEN = 3
    const val KEEP_RECENT = 6
    const val OLD_TOOL_CHARS = 1_200
    const val DROPPED_NOTE = "Earlier messages were dropped to fit this model's context. Ask the user or reread a file if a detail is missing."

    fun chars(message: ChatMessage): Int =
        (message.content?.length ?: 0) +
            (message.reasoningContent?.length ?: 0) +
            (message.toolCalls?.sumOf { it.function.name.length + it.function.arguments.length + 24 } ?: 0) +
            16

    fun fit(messages: List<ChatMessage>, contextTokens: Int, reserveTokens: Int, fixedChars: Int): List<ChatMessage> {
        if (contextTokens <= 0) return messages
        val budget = (contextTokens - reserveTokens).toLong() * CHARS_PER_TOKEN - fixedChars
        if (messages.sumOf { chars(it).toLong() } <= budget) return messages
        if (budget <= 0) return ToolTranscript.trim(messages, 2)

        val lastUser = messages.indexOfLast { it.role == "user" }.coerceAtLeast(0)
        var cut = messages
        for (cutFrom in listOf(messages.size - KEEP_RECENT, maxOf(lastUser, messages.size - KEEP_RECENT))) {
            cut = shorten(cut, cutFrom)
            if (cut.sumOf { chars(it).toLong() } <= budget) return ToolTranscript.seal(cut)
        }

        val head = cut.takeWhile { it.role == "system" }
        val rest = cut.drop(head.size)
        val note = ChatMessage.system(DROPPED_NOTE)
        val fixed = head.sumOf { chars(it).toLong() } + chars(note)
        var limit = rest.size - 1
        while (limit > 1) {
            val tail = ToolTranscript.trim(rest, limit)
            if (fixed + tail.sumOf { chars(it).toLong() } <= budget) return head + note + tail
            limit--
        }
        return capTools(head + note + ToolTranscript.trim(rest, 2), budget)
    }

    private fun shorten(messages: List<ChatMessage>, cutFrom: Int): List<ChatMessage> =
        messages.mapIndexed { index, message ->
            val text = message.content
            if (index < cutFrom && message.role == "tool" && text != null && text.length > OLD_TOOL_CHARS + 80) {
                message.copy(content = text.take(OLD_TOOL_CHARS) + "\n[cut ${text.length - OLD_TOOL_CHARS} chars to fit the context]")
            } else if (index < cutFrom && message.reasoningContent != null) {
                message.copy(reasoningContent = null)
            } else {
                message
            }
        }

    /** The last resort when one fresh tool result alone is larger than the window. */
    private fun capTools(messages: List<ChatMessage>, budget: Long): List<ChatMessage> {
        val over = messages.sumOf { chars(it).toLong() } - budget
        if (over <= 0) return messages
        val tools = messages.filter { it.role == "tool" }
        val toolChars = tools.sumOf { (it.content?.length ?: 0).toLong() }
        if (toolChars == 0L) return messages
        val keep = ((toolChars - over).coerceAtLeast(OLD_TOOL_CHARS.toLong() * tools.size)).toDouble() / toolChars
        return messages.map { message ->
            val text = message.content
            if (message.role != "tool" || text == null) return@map message
            val size = (text.length * keep).toInt().coerceAtLeast(OLD_TOOL_CHARS)
            if (size >= text.length) message
            else message.copy(content = text.take(size) + "\n[cut ${text.length - size} chars to fit the context]")
        }
    }
}
