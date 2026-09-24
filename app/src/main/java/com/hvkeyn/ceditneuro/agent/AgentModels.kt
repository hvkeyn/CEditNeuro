package com.hvkeyn.ceditneuro.agent

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class FunctionCall(
    val name: String,
    val arguments: String = "",
)

@Serializable
data class ToolCall(
    val id: String,
    val type: String = "function",
    val function: FunctionCall,
)

/**
 * A message in the OpenAI-compatible chat format DeepSeek speaks. Null fields are dropped
 * during serialization, which matters because the API rejects explicit nulls for some roles.
 */
@Serializable
data class ChatMessage(
    val role: String,
    val content: String? = null,
    /** Echoed back on the next DeepSeek call. Dropping it makes a tool round fail. */
    @SerialName("reasoning_content") val reasoningContent: String? = null,
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null,
    /** Not sent as a string field. The backend inlines these only for vision models. */
    @kotlinx.serialization.Transient val imageDataUrls: List<String> = emptyList(),
) {
    companion object {
        fun system(text: String) = ChatMessage(role = "system", content = text)

        fun user(text: String, imageDataUrls: List<String> = emptyList()) = ChatMessage(
            role = "user",
            content = text,
            imageDataUrls = imageDataUrls,
        )

        fun assistant(
            text: String? = null,
            toolCalls: List<ToolCall>? = null,
            reasoning: String? = null,
        ) = ChatMessage(
            role = "assistant",
            content = text,
            reasoningContent = reasoning?.takeIf { it.isNotBlank() },
            toolCalls = toolCalls,
        )

        fun tool(toolCallId: String, name: String, content: String) = ChatMessage(
            role = "tool",
            content = content,
            toolCallId = toolCallId,
            name = name,
        )
    }
}
