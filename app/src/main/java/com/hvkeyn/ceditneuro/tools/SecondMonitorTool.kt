package com.hvkeyn.ceditneuro.tools

import android.content.Context
import android.content.Intent
import com.hvkeyn.ceditneuro.ui.MonitorActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** Returns the host script, or opens the computer's page on this phone. */
class SecondMonitorTool(private val context: Context) : Tool {
    override val name = "second_monitor"
    override val description =
        "Make this phone an extra display for a computer the user owns, on the same local network. " +
            "action=script returns the whole host file for os=windows or os=linux. Write that text unchanged. " +
            "action=open loads the address the computer printed, full screen, and keeps this screen awake. " +
            "This is an extended display, not a copy of the main screen."
    override val parameters = objectSchema(
        properties = mapOf(
            "action" to stringProp("script or open."),
            "os" to stringProp("windows or linux. Required for script."),
            "url" to stringProp("Page address the computer printed. Required for open."),
            "width" to intProp("Virtual display width. Defaults to this phone's width."),
            "height" to intProp("Virtual display height. Defaults to this phone's height."),
        ),
        required = listOf("action"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        return when (args.stringArg("action")?.trim()?.lowercase()) {
            "script" -> script(args)
            "open" -> open(args)
            else -> ToolResult.error("action is script or open.")
        }
    }

    private fun script(args: JsonObject): ToolResult {
        val os = args.stringArg("os")?.trim()?.lowercase().orEmpty()
        val metrics = context.resources.displayMetrics
        val width = SecondMonitor.size(args.intArg("width") ?: metrics.widthPixels)
        val height = SecondMonitor.size(args.intArg("height") ?: metrics.heightPixels)
        val text = when (os) {
            "linux" -> SecondMonitor.linuxScript(width, height)
            "windows" -> SecondMonitor.windowsScript()
            else -> return ToolResult.error("os is windows or linux.")
        }
        return ToolResult.ok(text)
    }

    private suspend fun open(args: JsonObject): ToolResult = withContext(Dispatchers.Main) {
        val url = args.stringArg("url")?.trim().orEmpty()
        if (!SecondMonitor.acceptPage(url)) {
            return@withContext ToolResult.error("That address is not a page on the computer.")
        }
        val intent = Intent(context, MonitorActivity::class.java)
            .putExtra(MonitorActivity.EXTRA_URL, url)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }.fold(
            onSuccess = { ToolResult.ok("Opened the extra display on this phone. The screen stays awake.") },
            onFailure = { ToolResult.error("This phone could not open that page.") },
        )
    }
}
