package com.hvkeyn.ceditneuro.tools

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File

data class TeamHelperRequest(
    val role: String,
    val task: String,
    val question: String,
    val briefPath: String,
)

data class TeamAgentUpdate(
    val role: String,
    val status: String,
    val summary: String = "",
    val startedAt: Long = 0L,
    val finishedAt: Long = 0L,
)

data class TeamUpdate(
    val task: String,
    val agents: List<TeamAgentUpdate>,
    val active: Boolean,
    val phase: String,
)

/**
 * A bounded sub-team for a hard step. The main agent is the only writer; helpers
 * read the same project and brief, work in parallel, and return evidence.
 */
class TeamTool(
    private val root: File,
    private val runHelper: suspend (TeamHelperRequest) -> String,
    private val enabled: () -> Boolean = { true },
    private val onUpdate: (TeamUpdate) -> Unit = {},
) : Tool {
    private var helped = false
    override val name = "team"
    override val description =
        "Ask a small read-only team for help on a hard step. The primary agent stays the only writer. " +
            "action=help runs 1 to 3 roles in parallel: researcher, reviewer, architect, or tester. " +
            "action=dual runs two independent solvers on the same question so their answers can be compared. " +
            "task is the real goal; question is the decision or check you need. " +
            "They share .ceditneuro/team/brief.md and write report-<role>.md. " +
            "Use when a task has independent research/review/test parts, is high-risk, or you are stuck. " +
            "Do not use for a one-file edit. action=status reads the last board."
    override val parameters = objectSchema(
        properties = mapOf(
            "action" to stringProp("help, dual, or status."),
            "task" to stringProp("The real goal."),
            "question" to stringProp("The decision, computation, or check the team should answer."),
            "roles" to stringProp("For help: researcher, reviewer, architect, tester. Maximum 3."),
        ),
        required = listOf("action"),
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        if (!enabled()) {
            return@withContext ToolResult.error("Team is off in Settings. Solve alone, or turn Team on in the Skills menu.")
        }
        when (args.stringArg("action").orEmpty().trim().lowercase()) {
            "status" -> ToolResult.ok(TeamBoard.summary(root))
            "help" -> runTeam(args, dual = false)
            "dual" -> runTeam(args, dual = true)
            else -> ToolResult.error("action is help, dual, or status.")
        }
    }

    private fun usable(role: String, text: String): Boolean {
        val value = text.trim()
        if (value.length < 80) return false
        val head = when (role) {
            "researcher" -> "FINDING"
            "architect" -> "TRADEOFF"
            "solver-a", "solver-b" -> "ANSWER"
            else -> "VERDICT"
        }
        val upper = value.uppercase()
        return head in upper && listOf("EVIDENCE", "PASS", "FAIL", "RISK", "INSUFFICIENT", "CHECK").any { it in upper }
    }

    private suspend fun runTeam(args: JsonObject, dual: Boolean): ToolResult {
        if (helped) return ToolResult.error("This run already used its one team. Use the reports in .ceditneuro/team and continue.")
        val task = args.stringArg("task").orEmpty().trim()
        val question = args.stringArg("question").orEmpty().trim()
        val roles = if (dual) listOf("solver-a", "solver-b") else TeamBoard.normalize(args.stringArg("roles"))
        val goal = task.ifBlank { question }
        val asked = question.ifBlank { task }
        if (goal.isBlank()) return ToolResult.error("team needs task: the real goal.")
        if (roles.isEmpty()) {
            return ToolResult.error("Choose 1 to 3 roles: " + TeamBoard.roles.keys.joinToString(", ") + ".")
        }
        helped = true
        TeamBoard.begin(root, goal, asked, roles)
        val agents = roles.associateWith { TeamAgentUpdate(it, "queued") }.toMutableMap()
        fun snapshot(): List<TeamAgentUpdate> = synchronized(agents) { roles.map { agents.getValue(it) } }
        fun setAgent(update: TeamAgentUpdate) {
            synchronized(agents) {
                val previous = agents[update.role]
                agents[update.role] = update.copy(
                    startedAt = update.startedAt.takeIf { it > 0 } ?: previous?.startedAt ?: 0L,
                    finishedAt = update.finishedAt,
                )
            }
            onUpdate(TeamUpdate(goal, snapshot(), true, update.role + " " + update.status))
        }
        onUpdate(TeamUpdate(goal, snapshot(), true, "brief ready"))

        val replies = coroutineScope {
            roles.map { role ->
                async {
                    setAgent(TeamAgentUpdate(role, "working", startedAt = System.currentTimeMillis()))
                    val request = TeamHelperRequest(role, goal, asked, TeamBoard.briefFile(root).absolutePath)
                    val raw = runCatching { runHelper(request) }.getOrElse { error ->
                        "Helper failed: " + (error.message ?: error::class.java.simpleName)
                    }
                    val failed = raw.startsWith("Helper failed:") || !usable(role, raw)
                    val report = TeamBoard.RoleReport(role, raw, failed)
                    TeamBoard.record(root, report)
                    val heading = when (role) {
                        "researcher" -> "FINDING"
                        "architect" -> "TRADEOFF"
                        "solver-a", "solver-b" -> "ANSWER"
                        else -> "VERDICT"
                    }
                    val at = raw.indexOf(heading, ignoreCase = true)
                    val summary = if (at >= 0) {
                        raw.substring(at).lineSequence().firstOrNull().orEmpty()
                    } else {
                        raw.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
                    }
                    setAgent(
                        TeamAgentUpdate(
                            role = role,
                            status = if (failed) "failed" else "done",
                            summary = summary.take(140),
                            finishedAt = System.currentTimeMillis(),
                        ),
                    )
                    report
                }
            }.awaitAll()
        }

        val failed = replies.count { it.failed }
        onUpdate(TeamUpdate(goal, snapshot(), false, "reports ready"))
        val body = buildString {
            append("Team of ").append(replies.size).append(" · ").append(replies.size - failed).append(" reports\n")
            append("Brief: `").append(TeamBoard.DIR).append("/brief.md`\n")
            append("Board: `").append(TeamBoard.DIR).append("/board.md`\n\n")
            replies.forEach { report ->
                append("## ").append(report.role).append(if (report.failed) " · failed" else " · done").append('\n')
                append(report.text.trim().take(2400)).append("\n\n")
            }
            append("The primary agent owns every edit. Resolve each risk or say why it does not apply.")
        }
        return if (failed == replies.size) ToolResult.error(body) else ToolResult.ok(body)
    }
}
