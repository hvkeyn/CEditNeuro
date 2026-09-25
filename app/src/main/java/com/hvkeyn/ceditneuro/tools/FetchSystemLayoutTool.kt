package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.shizuku.ShizukuCommandRunner
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * Dumps the foreground window and returns a short tap list, not the raw hierarchy.
 * The dump file is removed after it is read. Needs the same shell access as [ShizukuExecTool].
 */
class FetchSystemLayoutTool(
    private val runner: ShizukuCommandRunner,
    private val prepare: suspend () -> String?,
    private val dumpFile: File,
) : Tool {
    override val name = "fetch_system_layout"
    override val description =
        "Read the foreground screen and return a short list of rows. " +
            "Each row has tap=X,Y at the center of [left,top,right,bottom], the visible label, and click when it can be tapped. " +
            "Pass that X and Y to execute_system_action. Empty layouts and spacers are already removed. " +
            "Requires Shizuku or root, same as shizuku_exec. Does not return another app's private files or typed passwords."
    override val parameters = objectSchema(properties = emptyMap<String, JsonObject>())

    override suspend fun execute(args: JsonObject): ToolResult {
        val blocked = prepare()
        if (blocked != null) return ToolResult.error(blocked)

        val remote = "/data/local/tmp/ceditneuro-state.xml"
        val local = dumpFile.absolutePath
        dumpFile.parentFile?.mkdirs()
        dumpFile.delete()
        val dumped = runCatching {
            runner.executeShizukuCommand(
                "uiautomator dump --compressed ${quote(remote)} && cp ${quote(remote)} ${quote(local)} && rm -f ${quote(remote)}",
                timeoutSeconds = 45,
            )
        }.getOrElse { error ->
            dumpFile.delete()
            return ToolResult.error(error.message ?: "UI dump failed.")
        }
        if (shellFailed(dumped)) {
            dumpFile.delete()
            val detail = shellPayload(dumped)
            if (detail.contains("Killed") || dumped.contains("exit=137")) {
                return ToolResult.error(
                    "UI dump was killed before it connected. Another automation session already holds UiAutomation. " +
                        "Do not call fetch_system_layout or execute_system_action again. " +
                        "Install with install_apk and remove with uninstall_apk. Do not tap through the app.",
                )
            }
            return ToolResult.error(detail.ifBlank { dumped })
        }
        if (!dumpFile.isFile || dumpFile.length() <= 0L) {
            return ToolResult.error("UI dump was not written.\n${shellPayload(dumped)}")
        }
        val xml = runCatching { dumpFile.readText().take(MAX_XML_CHARS) }
            .getOrElse { error ->
                dumpFile.delete()
                return ToolResult.error(error.message ?: "Could not read the UI dump.")
            }
        dumpFile.delete()
        if (!xml.contains("<hierarchy") && !xml.contains("<node")) {
            return ToolResult.error("UI dump was empty.")
        }
        val report = UiLayoutCompactor.compact(xml)
        if (report.kept == 0) return ToolResult.error("UI dump had no visible controls.\n${report.text}")
        return ToolResult.ok(report.text)
    }

    private fun quote(path: String): String = "'" + path.replace("'", "'\\''") + "'"

    private companion object {
        const val MAX_XML_CHARS = 1_500_000
    }
}
