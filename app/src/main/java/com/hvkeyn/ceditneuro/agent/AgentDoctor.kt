package com.hvkeyn.ceditneuro.agent

import com.hvkeyn.ceditneuro.data.StoredSession

/**
 * Reads saved agent sessions and points at mistakes that keep coming back.
 * The report is plain text so it can be copied or sent as the next prompt.
 */
object AgentDoctor {
    private const val MAX_REPORT = 12_000

    /** The numbers harness search is allowed to use. They come only from this report. */
    data class Measure(
        val score: Double,
        val cost: Double,
        val repeated: Int,
        val once: Int,
        val summary: String,
        val stamp: String,
    )

    fun report(sessions: Map<String, StoredSession>): String {
        val collected = collect(sessions)
        val repeated = collected.findings.values.filter { it.count >= 2 }.sortedByDescending { it.count }
        val once = collected.findings.values.filter { it.count == 1 }.sortedBy { it.kind }
        return buildString {
            append("Agent doctor\n")
            append("Projects: ").append(collected.projects).append(". Messages: ").append(collected.messages).append(".\n\n")
            if (collected.findings.isEmpty()) {
                append("No failed tool calls, shell errors, or error notes in the saved sessions.\n")
            } else {
                append("Repeated\n")
                if (repeated.isEmpty()) append("None yet. One-off failures are listed below.\n")
                repeated.forEach { append(it.render()) }
                if (once.isNotEmpty()) {
                    append("\nSeen once\n")
                    once.take(8).forEach { append(it.render()) }
                }
                append("\nWhat to change\n")
                advice(collected.findings.values.map { it.kind }.toSet()).forEach { append("- ").append(it).append('\n') }
            }
            append("\nSend this report back and fix only the repeated failures. ")
            append("The score counts tool failures and the same problem showing up again in the chats and texts. ")
            append("One sentence is not a rule. Do not special-case a task, a chat, or a wording. ")
            append("Do not invent permissions Android will not grant.\n")
        }.take(MAX_REPORT)
    }

    fun measure(sessions: Map<String, StoredSession>): Measure {
        val collected = collect(sessions)
        val repeated = collected.findings.values.filter { it.count >= 2 }
        val once = collected.findings.values.filter { it.count == 1 }
        val penalty = repeated.sumOf { it.count } + once.size * 0.25
        val score = 1.0 / (1.0 + penalty)
        val summary = buildString {
            if (collected.findings.isEmpty()) {
                append("No failed tool calls, shell errors, or error notes.")
            } else {
                repeated.sortedByDescending { it.count }.forEach { append(it.render()) }
                once.sortedBy { it.kind }.take(8).forEach { append(it.render()) }
            }
        }.take(2_000)
        val stamp = summary.hashCode().toString() + ":" + repeated.sumOf { it.count } + ":" + once.size
        return Measure(
            score,
            collected.messages.toDouble().coerceAtLeast(1.0),
            repeated.sumOf { it.count },
            once.size,
            summary,
            stamp,
        )
    }

    private data class Collected(
        val findings: Map<String, Finding>,
        val messages: Int,
        val projects: Int,
    )

    private fun collect(sessions: Map<String, StoredSession>): Collected {
        val findings = mutableMapOf<String, Finding>()
        var messages = 0
        sessions.forEach { (root, session) ->
            val folder = root.substringAfterLast('/').ifBlank { root }
            val seen = mutableSetOf<String>()
            val asks = mutableListOf<String>()
            session.conversation.forEach { message ->
                messages++
                when (message.role) {
                    "tool" -> {
                        val text = message.content.orEmpty()
                        val kind = kindOf(text) ?: return@forEach
                        add(findings, kind, message.name ?: "tool", folder, text)
                    }
                    "user" -> noteUser(message.content.orEmpty(), folder, findings, seen, asks)
                    "assistant" -> noteAssistant(message.content.orEmpty(), folder, findings, seen)
                }
            }
            session.chat.forEach { entry ->
                when (entry.role) {
                    "Error" -> {
                        if (entry.text.startsWith("Link closed.")) return@forEach
                        if (entry.text.startsWith("Stopped by the user.")) return@forEach
                        if ("schema is loaded now" in entry.text) return@forEach
                        val kind = if (unanswered(entry.text)) "unanswered" else kindOf(entry.text) ?: "error"
                        val sample = if (kind == "unanswered") UNANSWERED_SAMPLE else entry.text
                        add(findings, kind, entry.toolName ?: "chat", folder, sample)
                    }
                    "User" -> noteUser(entry.text, folder, findings, seen, asks)
                    "Assistant" -> noteAssistant(entry.text, folder, findings, seen)
                }
            }
            asks.groupingBy { it }.eachCount().filter { it.value >= 2 }.forEach { (_, count) ->
                repeat(count) {
                    add(findings, "repeated ask", "chat", folder, "The same request was sent again.")
                }
            }
            session.shell.forEach { line ->
                val kind = kindOf(line.output) ?: return@forEach
                add(findings, kind, "shell", folder, line.command + "\n" + line.output)
            }
        }
        return Collected(findings, messages, sessions.size)
    }

    private fun add(
        findings: MutableMap<String, Finding>,
        kind: String,
        tool: String,
        folder: String,
        sample: String,
    ) {
        val key = "$tool|$kind"
        val current = findings[key]
        if (current == null) {
            findings[key] = Finding(kind, tool, 1, folder, sampleLine(sample))
        } else {
            findings[key] = current.copy(count = current.count + 1)
        }
    }

    private fun sampleLine(text: String): String =
        text.lineSequence()
            .map { it.trim() }
            .firstOrNull { it.isNotBlank() && !it.matches(Regex("""exit=\d+""")) }
            ?.take(160)
            ?: text.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(160)

    private fun kindOf(text: String): String? {
        val line = text.lowercase()
        if (line.isBlank() || line.startsWith("exit=0")) return null
        return when {
            "securityexception" in line -> "SecurityException"
            "permission denied" in line -> "Permission denied"
            "unable to resolve host" in line || "no address associated" in line -> "host did not resolve"
            "shizuku is not running" in line || "cannot grant itself" in line ||
                "shell access is not available" in line || "shizuku permission was not granted" in line ->
                "shell access missing"
            "ui dump was killed" in line -> "ui dump held"
            "cannot link" in line -> "binary will not start"
            "timed out" in line || line.trim() == "timeout" || line.trim().startsWith("timeout\n") ||
                "sockettimeout" in line -> "timed out"
            "tool_calls" in line && "400" in line -> "tool call rejected"
            "chain validation" in line || "certificate chain was rejected" in line -> "certificate rejected"
            "incorrect user data" in line || "do not repeat this login" in line -> "login rejected"
            line.startsWith("exit=") && !line.startsWith("exit=0") -> "command failed"
            "not a file" in line || "no such file" in line -> "missing file"
            "stopped:" in line -> "run stopped"
            else -> null
        }
    }

    private fun advice(kinds: Set<String>): List<String> {
        val lines = mutableListOf<String>()
        if ("ui dump held" in kinds) {
            lines += "Do not call fetch_system_layout again. Install with install_apk and remove with uninstall_apk. Do not tap through the app."
        }
        if ("shell access missing" in kinds || "SecurityException" in kinds) {
            lines += "Do not call shizuku_exec, fetch_system_layout, or execute_system_action again. They cannot run until the user starts Shizuku or roots the phone. Use run_command, net_info, and install_apk."
        }
        if ("host did not resolve" in kinds) {
            lines += "That hostname did not resolve. Do not fetch it again. Choose a different URL."
        }
        if ("certificate rejected" in kinds) {
            lines += "The certificate was rejected. Do not retry that host."
        }
        if ("login rejected" in kinds) {
            lines += "The server rejected the user data. Do not repeat that login."
        }
        if ("Permission denied" in kinds) {
            lines += "That path was denied. Do not repeat it. Stay on paths the tool already returned."
        }
        if ("binary will not start" in kinds) {
            lines += "A CANNOT LINK failure means the binary is broken. Do not retry screencap."
        }
        if ("missing file" in kinds) {
            lines += "If the project folder is empty, ask for the real folder instead of guessing."
        }
        if ("tool call rejected" in kinds) {
            lines += "Keep tool results paired with their tool calls. Continue instead of starting a broken transcript."
        }
        if ("timed out" in kinds || "command failed" in kinds) {
            lines += "Repeat a failed command only after changing the path, the arguments, or the tool."
        }
        if ("run stopped" in kinds) {
            lines += "A stopped run can be continued. Do not resend the same long task from the start."
        }
        if ("user correction" in kinds) {
            lines += "A repeated correction means the reply missed the request. Change the prompt or the skill for that kind of miss. Do not quote one sentence as a rule."
        }
        if ("repeated ask" in kinds) {
            lines += "The same request came back. Change the harness so the next run finishes that kind of request. Do not special-case the wording."
        }
        if ("unanswered" in kinds) {
            lines += "An empty or busy reply is a stall. Say that once and stop. Do not retry the same call."
        }
        if (lines.isEmpty()) lines += "No setup change suggested. Fix the single failure in the project that produced it."
        return lines
    }

    private fun noteUser(
        text: String,
        folder: String,
        findings: MutableMap<String, Finding>,
        seen: MutableSet<String>,
        asks: MutableList<String>,
    ) {
        val norm = text.trim().lowercase().replace(Regex("\\s+"), " ")
        if (norm.length >= 20) asks += norm
        if (!seen.add("user:$norm")) return
        if (correction(norm)) add(findings, "user correction", "chat", folder, "The user rejected the reply.")
    }

    private fun noteAssistant(
        text: String,
        folder: String,
        findings: MutableMap<String, Finding>,
        seen: MutableSet<String>,
    ) {
        val norm = text.trim().lowercase().replace(Regex("\\s+"), " ")
        if (!seen.add("assistant:$norm")) return
        if (unanswered(text)) add(findings, "unanswered", "chat", folder, UNANSWERED_SAMPLE)
    }

    private fun correction(text: String): Boolean =
        Regex(
            """^(нет|не то|не так|неправильно|ошибка|переделай|исправь|заново|опять|wrong|not that|incorrect|try again|that's wrong)\b""",
            RegexOption.IGNORE_CASE,
        ).containsMatchIn(text)

    private fun unanswered(text: String): Boolean {
        val line = text.lowercase()
        return listOf("did not answer", "overloaded", "rate-limited", "не отвечает", "перегруж").any { it in line }
    }

    private const val UNANSWERED_SAMPLE = "The reply was empty or said the model was busy."

    private data class Finding(
        val kind: String,
        val tool: String,
        val count: Int,
        val folder: String,
        val sample: String,
    ) {
        fun render(): String =
            "- $count × $tool: $kind (in $folder)\n  $sample\n"
    }
}
