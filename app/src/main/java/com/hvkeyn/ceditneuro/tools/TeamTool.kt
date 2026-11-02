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

/**
 * A bounded sub-team for a hard step. The main agent is the only writer; helpers
 * read the same project and brief, work in parallel, and return evidence.
 */
class TeamTool(
    private val root: File,
    private val runHelper: suspend (TeamHelperRequest) -> String,
    private val onStatus: (String) -> Unit = {},
) : Tool {
    private var helped = false
    override val name = "team"
    override val description =
        "Ask a small read-only team for help on a hard step. The primary agent stays the only writer. " +
            "action=help runs 1 to 3 roles in parallel: researcher, reviewer, architect, or tester. " +
            "task is the real goal; question is the decision or check you need. " +
            "They share .ceditneuro/team/brief.md and write report-<role>.md. " +
            "Use when a task has independent research/review/test parts, is high-risk, or you are stuck. " +
            "Do not use for a one-file edit. action=status reads the last board."
    override val parameters = objectSchema(
        properties = mapOf(
            "action" to stringProp("help or status."),
            "task" to stringProp("The real goal, for help."),
            "question" to stringProp("The decision or check the team should answer, for help."),
            "roles" to stringProp("Comma-separated: researcher, reviewer, architect, tester. Maximum 3."),
        ),
        required = listOf("action"),
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        when (args.stringArg("action").orEmpty().trim().lowercase()) {
            "status" -> ToolResult.ok(TeamBoard.summary(root))
            "help" -> help(args)
            else -> ToolResult.error("action is help or status.")
        }
    }

    private fun usable(role: String, text: String): Boolean {
        val value = text.trim()
        if (value.length < 120) return false
        val head = when (role) {
            "researcher" -> "FINDING"
            "architect" -> "TRADEOFF"
            else -> "VERDICT"
        }
        val upper = value.uppercase()
        return head in upper && listOf("EVIDENCE", "PASS", "FAIL", "RISK", "INSUFFICIENT").any { it in upper }
    }

    private suspend fun help(args: JsonObject): ToolResult {
        if (helped) return ToolResult.error("This run already used its one team. Use the reports in .ceditneuro/team and continue.")
        val task = args.stringArg("task").orEmpty().trim()
        val question = args.stringArg("question").orEmpty().trim().ifBlank { task }
        val roles = TeamBoard.normalize(args.stringArg("roles"))
        if (task.isBlank()) return ToolResult.error("team help needs task: the real goal.")
        if (roles.isEmpty()) {
            return ToolResult.error(
                "Choose 1 to 3 roles: " + TeamBoard.roles.keys.joinToString(", ") + ".",
            )
        }
        helped = true
        TeamBoard.begin(root, task, question, roles)
        onStatus("Team · " + roles.joinToString(" + ") + " started")

        val replies = coroutineScope {
            roles.map { role ->
                async {
                    val request = TeamHelperRequest(role, task, question, TeamBoard.briefFile(root).absolutePath)
                    val text = runCatching { runHelper(request) }.getOrElse { error ->
                        "Helper failed: " + (error.message ?: error::class.java.simpleName)
                    }
                    val failed = text.startsWith("Helper failed:") || !usable(role, text)
                    TeamBoard.RoleReport(role, text, failed).also { report ->
                        TeamBoard.record(root, report)
                    }
                }
            }.awaitAll()
        }

        onStatus("Team · " + replies.count { !it.failed } + "/" + replies.size + " reports ready")
        val failed = replies.count { it.failed }
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
