package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.shizuku.ShizukuCommandRunner
import kotlinx.serialization.json.JsonObject

/**
 * Dumps the foreground window hierarchy and returns that XML as tool context.
 * Needs the same shell access as [ShizukuExecTool]. It does not read another app's private files.
 */
class FetchSystemLayoutTool(
    private val runner: ShizukuCommandRunner,
    private val prepare: suspend () -> String?,
) : Tool {
    override val name = "fetch_system_layout"
    override val description =
        "Dump the current foreground UI hierarchy and return its XML. " +
            "Call this before tapping, so coordinates come from the layout. " +
            "Requires Shizuku or root, same as shizuku_exec. " +
            "Does not read another app's private data or credentials."
    override val parameters = objectSchema(properties = emptyMap<String, JsonObject>())

    override suspend fun execute(args: JsonObject): ToolResult {
        val blocked = prepare()
        if (blocked != null) return ToolResult.error(blocked)

        val dumped = runCatching {
            runner.executeShizukuCommand("uiautomator dump /data/local/tmp/state.xml", timeoutSeconds = 45)
        }.getOrElse { error -> return ToolResult.error(error.message ?: "UI dump failed.") }
        if (shellFailed(dumped)) return ToolResult.error(dumped)

        val rawState = runCatching {
            runner.executeShizukuCommand("cat /data/local/tmp/state.xml", timeoutSeconds = 15)
        }.getOrElse { error -> return ToolResult.error(error.message ?: "Could not read the UI dump.") }
        if (shellFailed(rawState)) return ToolResult.error(rawState)

        val layout = shellPayload(rawState)
        if (!layout.contains("<hierarchy") && !layout.contains("<?xml")) {
            return ToolResult.error("UI dump was empty.\n$rawState")
        }
        return ToolResult.ok(layout)
    }
}
