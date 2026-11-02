package com.hvkeyn.ceditneuro.ui

import com.hvkeyn.ceditneuro.ui.ChatRole

enum class CompanionMood {
    OFFLINE,
    SLEEPING,
    IDLE,
    THINKING,
    WORKING,
    TEAM,
    HAPPY,
    WORRIED,
}

data class CompanionStatus(
    val mood: CompanionMood,
    val title: String,
    val message: String,
    val load: Int,
)

/**
 * The status pet is a reflection of the agent, not another agent.
 * It never claims work that is not in the chat state.
 */
fun companionStatus(
    online: Boolean,
    agentRunning: Boolean,
    sleeping: Boolean,
    team: TeamUi?,
    activity: AgentActivity?,
    lastEntry: ChatEntry?,
): CompanionStatus {
    val working = team?.agents?.count { it.status == "working" } ?: 0
    val teamTotal = team?.agents?.size ?: 0
    if (!online) {
        return CompanionStatus(CompanionMood.OFFLINE, "offline", "No network. Nib is watching the cable.", 0)
    }
    if (team != null && team.active && teamTotal > 0) {
        return CompanionStatus(
            CompanionMood.TEAM,
            "coordinating",
            "$working of $teamTotal helpers are working. Nib keeps the brief open.",
            working.coerceAtLeast(1),
        )
    }
    if (agentRunning && team != null && !team.active && teamTotal > 0) {
        return CompanionStatus(
            CompanionMood.TEAM,
            "integrating",
            "$teamTotal reports are in. Nib is waiting for the primary agent to reconcile them.",
            teamTotal.coerceAtMost(3),
        )
    }
    if (agentRunning) {
        val phase = activity?.phase.orEmpty()
        val lower = phase.lowercase()
        val thinking = lower.contains("thinking") || lower.contains("waiting") ||
            lower.contains("writing") || lower.contains("preparing")
        val load = ((activity?.context?.length ?: 0) / 40).coerceIn(1, 3)
        return if (thinking) {
            CompanionStatus(CompanionMood.THINKING, "thinking", phase.ifBlank { "Working through the request." }, load)
        } else {
            CompanionStatus(CompanionMood.WORKING, "working", phase.ifBlank { "A tool is running." }, load)
        }
    }
    if (lastEntry?.role == ChatRole.Error) {
        return CompanionStatus(CompanionMood.WORRIED, "worried", "The last run stopped. Nib is holding the error.", 0)
    }
    if (lastEntry?.role == ChatRole.Assistant) {
        if (sleeping) {
            return CompanionStatus(CompanionMood.SLEEPING, "sleeping", "The run is done. Nib curled up on the brief.", 0)
        }
        return CompanionStatus(CompanionMood.HAPPY, "content", "The last answer is in. Nib is keeping an eye on the project.", 0)
    }
    if (sleeping) {
        return CompanionStatus(CompanionMood.SLEEPING, "sleeping", "No task is running. Nib is asleep.", 0)
    }
    return CompanionStatus(CompanionMood.IDLE, "idle", "Waiting for a task. Nib is listening.", 0)
}

/** A small, repeatable set of idle gestures. */
enum class IdleAction { BLINK, LOOK_LEFT, LOOK_RIGHT, EAR_TWITCH, TAIL_SWISH }

fun idleAction(seed: Long): IdleAction {
    val values = IdleAction.entries
    return values[(seed / 1000L).mod(values.size.toLong()).toInt()]
}
