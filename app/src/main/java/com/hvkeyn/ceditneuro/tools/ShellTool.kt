package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.shell.DeviceShell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * Runs a command in the project directory through [DeviceShell], the shell built
 * into this app. There is no external Termux process.
 */
class ShellTool(
    private val shell: DeviceShell,
    private val projectRoot: File,
    private val onFinished: (command: String, rendered: String) -> Unit = { _, _ -> },
) : Tool {

    override val name = "run_command"
    override val description =
        "Run a shell command in the project root with the built-in Android shell " +
            "(mksh and toybox). No separate app is required. Use it for echo, ls, mkdir, " +
            "rm, cp, mv, grep, sed, find and other toybox applets. " +
            "pkg, apt, git, kotlinc and compilers are not installed. " +
            "timeout_seconds defaults to 120."
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
        val rendered = withContext(Dispatchers.IO) {
            shell.run(command, projectRoot, timeoutSeconds).render()
        }
        onFinished(command, rendered)
        return if (rendered.startsWith("timed out")) {
            ToolResult.error(rendered)
        } else {
            ToolResult.ok(rendered)
        }
    }
}
