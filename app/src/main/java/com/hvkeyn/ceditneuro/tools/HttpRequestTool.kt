package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.net.AgentNet
import com.hvkeyn.ceditneuro.workspace.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.nio.charset.Charset

/**
 * Fetches a URL with the app's internet access. Replaces curl, which Android's
 * toybox shell does not ship.
 */
class HttpRequestTool(
    private val workspace: Workspace,
    private val net: AgentNet,
    private val allowed: () -> Boolean,
    private val onSaved: (String) -> Unit = {},
) : Tool {

    override val name = "http_request"
    override val description =
        "Fetch a URL over http or https using the app's network. Use this instead of curl or wget. " +
            "Optional save_path writes the raw bytes into the project. Text responses are returned " +
            "directly, truncated if they are long."
    override val parameters = objectSchema(
        properties = mapOf(
            "url" to stringProp("Absolute http or https URL."),
            "method" to stringProp("GET, POST, PUT, or DELETE. Defaults to GET."),
            "headers" to stringProp("Optional request headers, one 'Name: value' per line."),
            "body" to stringProp("Optional request body for POST and PUT."),
            "save_path" to stringProp("Optional path inside the project to store the response bytes."),
            "max_chars" to intProp("How much of a text response to return. Defaults to 16000."),
        ),
        required = listOf("url"),
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        if (!allowed()) return@withContext ToolResult.error("Network is disabled in Settings.")
        val url = args.stringArg("url") ?: return@withContext ToolResult.error("Missing 'url'.")
        val method = args.stringArg("method") ?: "GET"
        val savePath = args.stringArg("save_path")?.trim()?.trimStart('/')
        val maxChars = (args.intArg("max_chars") ?: 16_000).coerceIn(200, 40_000)
        val headers = runCatching { AgentNet.parseHeaders(args.stringArg("headers")) }
            .getOrElse { return@withContext ToolResult.error(it.message ?: "Bad headers.") }

        val exchange = runCatching {
            net.exchange(url, method, headers, args.stringArg("body"), AgentNet.MAX_DOWNLOAD_BYTES)
        }.getOrElse { return@withContext ToolResult.error(it.message ?: "Request failed.") }

        if (!savePath.isNullOrEmpty()) {
            val file = runCatching { workspace.resolve(savePath) }
                .getOrElse { return@withContext ToolResult.error(it.message ?: "Bad save_path.") }
            if (file.isDirectory) return@withContext ToolResult.error("$savePath is a directory.")
            file.parentFile?.mkdirs()
            file.writeBytes(exchange.body)
            onSaved(savePath)
            return@withContext ToolResult.ok(
                "HTTP ${exchange.code}\nSaved ${exchange.body.size} bytes to $savePath",
            )
        }

        val text = exchange.body.toString(Charset.forName("UTF-8"))
        val shown = if (text.length <= maxChars) text else text.take(maxChars) + "\n… truncated"
        val type = exchange.contentType.ifBlank { "unknown" }
        ToolResult.ok("HTTP ${exchange.code} ($type)\n$shown")
    }
}
