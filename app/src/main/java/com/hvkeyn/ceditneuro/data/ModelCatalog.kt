package com.hvkeyn.ceditneuro.data

import kotlinx.serialization.Serializable

/**
 * One model on an OpenAI-compatible host. API keys never live here: the built-in
 * catalog only names public endpoints and model ids.
 */
@Serializable
data class CatalogModel(
    val name: String,
    val displayName: String,
    val maxContextTokens: Int = 128_000,
    val maxOutputTokens: Int = 8_192,
    val supportsTools: Boolean = true,
    val supportsReasoning: Boolean = false,
    val supportsVision: Boolean = false,
) {
    fun seesImages(): Boolean {
        if (supportsVision) return true
        val id = name.lowercase()
        if (id.contains("deepseek")) return false
        return id.contains("vision") || id.contains("gpt-4o") || id.contains("gpt-4.1") ||
            id.contains("gemini") || id.contains("claude") || id.contains("-vl") ||
            id.contains("pixtral") || id.contains("llava") || id.contains("grok")
    }
}

/** A provider, in the same shape Zed stores under `language_models`. */
@Serializable
data class ModelProvider(
    val id: String,
    val name: String,
    val apiUrl: String,
    val apiKey: String = "",
    val builtin: Boolean = false,
    val models: List<CatalogModel> = emptyList(),
)

/**
 * Built-in models copied from the local Zed config for DeepSeek Flash.
 * `deepseek-flash` is the fully specified model (V4.1 Flash, 1M context, 32K output,
 * tools, interleaved reasoning). `deepseek-v4-flash` is the other Flash favorite.
 */
object ModelCatalog {
    const val DEEPSEEK_ID = "deepseek"
    const val DEEPSEEK_API_URL = "https://api.deepseek.com/v1"
    const val FLASH_MODEL = "deepseek-flash"
    const val V4_FLASH_MODEL = "deepseek-v4-flash"

    fun builtins(): List<ModelProvider> = listOf(deepSeek())

    fun deepSeek(): ModelProvider = ModelProvider(
        id = DEEPSEEK_ID,
        name = "DeepSeek",
        apiUrl = DEEPSEEK_API_URL,
        builtin = true,
        models = listOf(
            CatalogModel(
                name = FLASH_MODEL,
                displayName = "DeepSeek V4.1 Flash",
                maxContextTokens = 1_000_000,
                maxOutputTokens = 32_000,
                supportsTools = true,
                supportsReasoning = true,
            ),
            CatalogModel(
                name = V4_FLASH_MODEL,
                displayName = "DeepSeek V4 Flash",
                maxContextTokens = 1_000_000,
                maxOutputTokens = 32_000,
                supportsTools = true,
                supportsReasoning = true,
            ),
        ),
    )

    /** Keeps a saved API key and any extra models, and refreshes the built-in catalog. */
    fun merge(saved: List<ModelProvider>): List<ModelProvider> {
        val builtinIds = builtins().map { it.id }.toSet()
        val mergedBuiltins = builtins().map { builtin ->
            val previous = saved.find { it.id == builtin.id } ?: return@map builtin
            val extras = previous.models.filter { model ->
                builtin.models.none { it.name == model.name }
            }
            builtin.copy(
                apiKey = previous.apiKey,
                apiUrl = previous.apiUrl.ifBlank { builtin.apiUrl },
                models = builtin.models + extras,
            )
        }
        return mergedBuiltins + saved.filter { it.id !in builtinIds }
    }
}
