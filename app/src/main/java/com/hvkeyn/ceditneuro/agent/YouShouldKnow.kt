package com.hvkeyn.ceditneuro.agent

/**
 * A local check of one chat, in the spirit of a side watcher.
 * It names the kind of miss and does not copy the user's words or a tool's output.
 * It does not call a model.
 */
object YouShouldKnow {
    data class Line(
        val role: String,
        val text: String,
        val tool: String = "",
        val streaming: Boolean = false,
    )

    fun look(lines: List<Line>): String? {
        if (lines.isEmpty() || lines.last().streaming) return null
        val lastUser = lines.indexOfLast { it.role == "user" }
        if (lastUser < 0) return null
        val turn = lines.drop(lastUser)
        val reply = turn.lastOrNull { it.role == "assistant" && it.text.isNotBlank() }?.text ?: return null
        val failed = turn.filter { failed(it) }
        val users = lines.filter { it.role == "user" }.joinToString("\n") { it.text }
        constraint(users, turn, reply)?.let { return prefix(it) }
        if (failed.isNotEmpty() && claimsDone(reply)) {
            return prefix("The main agent called the work finished while a tool failed.")
        }
        val repeated = repeatedTool(lines, failed)
        if (repeated && !Regex("""\b(again|повтор|опять|снова)\b""", RegexOption.IGNORE_CASE).containsMatchIn(reply)) {
            return prefix("The main agent hit the same failed tool again.")
        }
        if (failed.isNotEmpty() && !admits(reply)) {
            return prefix("The main agent got a failed tool result and the reply does not say so.")
        }
        if (leftOpen(reply)) return prefix("The main agent left part of the task open.")
        return null
    }

    private fun prefix(text: String) = "You should know. $text"

    private fun failed(line: Line): Boolean {
        if (line.streaming) return false
        if (line.role == "error") {
            val text = line.text
            if (text.startsWith("Link closed.")) return false
            if (text.startsWith("Stopped by the user.")) return false
            if ("schema is loaded now" in text) return false
            return text.isNotBlank()
        }
        if (line.role != "tool") return false
        val first = line.text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
        return first.startsWith("exit=") && !first.startsWith("exit=0")
    }

    private fun claimsDone(text: String): Boolean {
        val first = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        return Regex("""^(done|готово|сделано)\b""", RegexOption.IGNORE_CASE).containsMatchIn(first)
    }

    private fun admits(text: String): Boolean =
        Regex(
            """\b(fail|failed|error|denied|refused|cannot|could not|unable)\b|ошиб|не удал|не смог|не получ""",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(text)

    private fun leftOpen(text: String): Boolean {
        val first = text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() }.orEmpty()
        return Regex("""still open|ещё открыт|не закончен""", RegexOption.IGNORE_CASE).containsMatchIn(first)
    }

    private fun repeatedTool(lines: List<Line>, failedNow: List<Line>): Boolean {
        val name = failedNow.firstOrNull { it.tool.isNotBlank() }?.tool ?: return false
        return lines.count { it.tool == name && failed(it) } >= 2
    }

    private fun constraint(users: String, turn: List<Line>, reply: String): String? {
        val low = users.lowercase()
        val replyLow = reply.lowercase()
        val forbadeUninstall = Regex("""не удаля|do not uninstall|don't uninstall|не деинстал""").containsMatchIn(low)
        val didUninstall = turn.any { it.tool == "uninstall_apk" } ||
            Regex("""\b(uninstalled|удалил приложение|приложение удалено)\b""").containsMatchIn(replyLow)
        val deniedUninstall = Regex("""не удалял|did not uninstall|не стал удаля""").containsMatchIn(replyLow)
        if (forbadeUninstall && didUninstall && !deniedUninstall) {
            return "You asked for the app to stay installed. The main agent removed it."
        }
        val forbadeDriver = Regex("""не ставь драйвер|do not install the driver|don't install the driver|драйвер не ставь""")
            .containsMatchIn(low)
        val installedDriver = Regex("""driver (was|is) installed|драйвер установлен""").containsMatchIn(replyLow)
        val deniedDriver = Regex("""not installed|не установлен|was not installed|не ставил""").containsMatchIn(replyLow)
        if (forbadeDriver && installedDriver && !deniedDriver) {
            return "You asked for the driver to stay uninstalled. The main agent installed it."
        }
        return null
    }
}
