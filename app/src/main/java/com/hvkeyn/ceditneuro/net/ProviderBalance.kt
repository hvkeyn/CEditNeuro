package com.hvkeyn.ceditneuro.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
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

    /** OpenAI `data[].id`, or a local server's `models[].name`. */
    fun modelNames(body: String): List<String>? {
        val root = runCatching { json.parseToJsonElement(body) as? JsonObject }.getOrNull() ?: return null
        val data = root["data"] as? JsonArray
        val fromData = data?.mapNotNull { (it as? JsonObject)?.text("id") }.orEmpty()
        if (fromData.isNotEmpty()) return fromData
        val models = root["models"] as? JsonArray ?: return null
        return models.mapNotNull { element ->
            val item = element as? JsonObject ?: return@mapNotNull null
            item.text("name") ?: item.text("model") ?: item.text("id")
        }.takeIf { it.isNotEmpty() }
    }

    /**
     * The models one OpenAI-compatible host is offering right now.
     * A blank key is sent with no Authorization header, which is what a local server expects.
     * The key is never copied into the note.
     */
    fun fetchModels(apiUrl: String, apiKey: String): FetchModels {
        val urls = modelUrls(apiUrl)
        if (urls.isEmpty()) return FetchModels(note = "API URL is not a valid address.")
        val key = apiKey.trim()
        var rejected = false
        for (url in urls) {
            val response = runCatching { get(url, key, authorize = key.isNotEmpty()) }.getOrElse {
                return FetchModels(note = "No answer from that host.")
            }
            when (response.code) {
                401, 403 -> {
                    rejected = true
                    continue
                }
                in 200..299 -> {
                    val names = modelNames(response.body)?.take(80)
                    if (!names.isNullOrEmpty()) return FetchModels(names)
                }
            }
        }
        return if (rejected) {
            FetchModels(note = "That host rejected the saved key.")
        } else {
            FetchModels(note = "That host did not return a model list.")
        }
    }

    internal fun modelUrls(apiUrl: String): List<String> {
        val base = apiUrl.trim().trimEnd('/')
        val origin = originOf(base) ?: return emptyList()
        val urls = LinkedHashSet<String>()
        if (base.endsWith("/v1")) urls += "$base/models" else urls += "$base/v1/models"
        if (!base.endsWith("/models")) urls += "$base/models"
        urls += "$origin/v1/models"
        urls += "$origin/api/tags"
        return urls.toList()
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

    data class FetchModels(val names: List<String> = emptyList(), val note: String = "")

    private fun originOf(apiUrl: String): String? {
        val trimmed = apiUrl.trim()
        val match = Regex("""^(https?://[^/]+)""", RegexOption.IGNORE_CASE).find(trimmed) ?: return null
        return match.groupValues[1].trimEnd('/')
    }

    private fun JsonObject.text(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }

    private fun JsonObject.number(name: String): Double? {
        val primitive = this[name] as? JsonPrimitive ?: return null
        primitive.doubleOrNull?.let { return it }
        return primitive.contentOrNull?.toDoubleOrNull()
    }

    private fun money(value: Double): String = String.format(java.util.Locale.US, "%.2f", value)

    private data class HttpText(val code: Int, val body: String)
}
