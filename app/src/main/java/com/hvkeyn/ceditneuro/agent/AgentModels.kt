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
    @SerialName("tool_calls") val toolCalls: List<ToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null,
) {
    companion object {
        fun system(text: String) = ChatMessage(role = "system", content = text)

        fun user(text: String) = ChatMessage(role = "user", content = text)

        fun assistant(text: String? = null, toolCalls: List<ToolCall>? = null) =
            ChatMessage(role = "assistant", content = text, toolCalls = toolCalls)

        fun tool(toolCallId: String, name: String, content: String) = ChatMessage(
            role = "tool",
            content = content,
            toolCallId = toolCallId,
            name = name,
        )
    }
}
