package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.data.AgentSettings
import com.hvkeyn.ceditneuro.data.ModelCatalog
import com.hvkeyn.ceditneuro.data.ModelProvider
import com.hvkeyn.ceditneuro.net.ProviderBalance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * Asks an OpenAI-compatible host which models it has, then saves that list on the matching provider.
 * A local server is saved with no key. The API key is never written into the result.
 */
class ListModelsTool(
    private val current: () -> AgentSettings,
    private val replace: (AgentSettings) -> Unit,
    private val fetch: (String, String) -> ProviderBalance.FetchModels = ProviderBalance::fetchModels,
) : Tool {
    override val name = "list_models"
    override val description =
        "Fetch the models an OpenAI-compatible host is offering and save them on that provider. " +
            "provider is a saved name or id. url is the address when it is not the active provider, " +
            "including a local server such as http://127.0.0.1:11434/v1. " +
            "A local server needs no key. Do not ask for an API key, do not print one, and do not invent a model id."
    override val parameters = objectSchema(
        properties = mapOf(
            "provider" to stringProp("Saved provider name or id. Omit to use the active provider."),
            "url" to stringProp("OpenAI-compatible base URL. Optional when the provider is already saved."),
        ),
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val settings = current()
        val asked = args.stringArg("provider").orEmpty().trim()
        val url = cleanUrl(args.stringArg("url").orEmpty())
        val known = find(settings, asked, url)
        if (known?.id == ModelCatalog.WEB_PHONE_ID || known?.id == ModelCatalog.QWEN_PHONE_ID) {
            val names = known.models.joinToString(", ") { it.name }
            return@withContext ToolResult.ok("Those models are fixed in the app: $names.")
        }
        val target = url.ifBlank { known?.apiUrl.orEmpty() }
        if (target.isBlank()) return@withContext ToolResult.error("Name a saved provider or a URL.")
        if (!settings.networkEnabled && !localHost(target)) {
            return@withContext ToolResult.error("Network is off in Settings.")
        }
        val fetched = fetch(target, known?.apiKey.orEmpty())
        if (fetched.names.isEmpty()) return@withContext ToolResult.error(redact(fetched.note, settings))
        val models = ModelCatalog.adopt(fetched.names, known?.models.orEmpty())
        val saved = when {
            known != null -> {
                replace(
                    settings.copy(
                        providers = settings.providers.map { item ->
                            if (item.id == known.id) item.copy(models = models) else item
                        },
                    ),
                )
                known.name
            }
            localHost(target) -> {
                val added = ModelProvider(
                    id = "local-" + target.hashCode().toUInt().toString(16),
                    name = hostOf(target) ?: "Local",
                    apiUrl = target,
                    keyless = true,
                    models = models,
                )
                replace(settings.copy(providers = settings.providers + added))
                added.name
            }
            else -> null
        }
        val listed = fetched.names.take(20).joinToString(", ")
        val more = if (fetched.names.size > 20) " and ${fetched.names.size - 20} more" else ""
        val where = if (saved == null) {
            "Not saved. Add that host in Settings first."
        } else {
            "Saved on $saved."
        }
        ToolResult.ok(redact("$where ${fetched.names.size} models: $listed$more.", settings))
    }

    private fun find(settings: AgentSettings, asked: String, url: String): ModelProvider? {
        if (url.isNotEmpty()) {
            settings.providers.find { it.apiUrl.trim().trimEnd('/').equals(url, ignoreCase = true) }?.let { return it }
        }
        if (asked.isNotEmpty()) {
            settings.providers.find {
                it.id.equals(asked, ignoreCase = true) || it.name.equals(asked, ignoreCase = true)
            }?.let { return it }
        }
        return if (url.isEmpty() && asked.isEmpty()) settings.provider else null
    }

    private fun redact(text: String, settings: AgentSettings): String {
        var out = text
        for (provider in settings.providers) {
            if (provider.apiKey.length >= 6) out = out.replace(provider.apiKey, "••••")
        }
        return out
    }

    private fun cleanUrl(raw: String): String =
        raw.trim().replace(Regex("""(https?://)[^/@\s]+@""", RegexOption.IGNORE_CASE), "$1")

    private fun hostOf(url: String): String? =
        Regex("""^https?://([^/:]+)""", RegexOption.IGNORE_CASE).find(url)?.groupValues?.get(1)

    private fun localHost(url: String): Boolean {
        val host = hostOf(url)?.lowercase() ?: return false
        if (host == "localhost" || host == "127.0.0.1" || host == "::1") return true
        if (host.startsWith("192.168.") || host.startsWith("10.")) return true
        val parts = host.split('.')
        if (parts.size == 4 && parts[0] == "172") {
            val second = parts[1].toIntOrNull() ?: return false
            return second in 16..31
        }
        return false
    }
}
