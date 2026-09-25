package com.hvkeyn.ceditneuro.agent

import com.hvkeyn.ceditneuro.data.StoredSession

/**
 * Reads saved agent sessions and points at mistakes that keep coming back.
 * The report is plain text so it can be copied or sent as the next prompt.
 */
object AgentDoctor {
    private const val MAX_REPORT = 12_000

    fun report(sessions: Map<String, StoredSession>): String {
        val findings = mutableMapOf<String, Finding>()
        var messages = 0
        sessions.forEach { (root, session) ->
            val folder = root.substringAfterLast('/').ifBlank { root }
            session.conversation.forEach { message ->
                messages++
                if (message.role != "tool") return@forEach
                val text = message.content.orEmpty()
                val kind = kindOf(text) ?: return@forEach
                add(findings, kind, message.name ?: "tool", folder, text)
            }
            session.chat.forEach { entry ->
                if (entry.role != "Error") return@forEach
                val kind = kindOf(entry.text) ?: "error"
                add(findings, kind, entry.toolName ?: "chat", folder, entry.text)
            }
            session.shell.forEach { line ->
                val kind = kindOf(line.output) ?: return@forEach
                add(findings, kind, "shell", folder, line.command + "\n" + line.output)
            }
        }
        val repeated = findings.values.filter { it.count >= 2 }.sortedByDescending { it.count }
        val once = findings.values.filter { it.count == 1 }.sortedBy { it.kind }
        return buildString {
            append("Agent doctor\n")
            append("Projects: ").append(sessions.size).append(". Messages: ").append(messages).append(".\n\n")
            if (findings.isEmpty()) {
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
                advice(findings.values.map { it.kind }.toSet()).forEach { append("- ").append(it).append('\n') }
            }
            append("\nSend this report back and fix only the repeated failures. ")
            append("Do not read other apps' private files and do not invent permissions Android will not grant.\n")
        }.take(MAX_REPORT)
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
            "shizuku is not running" in line || "cannot grant itself" in line || "shell access is not available" in line ->
                "shell access missing"
            "cannot link" in line -> "binary will not start"
            "timed out" in line -> "timed out"
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
            lines += "Do not read /proc/net or another app's data. Stay on paths the tool already returned."
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
        if (lines.isEmpty()) lines += "No setup change suggested. Fix the single failure in the project that produced it."
        return lines
    }

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
