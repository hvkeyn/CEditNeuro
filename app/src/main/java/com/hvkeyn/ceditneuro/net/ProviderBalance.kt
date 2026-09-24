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

    private fun get(url: String, apiKey: String): HttpText {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $apiKey")
            .header("Accept", "application/json")
            .build()
        client.newCall(request).execute().use { response ->
            val text = response.body?.string().orEmpty().take(8_000)
            return HttpText(response.code, text.replace(apiKey, "••••"))
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
