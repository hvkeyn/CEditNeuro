package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.shell.DeviceShell
import com.hvkeyn.ceditneuro.shell.ProgramRun
import com.hvkeyn.ceditneuro.workspace.Workspace
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
    private val workspace: Workspace,
    private val ensureExec: suspend (String) -> Boolean = { true },
    private val onFinished: (command: String, rendered: String) -> Unit = { _, _ -> },
) : Tool {

    override val name = "run_command"
    override val description =
        "Run a shell command as this app. Toybox commands (echo, ls, mkdir, rm, cp, mv, grep, sed, find) " +
            "run directly. java, javac, and kotlinc run after install_jdk. " +
            "cwd defaults to the project and may be an absolute directory the app can use. " +
            "There is no pkg, apt, or root. git and python run after install_runtime. " +
            "gradle, aapt2, and d8 run after install_android_sdk. Use timeout_seconds of 600 for gradle. " +
            "stderr is included after a --- stderr --- line. " +
            "`fetch URL DEST` as the whole command downloads a file. " +
            "grep with no matches is success. Do not run logcat. " +
            "timeout_seconds defaults to 120; use 300 or more for kotlinc."
    override val parameters = objectSchema(
        properties = mapOf(
            "command" to stringProp("Shell command."),
            "cwd" to stringProp("Directory to run in. Project-relative or absolute. Defaults to the project root."),
            "timeout_seconds" to intProp("Give up after this many seconds. Defaults to 120."),
        ),
        required = listOf("command"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val command = args.stringArg("command") ?: return ToolResult.error("Missing 'command'.")
        val timeoutSeconds = (args.intArg("timeout_seconds") ?: 120).coerceIn(5, 900)
        val cwd = runCatching {
            val requested = args.stringArg("cwd")?.trim().orEmpty()
            if (requested.isEmpty()) workspace.root else workspace.resolve(requested)
        }.getOrElse { return ToolResult.error(it.message ?: "Bad cwd.") }
        if (!cwd.isDirectory) return ToolResult.error("cwd is not a directory: ${cwd.path}")
        if (ProgramRun.needsConsent(command) && !ensureExec("The command runs a program outside the system shell.")) {
            val denied = "exit=1\nThe user did not allow running installed programs."
            onFinished(command, denied)
            return ToolResult.error(denied)
        }
        val raw = withContext(Dispatchers.IO) {
            shell.run(command, cwd, timeoutSeconds).render()
        }
        val failed = raw.startsWith("exit=") && !raw.startsWith("exit=0")
        val noted = if (failed && "Do not repeat" !in raw) {
            raw + "\nDo not repeat this exact command. Change the path, the arguments, or the tool."
        } else {
            raw
        }
        val rendered = spill(noted)
        onFinished(command, rendered)
        return if (failed || rendered.startsWith("timed out")) {
            ToolResult.error(rendered)
        } else {
            ToolResult.ok(rendered)
        }
    }

    /** Keeps the full output on disk and sends the model a short tail. */
    private fun spill(rendered: String): String {
        if (rendered.length <= INLINE_CHARS) return rendered
        val dir = File(workspace.root, ".ceditneuro/tool-output").apply { mkdirs() }
        val file = File(dir, "cmd-${System.currentTimeMillis()}.txt")
        file.writeText(rendered)
        val tail = rendered.takeLast(TAIL_CHARS)
        return "Full output is ${rendered.length} chars at ${file.absolutePath}.\n" +
            "Read that file for the rest.\n--- tail ---\n$tail"
    }

    private companion object {
        const val INLINE_CHARS = 8_000
        const val TAIL_CHARS = 2_500
    }
}
