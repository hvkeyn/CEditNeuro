package com.hvkeyn.ceditneuro.tools

import android.content.Context
import android.content.Intent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.UUID

/**
 * Runs shell commands through Termux.
 *
 * Android gives an app no toolchain of its own: there is no compiler, no git binary and no
 * package manager inside the sandbox. So build and test runs are delegated to a Termux
 * installation via the RUN_COMMAND intent, which requires:
 *  - Termux installed,
 *  - `allow-external-apps=true` in `~/.termux/termux.properties`,
 *  - storage access for the project directory (`termux-setup-storage`).
 *
 * RUN_COMMAND is fire-and-forget, so the command redirects its output into a file inside the
 * project and the tool polls that file until the exit-code sentinel shows up.
 */
class ShellTool(
    private val context: Context,
    private val projectRoot: File,
) : Tool {

    override val name = "run_command"
    override val description =
        "Run a shell command in the project root through Termux and return its output. " +
            "Use it for builds, tests, package managers and the git CLI. If Termux is not " +
            "installed the call fails; in that case only edit files and tell the user."
    override val parameters = objectSchema(
        properties = mapOf(
            "command" to stringProp("Shell command to run in the project root."),
            "timeout_seconds" to intProp("Give up after this many seconds. Defaults to 120."),
        ),
        required = listOf("command"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val command = args.stringArg("command") ?: return ToolResult.error("Missing 'command'.")
        val timeoutSeconds = (args.intArg("timeout_seconds") ?: 120).coerceIn(5, 900)

        if (!isTermuxInstalled()) return ToolResult.error(TERMUX_MISSING)

        return withContext(Dispatchers.IO) { runCommand(command, timeoutSeconds) }
    }

    private fun isTermuxInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
        true
    }.getOrDefault(false)

    private suspend fun runCommand(command: String, timeoutSeconds: Int): ToolResult {
        val outputDir = File(projectRoot, OUTPUT_DIR).apply { mkdirs() }
        val outputFile = File(outputDir, "last-command.txt")
        outputFile.writeText("")

        val token = UUID.randomUUID().toString().replace("-", "").take(12)
        val doneMarker = "__CED_NEURO_DONE_$token"

        val quotedOutput = shellQuote(outputFile.absolutePath)
        val script = buildString {
            append("cd ").append(shellQuote(projectRoot.absolutePath)).append(" && ")
            append("{ ").append(command).append(" ; } > ").append(quotedOutput).append(" 2>&1; ")
            append("echo \"").append(doneMarker).append(" $?\" >> ").append(quotedOutput)
        }

        val intent = Intent().apply {
            setClassName(TERMUX_PACKAGE, "com.termux.app.RunCommandService")
            action = ACTION_RUN_COMMAND
            putExtra("com.termux.RUN_COMMAND_PATH", "/data/data/com.termux/files/usr/bin/sh")
            putExtra("com.termux.RUN_COMMAND_ARGUMENTS", arrayOf("-c", script))
            putExtra("com.termux.RUN_COMMAND_WORKDIR", "/data/data/com.termux/files/home")
            putExtra("com.termux.RUN_COMMAND_BACKGROUND", true)
            putExtra("com.termux.RUN_COMMAND_SESSION_ACTION", "0")
        }

        val accepted = runCatching { context.startService(intent) }.isSuccess
        if (!accepted) return ToolResult.error(TERMUX_DISABLED)

        val deadline = System.currentTimeMillis() + timeoutSeconds * 1_000L
        while (System.currentTimeMillis() < deadline) {
            delay(POLL_INTERVAL_MS)
            val text = runCatching { outputFile.readText() }.getOrDefault("")
            val markerIndex = text.indexOf(doneMarker)
            if (markerIndex >= 0) {
                val body = text.substring(0, markerIndex).trimEnd()
                val exitCode = text.substring(markerIndex + doneMarker.length)
                    .trim()
                    .substringBefore(' ')
                val rendered = if (body.isEmpty()) "(no output)" else body.take(MAX_OUTPUT_CHARS)
                return ToolResult.ok("exit=$exitCode\n$rendered")
            }
        }
        return ToolResult.error("Command timed out after ${timeoutSeconds}s.")
    }

    private fun shellQuote(value: String): String = "'" + value.replace("'", "'\\''") + "'"

    private companion object {
        const val TERMUX_PACKAGE = "com.termux"
        const val ACTION_RUN_COMMAND = "com.termux.RUN_COMMAND"
        const val OUTPUT_DIR = ".ceditneuro"
        const val POLL_INTERVAL_MS = 400L
        const val MAX_OUTPUT_CHARS = 20_000

        const val TERMUX_MISSING =
            "Termux is not installed, so no command can run on this device. File reads and " +
                "edits still work; tell the user that builds and tests need Termux."

        const val TERMUX_DISABLED =
            "Termux rejected the command. Add allow-external-apps=true to " +
                "~/.termux/termux.properties, restart Termux, and try again."
    }
}
