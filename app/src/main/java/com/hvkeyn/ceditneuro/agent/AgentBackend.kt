package com.hvkeyn.ceditneuro.agent

import com.hvkeyn.ceditneuro.tools.Tool
import kotlinx.coroutines.flow.Flow

/** Incremental output of a single model turn. */
sealed interface BackendChunk {
    data class Text(val value: String) : BackendChunk
    data class Reasoning(val value: String) : BackendChunk
    data class ToolCalls(val calls: List<ToolCall>) : BackendChunk
    data class Finished(val finishReason: String?) : BackendChunk
}

/**
 * The model behind the agent. Keeping this an interface means the loop and the UI stay
 * usable if the provider changes (another OpenAI-compatible host, a local model, or an
 * ACP-backed agent later).
 */
interface AgentBackend {
    fun complete(messages: List<ChatMessage>, tools: List<Tool>): Flow<BackendChunk>
}
