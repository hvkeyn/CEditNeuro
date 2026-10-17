package com.hvkeyn.ceditneuro.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.util.concurrent.TimeUnit

/**
 * Reads a prepaid balance for the API key currently typed in settings.
 * The key is sent only as a Bearer token and is never written into the result text.
 */
object ProviderBalance {
    private val json = Json { ignoreUnknownKeys = true }
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(25, TimeUnit.SECONDS)
        .build()

    fun lookup(apiUrl: String, apiKey: String): String {
        val key = apiKey.trim()
        if (key.isEmpty()) return "No API key."
        val origin = originOf(apiUrl) ?: return "API URL is not a valid address."
        var rejected = false
        for (url in candidateUrls(origin, apiUrl)) {
            val response = runCatching { get(url, key) }.getOrElse {
                return "Balance request failed."
            }
            when (response.code) {
                401, 403 -> {
                    rejected = true
                    continue
                }
                404, 405 -> continue
                in 200..299 -> parse(response.body)?.let { return it }
            }
        }
        return if (rejected) {
            "The key was rejected, so the balance is unavailable."
        } else {
            "This host does not report a balance for an API key."
        }
    }

    /** Asks a keyless OpenAI-compatible bridge for its models; one line for the settings card. */
    fun bridge(apiUrl: String): String {
        val base = apiUrl.trim().trimEnd('/')
        if (originOf(base) == null) return "API URL is not a valid address."
        val response = runCatching { get("$base/models", "unused") }.getOrElse { error ->
            return "No answer from the bridge (${error.message ?: error.javaClass.simpleName}). Start python app.py on the computer."
        }
        if (response.code !in 200..299) return "The bridge answered HTTP ${response.code}."
        return bridgeModels(response.body)?.let { "Bridge is up: " + it.joinToString(", ") }
            ?: "This address answers, but not with a model list."
    }

    fun bridgeModels(body: String): List<String>? = modelNames(body)

    /** Ids from one OpenAI model list. A local server may use `models[].name` in that same body. */
    fun modelNames(body: String): List<String>? = listedModels(body)?.map { it.name }

    /**
     * The model rows in one list response. A context size is kept only when that row carries it.
     * Nothing else in the body is read.
     */
    fun listedModels(body: String): List<ListedModel>? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val data = (root["data"] as? JsonArray)?.mapNotNull { element -> listed(element as? JsonObject, "id") }.orEmpty()
        if (data.isNotEmpty()) return data
        val models = root["models"] as? JsonArray ?: return null
        return models.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            listed(item, "name") ?: listed(item, "model") ?: listed(item, "id")
        }.takeIf { it.isNotEmpty() }
    }

    private fun listed(item: JsonObject?, field: String): ListedModel? {
        if (item == null) return null
        val name = item.text(field) ?: return null
        val context = item.int("context_length")
            ?: item.int("max_model_len")
            ?: item.int("context_window")
        return ListedModel(name, context)
    }

    /**
     * Asks one host for its model list: `{base}/models` when the base already ends in `/v1`,
     * otherwise `{base}/v1/models`. A remote server and a local server use this same request.
     * A blank key sends no Authorization header. The key is never copied into the note.
     */
    fun fetchModels(apiUrl: String, apiKey: String): FetchModels {
        val url = modelUrl(apiUrl) ?: return FetchModels(note = "API URL is not a valid address.")
        val key = apiKey.trim()
        val response = runCatching { get(url, key, authorize = key.isNotEmpty()) }.getOrElse {
            return FetchModels(note = "No answer from that host.")
        }
        if (response.code == 401 || response.code == 403) {
            return FetchModels(note = "That host rejected the saved key.")
        }
        if (response.code !in 200..299) return FetchModels(note = "That host did not return a model list.")
        val listed = listedModels(response.body)?.take(80).orEmpty()
        if (listed.isEmpty()) return FetchModels(note = "That host did not return a model list.")
        return FetchModels(
            names = listed.map { it.name },
            contextTokens = listed.mapNotNull { model -> model.contextTokens?.let { model.name to it } }.toMap(),
        )
    }

    /**
     * Asks the host to accept one model id with a one-token completion.
     * A hidden id can pass this even when the model list omitted it.
     * The key and the response body are not returned.
     */
    fun probeModel(apiUrl: String, apiKey: String, model: String): String {
        val name = model.trim()
        if (!name.matches(Regex("""[A-Za-z0-9_.:/+-]{1,80}"""))) return "Model id has an unsupported character."
        val url = probeUrl(apiUrl) ?: return "API URL is not a valid address."
        val key = apiKey.trim()
        val payload = """{"model":"$name","messages":[{"role":"user","content":"ping"}],"max_tokens":16}"""
        val response = runCatching { post(url, key, payload) }.getOrElse {
            return "No answer from that host."
        }
        return when (modelVerdict(response.code, response.body)) {
            "rejected" -> "That host rejected the saved key."
            "missing" -> "This host does not have $name."
            "present" -> "This host has $name."
            else -> "This host did not confirm $name."
        }
    }

    internal fun probeUrl(apiUrl: String): String? {
        val base = apiUrl.trim().trimEnd('/')
        if (originOf(base) == null) return null
        return if (base.endsWith("/chat/completions")) base else "$base/chat/completions"
    }

    /** present, missing, rejected, or unknown. The body is only scanned for an unknown-model phrase. */
    internal fun modelVerdict(code: Int, body: String): String {
        val missing = modelMissing(body)
        return when {
            code == 401 || code == 403 -> "rejected"
            code == 404 || missing -> "missing"
            code in 200..299 && "\"choices\"" in body -> "present"
            else -> "unknown"
        }
    }

    private fun modelMissing(body: String): Boolean {
        val text = body.lowercase()
        return "model_not_found" in text || "does not exist" in text || "do not exist" in text ||
            "not exist" in text || "model not found" in text || "unknown model" in text ||
            "invalid model" in text || "no such model" in text
    }

    /** The single models address for an OpenAI-compatible base URL. */
    internal fun modelUrl(apiUrl: String): String? {
        val base = apiUrl.trim().trimEnd('/')
        if (originOf(base) == null) return null
        return when {
            base.endsWith("/models") -> base
            base.endsWith("/v1") -> "$base/models"
            else -> "$base/v1/models"
        }
    }

    /** Turns a balance JSON body into one line, or null when the shape is unknown. */
    fun parse(body: String): String? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull()
            ?: return null
        deepSeek(root)?.let { return it }
        openRouterCredits(root)?.let { return it }
        openRouterKey(root)?.let { return it }
        moonshot(root)?.let { return it }
        siliconFlow(root)?.let { return it }
        openAiGrants(root)?.let { return it }
        return null
    }

    internal fun candidateUrls(origin: String, apiUrl: String): List<String> {
        val host = origin.substringAfter("://").lowercase()
        val specific = when {
            "deepseek.com" in host -> listOf("$origin/user/balance")
            "openrouter.ai" in host -> listOf(
                "https://openrouter.ai/api/v1/credits",
                "https://openrouter.ai/api/v1/key",
            )
            "moonshot" in host -> listOf("$origin/v1/users/me/balance")
            "siliconflow" in host -> listOf("$origin/v1/user/info")
            "openai.com" in host -> listOf("$origin/v1/dashboard/billing/credit_grants")
            else -> emptyList()
        }
        if (specific.isNotEmpty()) return specific
        return listOf(
            "$origin/user/balance",
            "$origin/v1/user/balance",
            "$origin/v1/users/me/balance",
            "$origin/v1/user/info",
            "$origin/api/v1/credits",
            "$origin/v1/dashboard/billing/credit_grants",
        )
    }

    private fun post(url: String, apiKey: String, jsonBody: String): HttpText {
        val builder = Request.Builder()
            .url(url)
            .header("Accept", "application/json")
            .post(jsonBody.toRequestBody("application/json".toMediaType()))
        if (apiKey.isNotEmpty()) builder.header("Authorization", "Bearer $apiKey")
        client.newCall(builder.build()).execute().use { response ->
            val text = response.body?.string().orEmpty().take(4_000)
            val hidden = if (apiKey.isEmpty()) text else text.replace(apiKey, "••••")
            return HttpText(response.code, hidden)
        }
    }

    private fun get(url: String, apiKey: String, authorize: Boolean = true): HttpText {
        val builder = Request.Builder().url(url).header("Accept", "application/json")
        if (authorize && apiKey.isNotEmpty()) builder.header("Authorization", "Bearer $apiKey")
        val request = builder.build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty().take(64_000)
            val hidden = if (apiKey.isEmpty()) text else text.replace(apiKey, "••••")
            return HttpText(response.code, hidden)
        }
    }

    private fun deepSeek(root: JsonObject): String? {
        val infos = root["balance_infos"] as? JsonArray ?: return null
        val parts = infos.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            val currency = item.text("currency") ?: return@mapNotNull null
            val total = item.text("total_balance") ?: return@mapNotNull null
            "$currency $total"
        }
        if (parts.isEmpty()) return null
        val available = (root["is_available"] as? JsonPrimitive)?.contentOrNull
        val suffix = if (available == "false") " — top up" else ""
        return parts.joinToString(" · ") + suffix
    }

    private fun openRouterCredits(root: JsonObject): String? {
        val data = root["data"] as? JsonObject ?: return null
        val credits = data.number("total_credits") ?: return null
        val used = data.number("total_usage") ?: 0.0
        return "USD ${money(credits - used)} left"
    }

    private fun openRouterKey(root: JsonObject): String? {
        val data = root["data"] as? JsonObject ?: return null
        val remaining = data.number("limit_remaining") ?: return null
        val limit = data.number("limit")
        return if (limit == null) "USD ${money(remaining)} left" else "USD ${money(remaining)} of ${money(limit)} left"
    }

    private fun moonshot(root: JsonObject): String? {
        val data = root["data"] as? JsonObject ?: return null
        val available = data.number("available_balance") ?: return null
        if (data["cash_balance"] == null && data["voucher_balance"] == null) return null
        return "CNY ${money(available)} left"
    }

    private fun siliconFlow(root: JsonObject): String? {
        val data = root["data"] as? JsonObject ?: return null
        val balance = data.text("balance") ?: data.text("totalBalance") ?: return null
        if (data["chargeBalance"] == null && data["totalBalance"] == null) return null
        return "USD $balance left"
    }

    private fun openAiGrants(root: JsonObject): String? {
        val available = root.number("total_available") ?: return null
        if (root["total_granted"] == null && root["total_used"] == null) return null
        return "USD ${money(available)} left"
    }

    data class ListedModel(val name: String, val contextTokens: Int? = null)

    data class FetchModels(
        val names: List<String> = emptyList(),
        val note: String = "",
        val contextTokens: Map<String, Int> = emptyMap(),
    )

    private fun originOf(apiUrl: String): String? {
        val trimmed = apiUrl.trim()
        val match = Regex("""^(https?://[^/]+)""", RegexOption.IGNORE_CASE).find(trimmed) ?: return null
        return match.groupValues[1].trimEnd('/')
    }

    private fun JsonObject.text(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(name: String): Int? {
        val primitive = this[name] as? JsonPrimitive ?: return null
        primitive.intOrNull?.let { return it }
        return primitive.contentOrNull?.toDoubleOrNull()?.toInt()
    }

    private fun JsonObject.number(name: String): Double? {
        val primitive = this[name] as? JsonPrimitive ?: return null
        primitive.doubleOrNull?.let { return it }
        return primitive.contentOrNull?.toDoubleOrNull()
    }

    private fun money(value: Double): String = String.format(java.util.Locale.US, "%.2f", value)

    private data class HttpText(val code: Int, val body: String)
}
