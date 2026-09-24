package com.hvkeyn.ceditneuro.agent

/** Two bars for the status shade: overall turn, and the current step. */
data class AgentBars(
    val overall: Int,
    val step: Int,
    val stepIndeterminate: Boolean,
)

fun agentBars(phase: String): AgentBars {
    val text = phase.lowercase()
    val round = STEP.find(text)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    val waiting = "waiting" in text || "preparing" in text || "thinking" in text
    val overall = when {
        "writing" in text -> 92
        "failed" in text -> 100
        "preparing" in text -> 8
        "waiting" in text -> (18 + round * 8).coerceAtMost(60)
        "thinking" in text -> (32 + round * 8).coerceAtMost(74)
        "done" in text || text.startsWith("+") -> (70 + round * 4).coerceAtMost(96)
        else -> (48 + round * 6).coerceAtMost(88)
    }
    val step = when {
        waiting -> 40
        "failed" in text || "done" in text || "writing" in text -> 100
        else -> 66
    }
    return AgentBars(overall = overall, step = step, stepIndeterminate = waiting)
}

private val STEP = Regex("""step\s+(\d+)""")
