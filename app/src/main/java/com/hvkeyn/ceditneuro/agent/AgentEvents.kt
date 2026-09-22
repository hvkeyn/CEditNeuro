package com.hvkeyn.ceditneuro.agent

import com.hvkeyn.ceditneuro.tools.ToolResult

/** Everything the agent loop reports back to the UI. */
sealed interface AgentEvent {
    data class AssistantText(val text: String) : AgentEvent

    data class Reasoning(val text: String) : AgentEvent

    data class ToolStarted(val name: String, val arguments: String) : AgentEvent

    data class ToolFinished(val name: String, val result: ToolResult) : AgentEvent

    /** The turn ended without further tool calls; [reason] is the backend finish reason. */
    data class TurnFinished(val reason: String?) : AgentEvent

    data class Failed(val message: String) : AgentEvent
}
