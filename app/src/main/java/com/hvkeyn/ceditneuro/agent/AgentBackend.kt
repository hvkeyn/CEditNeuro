package com.hvkeyn.ceditneuro.agent

import com.hvkeyn.ceditneuro.tools.Tool
import kotlinx.coroutines.flow.Flow

/** Incremental output of a single model turn. */
data class TokenUsage(
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cachedTokens: Int = 0,
    val latencyMs: Long = 0L,
) {
    val total: Int get() = promptTokens + completionTokens
    val isEmpty: Boolean get() = promptTokens == 0 && completionTokens == 0
}

sealed interface BackendChunk {
    data class Text(val value: String) : BackendChunk
    data class Reasoning(val value: String) : BackendChunk
    data class ToolCalls(val calls: List<ToolCall>) : BackendChunk
    data class Finished(
        val finishReason: String?,
        val cacheNote: String? = null,
        val usage: TokenUsage? = null,
    ) : BackendChunk
}

/**
 * The model behind the agent. Keeping this an interface means the loop and the UI stay
 * usable if the provider changes (another OpenAI-compatible host, a local model, or an
 * ACP-backed agent later).
 */
interface AgentBackend {
    fun complete(messages: List<ChatMessage>, tools: List<Tool>): Flow<BackendChunk>
}
