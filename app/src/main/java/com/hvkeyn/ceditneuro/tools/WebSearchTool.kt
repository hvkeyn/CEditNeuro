package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.net.AgentNet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder

/**
 * Keyless public-web search. DuckDuckGo is tried first; Bing and Mojeek are
 * fallbacks when the HTML endpoint answers with a check page or no hits.
 */
object WebSearchPages {
    data class Hit(val title: String, val url: String, val snippet: String)

    private val duckAnchor = Regex(
        """<a\b([^>]*\bclass="result__a"[^>]*)>(.*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val duckHref = Regex("""href="([^"]+)"""", RegexOption.IGNORE_CASE)
    private val duckSnippet = Regex(
        """class="result__snippet"[^>]*>(.*?)</(?:a|td|span|div)>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val bingBlock = Regex(
        """<li\b[^>]*class="[^"]*\bb_algo\b[^"]*"[^>]*>[\s\S]*?</li>""",
        RegexOption.IGNORE_CASE,
    )
    private val bingLink = Regex(
        """<h2[^>]*>[\s\S]*?<a[^>]*href="([^"]+)"[^>]*>([\s\S]*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val bingSnippet = Regex(
        """<p[^>]*>([\s\S]*?)</p>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val mojeekLink = Regex(
        """<a\b[^>]*\bclass="[^"]*title[^"]*"[^>]*href="([^"]+)"[^>]*>([\s\S]*?)</a>""",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL),
    )
    private val tags = Regex("<[^>]+>")
    private val blocked = listOf(
        "youtube.com", "youtu.be", "googlevideo.com", "spotify.com", "soundcloud.com",
        "instagram.com", "pinterest.com", "tiktok.com",
    )

    fun isCheckPage(html: String): Boolean =
        html.contains("anomaly.js") || !html.contains("result__a")

    fun parse(html: String, limit: Int): Pair<List<Hit>, Int> = parseDuck(html, limit)

    fun parseDuck(html: String, limit: Int): Pair<List<Hit>, Int> {
        val snippets = duckSnippet.findAll(html).map { text(it.groupValues[1]) }.toList()
        val hits = ArrayList<Hit>()
        var skipped = 0
        var cursor = 0
        val seen = HashSet<String>()
        duckAnchor.findAll(html).forEach { match ->
            val blurb = snippets.getOrNull(cursor).orEmpty()
            cursor++
            if (hits.size >= limit) return@forEach
            val raw = duckHref.find(match.groupValues[1])?.groupValues?.get(1) ?: return@forEach
            addHit(match.groupValues[2], raw, blurb, hits, seen) { skipped++ }
        }
        return hits to skipped
    }

    fun parseBing(html: String, limit: Int): Pair<List<Hit>, Int> {
        val hits = ArrayList<Hit>()
        val seen = HashSet<String>()
        var skipped = 0
        bingBlock.findAll(html).forEach { block ->
            if (hits.size >= limit) return@forEach
            val match = bingLink.find(block.value) ?: return@forEach
            val blurb = bingSnippet.find(block.value)?.groupValues?.get(1).orEmpty()
            addHit(match.groupValues[2], match.groupValues[1], blurb, hits, seen) { skipped++ }
        }
        return hits to skipped
    }

    fun parseMojeek(html: String, limit: Int): Pair<List<Hit>, Int> {
        val hits = ArrayList<Hit>()
        val seen = HashSet<String>()
        var skipped = 0
        mojeekLink.findAll(html).forEach { match ->
            if (hits.size >= limit) return@forEach
            addHit(match.groupValues[2], match.groupValues[1], "", hits, seen) { skipped++ }
        }
        return hits to skipped
    }

    private fun addHit(
        rawTitle: String,
        rawUrl: String,
        rawSnippet: String,
        hits: MutableList<Hit>,
        seen: MutableSet<String>,
        skip: () -> Unit,
    ) {
        val url = cleanUrl(rawUrl) ?: return
        if (blockedHost(url)) { skip(); return }
        if (!seen.add(url)) return
        hits += Hit(text(rawTitle).ifBlank { url }, url, text(rawSnippet))
    }

    private fun cleanUrl(raw: String): String? {
        val decoded = text(raw).trim()
        val url = when {
            decoded.startsWith("https://") || decoded.startsWith("http://") -> decoded
            decoded.startsWith("//") -> "https:$decoded"
            else -> return null
        }
        val host = url.substringAfter("://").substringBefore('/').substringBefore(':').lowercase()
        if (host.isEmpty() || host.endsWith("duckduckgo.com") || host.endsWith("bing.com") ||
            host.endsWith("mojeek.com")
        ) return null
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
        "Search the public web without a key. DuckDuckGo is tried first, then Bing and Mojeek. " +
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
        val encoded = URLEncoder.encode(query.take(240), Charsets.UTF_8.name())
        val headers = mapOf(
            "User-Agent" to AgentNet.BROWSER_AGENT,
            "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
            "Accept-Language" to "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7",
            "Cache-Control" to "no-cache",
        )
        var lastError = "No engine returned a result."

        suspend fun tryEngine(
            name: String,
            url: String,
            method: String,
            body: String?,
            parse: (String, Int) -> Pair<List<WebSearchPages.Hit>, Int>,
            checkPage: Boolean,
        ): ToolResult? {
            val exchange = runCatching {
                net.exchange(url, method, headers, body, AgentNet.MAX_DOWNLOAD_BYTES)
            }.getOrElse {
                lastError = "$name: ${it.message ?: "request failed"}"
                return null
            }
            val html = exchange.body.toString(Charsets.UTF_8)
            if (exchange.code >= 400) {
                lastError = "$name: HTTP ${exchange.code}"
                return null
            }
            if (checkPage && WebSearchPages.isCheckPage(html)) {
                lastError = "$name: check page"
                return null
            }
            val (hits, skipped) = parse(html, count)
            if (hits.isEmpty()) {
                lastError = "$name: no results"
                return null
            }
            val text = buildString {
                append(name).append(", ").append(hits.size).append(" results. A result is a page to open, not a file to save.\n")
                hits.forEachIndexed { index, hit ->
                    append(index + 1).append(". ").append(hit.title).append('\n')
                    append(hit.url).append('\n')
                    if (hit.snippet.isNotEmpty()) append(hit.snippet).append('\n')
                }
                if (skipped > 0) append("Skipped $skipped results from a video or store host.\n")
            }
            return ToolResult.ok(text.trimEnd())
        }

        tryEngine(
            "DuckDuckGo HTML",
            "https://html.duckduckgo.com/html/",
            "POST",
            "q=$encoded",
            WebSearchPages::parseDuck,
            checkPage = true,
        )?.let { return@withContext it }

        tryEngine(
            "Bing HTML",
            "https://www.bing.com/search?q=$encoded&count=$count&setlang=ru&cc=RU",
            "GET",
            null,
            WebSearchPages::parseBing,
            checkPage = false,
        )?.let { return@withContext it }

        tryEngine(
            "Mojeek HTML",
            "https://www.mojeek.com/search?q=$encoded",
            "GET",
            null,
            WebSearchPages::parseMojeek,
            checkPage = false,
        )?.let { return@withContext it }

        ToolResult.error(
            "Search failed on DuckDuckGo, Bing, and Mojeek. $lastError. " +
                "Do not retry the same query; open a known catalog or page instead.",
        )
    }
}
