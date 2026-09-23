package com.hvkeyn.ceditneuro.agent

import com.hvkeyn.ceditneuro.tools.ToolRegistry
import com.hvkeyn.ceditneuro.tools.ToolResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.IOException

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
    private val maxToolRounds: Int = 40,
) {
    private companion object {
        const val MAX_LENGTH_CONTINUES = 4
        const val MAX_EMPTY_CONTINUES = 1
        const val MAX_NET_RETRIES = 2
    }

    private val json = Json {
        ignoreUnknownKeys = true
        explicitNulls = false
    }

    fun run(history: List<ChatMessage>, userRequest: String): Flow<AgentEvent> = flow {
        val messages = mutableListOf<ChatMessage>()
        messages += ChatMessage.system(systemPrompt)
        messages += history
        messages += ChatMessage.user(userRequest)

        var lengthContinues = 0
        var emptyContinues = 0
        var round = 0

        suspend fun remember() {
            emit(AgentEvent.Context(messages.filter { it.role != "system" }))
        }

        while (round < maxToolRounds) {
            val assistantText = StringBuilder()
            val reasoning = StringBuilder()
            var pendingCalls: List<ToolCall> = emptyList()
            var finishReason: String? = null
            emit(activity(messages, round))

            var attempt = 0
            var received = false
            while (true) {
                try {
                    backend.complete(messages, toolRegistry.all).collect { chunk ->
                        when (chunk) {
                            is BackendChunk.Text -> {
                                received = true
                                assistantText.append(chunk.value)
                                emit(AgentEvent.AssistantText(chunk.value))
                            }

                            is BackendChunk.Reasoning -> {
                                received = true
                                reasoning.append(chunk.value)
                                emit(AgentEvent.Reasoning(chunk.value))
                            }

                            is BackendChunk.ToolCalls -> {
                                received = true
                                pendingCalls = chunk.calls
                            }

                            is BackendChunk.Finished -> finishReason = chunk.finishReason
                        }
                    }
                    break
                } catch (error: IOException) {
                    if (!retryable(error) || received || attempt >= MAX_NET_RETRIES) {
                        if (received) {
                            finishReason = "connection"
                            break
                        }
                        throw error
                    }
                    attempt++
                    emit(activity(messages, round, "Connection dropped, retry $attempt"))
                    delay(700L * attempt)
                }
            }

            val assistantMessage = ChatMessage.assistant(
                text = assistantText.toString().ifBlank { null },
                toolCalls = pendingCalls.ifEmpty { null },
                reasoning = reasoning.toString(),
            )
            val hasBody = assistantMessage.content != null ||
                assistantMessage.toolCalls != null ||
                assistantMessage.reasoningContent != null
            if (hasBody) messages += assistantMessage
            remember()

            val cutOff = finishReason == "length" || finishReason == "connection"
            if (pendingCalls.isEmpty()) {
                if (cutOff && lengthContinues < MAX_LENGTH_CONTINUES) {
                    lengthContinues++
                    val why = if (finishReason == "length") "Output limit" else "Connection dropped"
                    emit(activity(messages, round, "$why, continuing"))
                    messages += ChatMessage.user(
                        "The previous reply was cut off. Continue from that exact point. Do not repeat finished steps.",
                    )
                    remember()
                    round++
                    continue
                }
                val usedATool = messages.any { it.role == "tool" }
                if (assistantText.isBlank() && usedATool && emptyContinues < MAX_EMPTY_CONTINUES && !cutOff) {
                    emptyContinues++
                    emit(activity(messages, round, "No summary yet, continuing"))
                    messages += ChatMessage.user(
                        "Continue. If the task is finished, write the short summary. If not, take the next step.",
                    )
                    remember()
                    round++
                    continue
                }
                val reason = when {
                    finishReason == "length" -> "length"
                    finishReason == "connection" -> "connection"
                    assistantText.isBlank() && !usedATool -> "empty"
                    else -> "stop"
                }
                remember()
                emit(AgentEvent.TurnFinished(reason))
                return@flow
            }

            for (call in pendingCalls) {
                emit(AgentEvent.ToolStarted(call.function.name, call.function.arguments))
                val result = executeTool(call)
                emit(AgentEvent.ToolFinished(call.function.name, result))
                messages += ChatMessage.tool(call.id, call.function.name, result.content)
                remember()
            }
            round++
        }

        remember()
        emit(AgentEvent.TurnFinished("max_tool_rounds"))
    }

    private fun activity(messages: List<ChatMessage>, round: Int, phase: String? = null): AgentEvent.Activity {
        val chars = messages.sumOf { message ->
            (message.content?.length ?: 0) +
                (message.toolCalls?.sumOf { call ->
                    call.function.name.length + call.function.arguments.length
                } ?: 0)
        }
        val label = phase ?: if (round == 0) {
            "Waiting for the model"
        } else {
            "Waiting for the model · step ${round + 1}"
        }
        return AgentEvent.Activity(label, messages.size, chars)
    }

    private fun retryable(error: IOException): Boolean {
        val text = error.message.orEmpty()
        if (text.contains("No API key")) return false
        return !text.contains("HTTP 400") &&
            !text.contains("HTTP 401") &&
            !text.contains("HTTP 403") &&
            !text.contains("HTTP 404")
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
