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
            "Optional save_path writes the raw bytes into the project. A GET save can be up to 96 MB. " +
            "Text responses are returned directly, truncated if they are long. " +
            "A JSON body is sent as application/json unless content_type or a Content-Type header says otherwise."
    override val parameters = objectSchema(
        properties = mapOf(
            "url" to stringProp("Absolute http or https URL."),
            "method" to stringProp("GET, POST, PUT, or DELETE. Defaults to GET."),
            "headers" to stringProp("Optional request headers, one 'Name: value' per line."),
            "content_type" to stringProp("Body media type, for example application/json. Wins over a Content-Type header."),
            "body" to stringProp("Optional request body for POST and PUT."),
            "save_path" to stringProp("Optional path inside the project to store the response bytes."),
            "max_chars" to intProp("How much of a text response to return. Defaults to 16000."),
        ),
        required = listOf("url"),
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        if (!allowed()) return@withContext ToolResult.error("Network is disabled in Settings.")
        val url = args.stringArg("url") ?: return@withContext ToolResult.error("Missing 'url'.")
        if (url.contains("allorigins.win", ignoreCase = true) || url.contains("corsproxy.io", ignoreCase = true)) {
            return@withContext ToolResult.error(
                "That host is a browser proxy. Request the page URL directly with http_request. Do not call the proxy again.",
            )
        }
        val method = args.stringArg("method") ?: "GET"
        val savePath = args.stringArg("save_path")?.trim()?.trimStart('/')
        val maxChars = (args.intArg("max_chars") ?: 16_000).coerceIn(200, 40_000)
        val headers = runCatching { AgentNet.parseHeaders(args.stringArg("headers")) }
            .getOrElse { return@withContext ToolResult.error(it.message ?: "Bad headers.") }
            .toMutableMap()
        val body = args.stringArg("body")
        val declared = args.stringArg("content_type")?.trim()?.ifBlank { null }
        val headerType = headers.entries
            .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
            ?.value
            ?.ifBlank { null }
        val sniffed = body?.trimStart()?.let { text ->
            if (text.startsWith("{") || text.startsWith("[")) "application/json; charset=utf-8" else null
        }
        val contentType = declared ?: headerType ?: sniffed
        if (contentType != null) {
            headers.entries.removeAll { it.key.equals("Content-Type", ignoreCase = true) }
            headers["Content-Type"] = contentType
        }
        val verb = method.trim().uppercase().ifBlank { "GET" }

        if (!savePath.isNullOrEmpty() && (verb == "GET" || verb == "HEAD")) {
            val file = runCatching { workspace.resolve(savePath) }
                .getOrElse { return@withContext ToolResult.error(it.message ?: "Bad save_path.") }
            if (file.isDirectory) return@withContext ToolResult.error("$savePath is a directory.")
            file.parentFile?.mkdirs()
            runCatching { net.download(url, file, AgentNet.MAX_FILE_BYTES, 180) }
                .getOrElse { return@withContext ToolResult.error(httpFailure(it.message)) }
            onSaved(savePath)
            return@withContext ToolResult.ok("HTTP GET\nSaved ${file.length()} bytes to $savePath")
        }

        val exchange = runCatching {
            net.exchange(url, method, headers, body, AgentNet.MAX_DOWNLOAD_BYTES)
        }.getOrElse { return@withContext ToolResult.error(httpFailure(it.message)) }

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
        val refusal = com.hvkeyn.ceditneuro.shell.ShellExit.refusal(shown)
        val status = if (refusal.isEmpty()) "HTTP ${exchange.code} ($type)\n$shown" else "HTTP ${exchange.code} ($type)\n$shown\n$refusal"
        if (exchange.code == 401 || refusal.isNotEmpty()) ToolResult.error(status) else ToolResult.ok(status)
    }

    private fun httpFailure(message: String?): String {
        val detail = message ?: "Request failed."
        val refusal = com.hvkeyn.ceditneuro.shell.ShellExit.refusal(detail)
        return if (refusal.isEmpty()) detail else "$detail $refusal"
    }
}
