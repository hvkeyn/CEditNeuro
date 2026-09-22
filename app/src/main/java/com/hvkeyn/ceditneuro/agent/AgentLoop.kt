package com.hvkeyn.ceditneuro.agent

import com.hvkeyn.ceditneuro.tools.ToolRegistry
import com.hvkeyn.ceditneuro.tools.ToolResult
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * The agentic loop: ask the model, run whatever tools it requests, feed the results back,
 * and repeat until it answers without calling a tool.
 *
 * @param maxToolRounds guards against a model that keeps calling tools forever.
 */
class AgentLoop(
    private val backend: AgentBackend,
    private val toolRegistry: ToolRegistry,
    private val systemPrompt: String,
    private val maxToolRounds: Int = 24,
) {

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun run(history: List<ChatMessage>, userRequest: String): Flow<AgentEvent> = flow {
        val messages = mutableListOf<ChatMessage>()
        messages += ChatMessage.system(systemPrompt)
        messages += history
        messages += ChatMessage.user(userRequest)

        repeat(maxToolRounds) {
            val assistantText = StringBuilder()
            var pendingCalls: List<ToolCall> = emptyList()

            backend.complete(messages, toolRegistry.all).collect { chunk ->
                when (chunk) {
                    is BackendChunk.Text -> {
                        assistantText.append(chunk.value)
                        emit(AgentEvent.AssistantText(chunk.value))
                    }

                    is BackendChunk.Reasoning -> emit(AgentEvent.Reasoning(chunk.value))

                    is BackendChunk.ToolCalls -> pendingCalls = chunk.calls

                    is BackendChunk.Finished -> Unit
                }
            }

            messages += ChatMessage.assistant(
                text = assistantText.toString().ifBlank { null },
                toolCalls = pendingCalls.ifEmpty { null },
            )

            if (pendingCalls.isEmpty()) {
                emit(AgentEvent.TurnFinished("stop"))
                return@flow
            }

            for (call in pendingCalls) {
                emit(AgentEvent.ToolStarted(call.function.name, call.function.arguments))
                val result = executeTool(call)
                emit(AgentEvent.ToolFinished(call.function.name, result))
                messages += ChatMessage.tool(call.id, call.function.name, result.content)
            }
        }

        emit(AgentEvent.TurnFinished("max_tool_rounds"))
    }

    private suspend fun executeTool(call: ToolCall): ToolResult {
        val tool = toolRegistry.find(call.function.name)
            ?: return ToolResult.error(
                "Unknown tool '${call.function.name}'. Available tools: " +
                    toolRegistry.all.joinToString { it.name },
            )

        val rawArguments = call.function.arguments.ifBlank { "{}" }
        val args = runCatching { json.parseToJsonElement(rawArguments) }.getOrNull() as? JsonObject
            ?: return ToolResult.error(
                "Tool arguments must be a JSON object, got: ${rawArguments.take(200)}",
            )

        return runCatching { tool.execute(args) }
            .getOrElse { error ->
                ToolResult.error("Tool '${call.function.name}' failed: ${error.message ?: error::class.java.simpleName}")
            }
    }
}
