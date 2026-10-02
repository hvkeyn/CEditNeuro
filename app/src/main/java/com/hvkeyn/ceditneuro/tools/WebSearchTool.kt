package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.net.AgentNet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder

/**
 * Keyless web search through DuckDuckGo's HTML results page.
 * A hit is a page to open. It is not a file and not a license.
 */
object WebSearchPages {
    data class Hit(val title: String, val url: String, val snippet: String)

    private val anchor = Regex(
        """<a\b([^>]*\bclass="result__a"[^>]*)>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val href = Regex("""href="([^"]+)"""", RegexOption.IGNORE_CASE)
    private val snippet = Regex(
        """class="result__snippet"[^>]*>(.*?)</(?:a|td|span|div)>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val tags = Regex("<[^>]+>")
    private val blocked = listOf(
        "youtube.com",
        "youtu.be",
        "googlevideo.com",
        "spotify.com",
        "soundcloud.com",
        "instagram.com",
        "pinterest.com",
        "tiktok.com",
    )

    fun isCheckPage(html: String): Boolean =
        html.contains("anomaly.js") || !html.contains("result__a")

    fun parse(html: String, limit: Int): Pair<List<Hit>, Int> {
        val snippets = snippet.findAll(html).map { text(it.groupValues[1]) }.toList()
        val hits = ArrayList<Hit>()
        var skipped = 0
        var cursor = 0
        val seen = HashSet<String>()
        anchor.findAll(html).forEach { match ->
            val blurb = snippets.getOrNull(cursor).orEmpty()
            cursor++
            if (hits.size >= limit) return@forEach
            val raw = href.find(match.groupValues[1])?.groupValues?.get(1) ?: return@forEach
            val url = cleanUrl(raw) ?: return@forEach
            if (blockedHost(url)) {
                skipped++
                return@forEach
            }
            if (!seen.add(url)) return@forEach
            val title = text(match.groupValues[2]).ifBlank { url }
            hits += Hit(title, url, blurb)
        }
        return hits to skipped
    }

    private fun cleanUrl(raw: String): String? {
        val decoded = text(raw).trim()
        val url = when {
            decoded.startsWith("https://") || decoded.startsWith("http://") -> decoded
            decoded.startsWith("//") -> "https:$decoded"
            else -> return null
        }
        val host = url.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
        if (host.isEmpty() || host.endsWith("duckduckgo.com")) return null
        return url
    }

    private fun blockedHost(url: String): Boolean {
        val host = url.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
            .removePrefix("www.")
        return blocked.any { host == it || host.endsWith(".$it") }
    }

    private fun text(raw: String): String {
        var value = tags.replace(raw, "")
        value = value.replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
        value = Regex("&#(\\d+);").replace(value) { match ->
            match.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: match.value
        }
        return value.replace(Regex("\\s+"), " ").trim()
    }
}

class WebSearchTool(
    private val net: AgentNet,
    private val allowed: () -> Boolean,
) : Tool {
    override val name = "web_search"
    override val description =
        "Search the public web through DuckDuckGo's HTML results page. No key. " +
            "query is plain words. Returns titles and page URLs to open with http_request. " +
            "A result is not a file and not a license. Do not save a media file from a result until that page prints one."
    override val parameters = objectSchema(
        properties = mapOf(
            "query" to stringProp("Plain search words."),
            "count" to intProp("How many results to return, 1 to 8. Default 5."),
        ),
        required = listOf("query"),
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        if (!allowed()) return@withContext ToolResult.error("Network is disabled in Settings.")
        val query = args.stringArg("query")?.trim().orEmpty()
        if (query.isEmpty()) return@withContext ToolResult.error("Missing 'query'.")
        val count = (args.intArg("count") ?: 5).coerceIn(1, 8)
        val body = "q=" + URLEncoder.encode(query.take(240), Charsets.UTF_8.name())
        val exchange = runCatching {
            net.exchange(
                URL,
                "POST",
                mapOf("Content-Type" to "application/x-www-form-urlencoded"),
                body,
                AgentNet.MAX_DOWNLOAD_BYTES,
            )
        }.getOrElse { return@withContext ToolResult.error(it.message ?: "Search failed.") }
        val html = exchange.body.toString(Charsets.UTF_8)
        if (exchange.code >= 400 || WebSearchPages.isCheckPage(html)) {
            return@withContext ToolResult.error(
                "DuckDuckGo returned a check page. Do not retry web_search. Open a catalog page instead.",
            )
        }
        val (hits, skipped) = WebSearchPages.parse(html, count)
        if (hits.isEmpty()) {
            return@withContext ToolResult.error("No results. Try fewer words. Do not retry the same query.")
        }
        val text = buildString {
            append("DuckDuckGo HTML, ${hits.size} results. A result is a page to open, not a file to save.\n")
            hits.forEachIndexed { index, hit ->
                append(index + 1).append(". ").append(hit.title).append('\n')
                append(hit.url).append('\n')
                if (hit.snippet.isNotEmpty()) append(hit.snippet).append('\n')
            }
            if (skipped > 0) append("Skipped $skipped results from a video or store host.\n")
        }
        ToolResult.ok(text.trimEnd())
    }

    private companion object {
        const val URL = "https://html.duckduckgo.com/html/"
    }
}
