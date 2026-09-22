package com.hvkeyn.ceditneuro.tools

import kotlinx.serialization.json.JsonObject

/**
 * A capability the agent can invoke. Implementations live in this package and are wired
 * together by [ToolRegistry]; the agent loop only sees this interface.
 */
interface Tool {
    /** Stable identifier the model calls. Must be unique inside a registry. */
    val name: String

    /** Shown to the model; this is prompt text, so keep it precise. */
    val description: String

    /** JSON Schema for [execute]'s argument object. */
    val parameters: JsonObject

    suspend fun execute(args: JsonObject): ToolResult
}

data class ToolResult(
    val content: String,
    val isError: Boolean = false,
) {
    companion object {
        fun ok(content: String) = ToolResult(content)
        fun error(message: String) = ToolResult(message, isError = true)
    }
}
