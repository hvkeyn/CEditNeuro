package com.hvkeyn.ceditneuro.tools

import kotlinx.coroutines.CancellationException
import kotlinx.serialization.json.JsonObject

/** Opens the in-app browser and returns the text a person would see. */
class BrowsePageTool(
    private val networkAllowed: () -> Boolean,
    private val browse: suspend (String, Int) -> String,
) : Tool {
    override val name = "browse_page"
    override val description =
        "Open a page in the in-app browser and read its visible text. JavaScript runs. " +
            "Use this to check a site after uploading or editing it. Pass an absolute http(s) URL. " +
            "timeout_sec is how long to wait for a heavy page, from 5 to 90, default 25."
    override val parameters = objectSchema(
        properties = mapOf(
            "url" to stringProp("Absolute http or https URL to open."),
            "timeout_sec" to intProp("Seconds to wait for the page. Default 25, maximum 90."),
        ),
        required = listOf("url"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val url = args.stringArg("url")?.trim().orEmpty()
        if (url.isEmpty()) return ToolResult.error("Missing 'url'.")
        if (!networkAllowed()) return ToolResult.error("Agent network is off. Turn on Agent network in Settings.")
        val timeoutSec = args.intArg("timeout_sec") ?: 25
        return try {
            ToolResult.ok(browse(url, timeoutSec))
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ToolResult.error(error.message ?: "The browser could not open the page.")
        }
    }
}
