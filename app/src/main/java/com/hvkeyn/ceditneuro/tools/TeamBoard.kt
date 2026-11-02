package com.hvkeyn.ceditneuro.tools

import java.io.File

/**
 * Shared files for one team: the brief, one report per role, and a board the main
 * agent can read. Only the main agent writes project code; helpers are read-only.
 */
object TeamBoard {
    const val DIR = ".ceditneuro/team"
    const val MAX_ROLES = 3

    val roles: Map<String, String> = linkedMapOf(
        "researcher" to "Find external facts, documentation, APIs, or examples. Cite the URL or file. Do not edit code.",
        "reviewer" to "Audit the plan, diff, or current files. List correctness risks, missing cases, and tests. Do not edit code.",
        "architect" to "Compare two designs, boundaries, or data flows. Name the trade-off and the smallest safe change. Do not edit code.",
        "tester" to "Read the tests and the changed code. Propose exact checks and expected results. Do not edit code or run mutating commands.",
    )

    data class RoleReport(val role: String, val text: String, val failed: Boolean)

    fun normalize(raw: String?): List<String> {
        val seen = LinkedHashSet<String>()
        raw.orEmpty()
            .split(',', ';', '+', ' ', '\n')
            .map { it.trim().lowercase() }
            .filter { it.isNotEmpty() }
            .forEach { name -> if (name in roles) seen += name }
        return seen.take(MAX_ROLES)
    }

    fun dir(root: File): File = File(root, DIR)

    fun briefFile(root: File): File = File(dir(root), "brief.md")

    fun boardFile(root: File): File = File(dir(root), "board.md")

    fun reportFile(root: File, role: String): File = File(dir(root), "report-$role.md")

    fun begin(root: File, task: String, question: String, chosen: List<String>): List<String> {
        val folder = dir(root)
        folder.mkdirs()
        val brief = buildString {
            append("# Team brief\n\n")
            append("Task: ").append(task.trim()).append("\n\n")
            if (question.isNotBlank() && question.trim() != task.trim()) {
                append("Question: ").append(question.trim()).append("\n\n")
            }
            append("Main editor: the primary agent. Helpers must not edit project files.\n\n")
            append("Shared files: `").append(DIR).append("/brief.md`, `board.md`, and one `report-<role>.md`.\n\n")
            append("## Roles\n")
            chosen.forEach { role ->
                append("- **").append(role).append("** — ").append(roles.getValue(role)).append('\n')
            }
            append("\n## Contract\n")
            append("- Helpers read the project and the brief. They do not write, move, or delete project files.\n")
            append("- Every finding names a path, a line, a URL, or a command result.\n")
            append("- A FAIL or risk is returned to the main agent; the main agent fixes it or states why it does not apply.\n")
            append("- A helper that cannot answer says what is missing instead of guessing.\n")
        }
        write(briefFile(root), brief)
        write(boardFile(root), boardHeader(task, chosen))
        return chosen.map { reportFile(root, it).absolutePath }
    }

    @Synchronized
    fun record(root: File, report: RoleReport): String {
        write(reportFile(root, report.role), report.text.trim().ifBlank { "No report." } + "\n")
        val line = if (report.failed) "FAILED" else "done"
        append(
            boardFile(root),
            "\n## ${report.role}\nStatus: $line\nReport: `report-${report.role}.md`\n\n" +
                report.text.trim().take(1200) + "\n",
        )
        return "report-${report.role}.md"
    }

    fun summary(root: File): String {
        val board = boardFile(root)
        if (!board.isFile) return "No team has run in this project yet. Call team action=help first."
        return board.readText().trim().take(5000)
    }

    private fun boardHeader(task: String, chosen: List<String>): String = buildString {
        append("# Team board\n\nTask: ").append(task.trim()).append("\n\n")
        append("Roles: ").append(chosen.joinToString(", ")).append("\n")
        append("Main editor only. Helpers are read-only.\n")
    }

    private fun append(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.appendText(text)
    }

    private fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(text)
        if (!temp.renameTo(file)) {
            file.writeText(text)
            temp.delete()
        }
    }
}
