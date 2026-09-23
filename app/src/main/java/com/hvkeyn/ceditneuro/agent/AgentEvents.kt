package com.hvkeyn.ceditneuro.agent

import com.hvkeyn.ceditneuro.tools.ToolResult

/** Everything the agent loop reports back to the UI. */
sealed interface AgentEvent {
    data class AssistantText(val text: String) : AgentEvent

    data class Reasoning(val text: String) : AgentEvent

    /** One line of progress so a slow request does not look frozen. */
    data class Activity(val phase: String, val messages: Int, val chars: Int) : AgentEvent

    data class ToolStarted(val name: String, val arguments: String) : AgentEvent

    data class ToolFinished(val name: String, val result: ToolResult) : AgentEvent

    /**
     * The messages the model should see if the user continues this run.
     * Includes tool calls and tool results, not only the final summary.
     */
    data class Context(val messages: List<ChatMessage>) : AgentEvent

    /** The turn ended without further tool calls; [reason] is the backend finish reason. */
    data class TurnFinished(val reason: String?) : AgentEvent

    data class Failed(val message: String) : AgentEvent
}
