package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.agent.MathEval
import com.hvkeyn.ceditneuro.net.AgentNet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import java.net.URLEncoder

class CalculateTool : Tool {
    override val name = "calculate"
    override val description =
        "Evaluate arithmetic exactly on the phone: + - * / % ^, parentheses, pi, e, " +
            "sqrt, abs, sin, cos, tan, asin, acos, atan, ln, log, log2, exp, floor, ceil, round, min, max, deg, rad. " +
            "Angles are radians. Use this for any number you report."
    override val parameters = objectSchema(
        properties = mapOf("expression" to stringProp("For example (2.5^2 + 1) / sqrt(3).")),
        required = listOf("expression"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val expression = args.stringArg("expression")?.trim().orEmpty()
        if (expression.isEmpty()) return ToolResult.error("expression is required.")
        return runCatching { MathEval.eval(expression) }
            .fold(
                onSuccess = { ToolResult.ok("$expression = ${MathEval.format(it)}") },
                onFailure = { ToolResult.error("${it.message} Fix the expression; do not repeat it unchanged.") },
            )
    }
}

/** Short summaries from Wikipedia, arXiv, and Crossref. Each answer stays under a few hundred words. */
class ReferenceTool(
    private val net: AgentNet,
    private val networkAllowed: () -> Boolean,
) : Tool {
    override val name = "reference"
    override val description =
        "Look up reference material without loading a whole page. " +
            "source wiki returns the summary of one Wikipedia article (lang ru or en). " +
            "source arxiv returns up to 5 papers with id, year, title, and a short abstract. " +
            "source doi checks one DOI in Crossref and returns its title, year, and venue. " +
            "Cite only what this tool returned."
    override val parameters = objectSchema(
        properties = mapOf(
            "source" to stringProp("wiki, arxiv, or doi."),
            "query" to stringProp("Article title, search words, or the DOI."),
            "lang" to stringProp("Wikipedia language, ru or en. Default ru."),
        ),
        required = listOf("source", "query"),
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        if (!networkAllowed()) return@withContext ToolResult.error("Agent network is off in Settings.")
        val source = args.stringArg("source")?.trim()?.lowercase().orEmpty()
        val query = args.stringArg("query")?.trim().orEmpty()
        if (query.length < 2) return@withContext ToolResult.error("query is required.")
        runCatching {
            when (source) {
                "wiki" -> wiki(query, args.stringArg("lang")?.trim()?.lowercase()?.takeIf { it == "en" } ?: "ru")
                "arxiv" -> arxiv(query)
                "doi" -> doi(query)
                else -> return@withContext ToolResult.error("source is wiki, arxiv, or doi.")
            }
        }.fold(
            onSuccess = { ToolResult.ok(it) },
            onFailure = { ToolResult.error("${it.message ?: "Lookup failed."} Do not repeat the same lookup.") },
        )
    }

    private fun get(url: String): String {
        val response = net.exchange(url, "GET", mapOf("Accept" to "application/json, application/atom+xml"), null, MAX_BYTES)
        if (response.code == 404) throw java.io.IOException("Not found.")
        if (response.code !in 200..299) throw java.io.IOException("HTTP ${response.code}.")
        return response.body.toString(Charsets.UTF_8)
    }

    private fun wiki(title: String, lang: String): String {
        val search = json.parseToJsonElement(
            get("https://$lang.wikipedia.org/w/api.php?action=opensearch&limit=1&namespace=0&format=json&search=${enc(title)}"),
        ) as JsonArray
        val found = (search.getOrNull(1) as? JsonArray)?.firstOrNull()?.let { (it as JsonPrimitive).content }
            ?: throw java.io.IOException("No Wikipedia article for that title.")
        val summary = json.parseToJsonElement(
            get("https://$lang.wikipedia.org/api/rest_v1/page/summary/${enc(found.replace(' ', '_'))}"),
        ).jsonObject
        val extract = summary.str("extract").take(1_500)
        val url = summary["content_urls"]?.jsonObject?.get("desktop")?.jsonObject?.str("page").orEmpty()
        return "$found\n$extract\n$url".trim()
    }

    private fun arxiv(words: String): String {
        val xml = get("https://export.arxiv.org/api/query?max_results=5&search_query=all:${enc(words)}")
        val entries = Regex("<entry>(.*?)</entry>", RegexOption.DOT_MATCHES_ALL).findAll(xml).toList()
        if (entries.isEmpty()) return "No arXiv paper matched."
        return entries.joinToString("\n\n") { match ->
            val body = match.groupValues[1]
            fun tag(name: String) = Regex("<$name[^>]*>(.*?)</$name>", RegexOption.DOT_MATCHES_ALL)
                .find(body)?.groupValues?.get(1)?.replace(Regex("\\s+"), " ")?.trim().orEmpty()
            val id = tag("id").substringAfter("abs/")
            val year = tag("published").take(4)
            "$id ($year) ${tag("title")}\n${tag("summary").take(320)}"
        }
    }

    private fun doi(raw: String): String {
        val doi = raw.removePrefix("https://doi.org/").removePrefix("doi:").trim()
        val work = json.parseToJsonElement(get("https://api.crossref.org/works/${enc(doi)}"))
            .jsonObject["message"]?.jsonObject ?: throw java.io.IOException("Crossref returned no record.")
        val title = work["title"]?.jsonArray?.firstOrNull()?.let { (it as JsonPrimitive).content }.orEmpty()
        val venue = work["container-title"]?.jsonArray?.firstOrNull()?.let { (it as JsonPrimitive).content }.orEmpty()
        val year = work["issued"]?.jsonObject?.get("date-parts")?.jsonArray?.firstOrNull()?.jsonArray
            ?.firstOrNull()?.let { (it as JsonPrimitive).content }.orEmpty()
        return "DOI $doi exists.\n$title\n$venue $year".trim()
    }

    private fun JsonObject.str(key: String): String = (this[key] as? JsonPrimitive)?.content.orEmpty()

    private fun enc(text: String) = URLEncoder.encode(text, "UTF-8")

    private companion object {
        const val MAX_BYTES = 2L * 1024 * 1024
        val json = Json { ignoreUnknownKeys = true }
    }
}
