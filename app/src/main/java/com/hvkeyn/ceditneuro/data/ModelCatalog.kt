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
    /** A local bridge that ignores Authorization, so no key is asked for. */
    val keyless: Boolean = false,
) {
    val ready: Boolean get() = keyless || apiKey.isNotBlank()
}

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

    const val WEB_BRIDGE_ID = "deepseek-web"
    const val WEB_BRIDGE_URL = "http://127.0.0.1:8000/v1"

    /**
     * Tsuev/opencode-deepseek: an OpenAI-compatible bridge to the free DeepSeek web chat,
     * run on a computer. Tool calls are emulated in text and the context is about 64K.
     */
    fun webBridge(): ModelProvider = ModelProvider(
        id = WEB_BRIDGE_ID,
        name = "DeepSeek Web (free bridge)",
        apiUrl = WEB_BRIDGE_URL,
        keyless = true,
        models = webModels(),
    )

    const val WEB_PHONE_ID = "deepseek-web-phone"
    const val WEB_PHONE_URL = "https://chat.deepseek.com"

    /**
     * The same free web chat spoken directly from the phone: the user signs in once in an
     * in-app page and no computer or bridge is needed.
     */
    fun webPhone(): ModelProvider = ModelProvider(
        id = WEB_PHONE_ID,
        name = "DeepSeek Web (on this phone)",
        apiUrl = WEB_PHONE_URL,
        keyless = true,
        models = webModels(),
    )

    const val QWEN_PHONE_ID = "qwen-web-phone"
    const val QWEN_PHONE_URL = "https://chat.qwen.ai"

    /**
     * The free Qwen web chat spoken directly from the phone: the user signs in once in an
     * in-app page and no computer or bridge is needed. Tool calls are read from the reply text.
     */
    fun qwenPhone(): ModelProvider = ModelProvider(
        id = QWEN_PHONE_ID,
        name = "Qwen Web (on this phone)",
        apiUrl = QWEN_PHONE_URL,
        keyless = true,
        models = listOf(
            CatalogModel(
                name = "qwen3.8-max",
                displayName = "Qwen Web Max",
                maxContextTokens = 64_000,
                maxOutputTokens = 8_192,
            ),
            CatalogModel(
                name = "qwen3.7-plus",
                displayName = "Qwen Web Plus",
                maxContextTokens = 64_000,
                maxOutputTokens = 8_192,
            ),
            CatalogModel(
                name = "qwen3-coder-plus",
                displayName = "Qwen Web Coder",
                maxContextTokens = 64_000,
                maxOutputTokens = 8_192,
            ),
        ),
    )

    private fun webModels(): List<CatalogModel> = listOf(
            CatalogModel(
                name = "deepseek-chat",
                displayName = "DeepSeek Web Instant",
                maxContextTokens = 64_000,
                maxOutputTokens = 8_192,
            ),
            CatalogModel(
                name = "deepseek-expert",
                displayName = "DeepSeek Web Expert",
                maxContextTokens = 64_000,
                maxOutputTokens = 8_192,
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
