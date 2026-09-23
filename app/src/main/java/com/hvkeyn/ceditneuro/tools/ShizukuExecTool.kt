package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.shizuku.ShizukuShell
import com.hvkeyn.ceditneuro.workspace.Workspace
import kotlinx.serialization.json.JsonObject

/**
 * One command through Shizuku, which runs as the Android shell user when the user
 * has started Shizuku and allowed this app. That is UID 2000, not root.
 */
class ShizukuExecTool(
    private val workspace: Workspace,
    private val shell: ShizukuShell,
    private val prepare: suspend () -> String?,
) : Tool {
    override val name = "shizuku_exec"
    override val description =
        "Run one command as the Android shell user via Shizuku. Use it for dumpsys, logcat, " +
            "ps, screencap, input, settings, ip, and ss. The user must start Shizuku and allow this app. " +
            "Do not use it to install or launch an APK; call install_apk. " +
            "This is not root, and it cannot read this app's private toolchain. " +
            "java, git, and python stay on run_command."
    override val parameters = objectSchema(
        properties = mapOf(
            "command" to stringProp("One shell command."),
            "cwd" to stringProp("Working directory. Defaults to the project. Shared storage works; app-private files may not."),
            "timeout_seconds" to intProp("Give up after this many seconds. Defaults to 30."),
        ),
        required = listOf("command"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val command = args.stringArg("command")?.trim().orEmpty()
        if (command.isEmpty()) return ToolResult.error("Missing 'command'.")
        val blocked = prepare()
        if (blocked != null) return ToolResult.error(blocked)
        val timeout = (args.intArg("timeout_seconds") ?: 30).coerceIn(5, 180)
        val cwd = runCatching {
            val requested = args.stringArg("cwd")?.trim().orEmpty()
            val dir = if (requested.isEmpty()) workspace.root else workspace.resolve(requested)
            if (!dir.isDirectory) error("Not a directory: ${dir.path}")
            dir.path
        }.getOrElse { error -> return ToolResult.error(error.message ?: "Invalid cwd.") }

        val text = runCatching { shell.exec(command, cwd, timeout) }
            .getOrElse { error -> return ToolResult.error(error.message ?: "Shizuku command failed.") }
        val failed = text.startsWith("exit=") && !text.startsWith("exit=0")
        return if (failed) ToolResult.error(text) else ToolResult.ok(text)
    }
}
