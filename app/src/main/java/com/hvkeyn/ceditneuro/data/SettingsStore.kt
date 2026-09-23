package com.hvkeyn.ceditneuro.data

import android.content.Context
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

data class AgentSettings(
    val providers: List<ModelProvider> = ModelCatalog.builtins(),
    val activeProviderId: String = ModelCatalog.DEEPSEEK_ID,
    val activeModel: String = ModelCatalog.FLASH_MODEL,
    val reasoningEffort: String = "high",
    val thinkingEnabled: Boolean = true,
    val autoApproveEdits: Boolean = false,
    /** Agent may download files and install modules. On until the user turns it off. */
    val networkEnabled: Boolean = true,
    /** Null until the user answers the prompt. True lets installed compilers run. */
    val execAllowed: Boolean? = null,
    /** edit, build, or remote. The chat selector writes this. */
    val workFocus: String = WORK_EDIT,
    val remotes: List<RemoteServer> = emptyList(),
    val activeRemoteId: String = "",
) {
    val provider: ModelProvider
        get() = providers.find { it.id == activeProviderId }
            ?: providers.firstOrNull()
            ?: ModelCatalog.deepSeek()

    val model: CatalogModel
        get() = provider.models.find { it.name == activeModel }
            ?: provider.models.firstOrNull()
            ?: CatalogModel(name = activeModel, displayName = activeModel)

    val apiKey: String get() = provider.apiKey
    val baseUrl: String get() = provider.apiUrl

    fun modelLabel(): String = model.displayName.ifBlank { model.name }

    /** Points the selection at a provider and model that still exist. */
    fun normalized(): AgentSettings {
        val selected = providers.find { it.id == activeProviderId } ?: providers.firstOrNull()
            ?: return this
        val modelName = selected.models.find { it.name == activeModel }?.name
            ?: selected.models.firstOrNull()?.name
            ?: activeModel
        return copy(activeProviderId = selected.id, activeModel = modelName)
    }

    companion object {
        val REASONING_EFFORTS = listOf("low", "medium", "high")
        const val WORK_EDIT = "edit"
        const val WORK_BUILD = "build"
        const val WORK_REMOTE = "remote"
        val WORK_FOCUSES = listOf(WORK_EDIT, WORK_BUILD, WORK_REMOTE)
    }
}

/**
 * Provider list and API keys. Keys are stored in [EncryptedSharedPreferences] on the
 * device. Nothing in this class reads a secret out of the project tree.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences = createPrefs(context)
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    private val _settings = MutableStateFlow(read(context))
    val settings: StateFlow<AgentSettings> = _settings.asStateFlow()

    val current: AgentSettings get() = _settings.value

    fun update(transform: (AgentSettings) -> AgentSettings) {
        val next = transform(_settings.value).normalized()
        persist(next)
        _settings.value = next
    }

    private fun read(context: Context): AgentSettings {
        val loaded = AgentSettings(
            providers = loadProviders(),
            activeProviderId = prefs.getString(KEY_ACTIVE_PROVIDER, null) ?: ModelCatalog.DEEPSEEK_ID,
            activeModel = prefs.getString(KEY_ACTIVE_MODEL, null)
                ?: prefs.getString(LEGACY_MODEL, null)
                ?: ModelCatalog.FLASH_MODEL,
            reasoningEffort = prefs.getString(KEY_REASONING, null) ?: "high",
            thinkingEnabled = if (prefs.contains(KEY_THINKING)) prefs.getBoolean(KEY_THINKING, true) else true,
            autoApproveEdits = prefs.getBoolean(KEY_AUTO_APPROVE, false),
            networkEnabled = prefs.getBoolean(KEY_NETWORK, true),
            execAllowed = if (prefs.contains(KEY_EXEC)) prefs.getBoolean(KEY_EXEC, false) else null,
            workFocus = prefs.getString(KEY_WORK, null)?.takeIf { it in AgentSettings.WORK_FOCUSES }
                ?: AgentSettings.WORK_EDIT,
            remotes = prefs.getString(KEY_REMOTES, null)?.let { raw ->
                runCatching { json.decodeFromString<List<RemoteServer>>(raw) }.getOrNull()
            }.orEmpty(),
            activeRemoteId = prefs.getString(KEY_ACTIVE_REMOTE, null).orEmpty(),
        ).normalized()
        return importDebugSeed(context, loaded)
    }

    private fun loadProviders(): List<ModelProvider> {
        val raw = prefs.getString(KEY_PROVIDERS, null)
        if (!raw.isNullOrBlank()) {
            val decoded = runCatching { json.decodeFromString<List<ModelProvider>>(raw) }.getOrNull()
            if (decoded != null) return ModelCatalog.merge(decoded)
        }
        val legacyKey = prefs.getString(LEGACY_API_KEY, null).orEmpty()
        val legacyUrl = migratedDeepSeekUrl(prefs.getString(LEGACY_BASE_URL, null))
        return ModelCatalog.builtins().map { provider ->
            if (provider.id == ModelCatalog.DEEPSEEK_ID) {
                provider.copy(apiKey = legacyKey, apiUrl = legacyUrl)
            } else {
                provider
            }
        }
    }

    /**
     * Debug-only one-shot import. A file dropped into the app's private files dir is
     * read, stored in encrypted prefs, and deleted. Release builds ignore it.
     */
    private fun importDebugSeed(context: Context, settings: AgentSettings): AgentSettings {
        val debuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!debuggable) return settings
        val file = File(context.filesDir, SEED_FILE)
        if (!file.isFile) return settings
        val key = runCatching { file.readText() }.getOrDefault("").trim()
        runCatching { file.delete() }
        if (key.isEmpty()) return settings
        val provider = settings.providers.find { it.id == ModelCatalog.DEEPSEEK_ID }
        if (provider == null || provider.apiKey.isNotBlank()) return settings
        val next = settings.copy(
            providers = settings.providers.map { item ->
                if (item.id == ModelCatalog.DEEPSEEK_ID) item.copy(apiKey = key) else item
            },
        )
        persist(next)
        return next
    }

    private fun persist(settings: AgentSettings) {
        val editor = prefs.edit()
            .putString(KEY_PROVIDERS, json.encodeToString(settings.providers))
            .putString(KEY_ACTIVE_PROVIDER, settings.activeProviderId)
            .putString(KEY_ACTIVE_MODEL, settings.activeModel)
            .putString(KEY_REASONING, settings.reasoningEffort)
            .putBoolean(KEY_THINKING, settings.thinkingEnabled)
            .putBoolean(KEY_AUTO_APPROVE, settings.autoApproveEdits)
            .putBoolean(KEY_NETWORK, settings.networkEnabled)
        val execAllowed = settings.execAllowed
        if (execAllowed == null) editor.remove(KEY_EXEC) else editor.putBoolean(KEY_EXEC, execAllowed)
        editor.putString(KEY_WORK, settings.workFocus)
            .putString(KEY_REMOTES, json.encodeToString(settings.remotes))
            .putString(KEY_ACTIVE_REMOTE, settings.activeRemoteId)
            .apply()
    }

    private fun createPrefs(context: Context): SharedPreferences = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse {
        context.getSharedPreferences("${FILE_NAME}_plain", Context.MODE_PRIVATE)
    }

    private companion object {
        const val FILE_NAME = "ceditneuro_settings"
        const val SEED_FILE = "seed-api-key"
        const val KEY_PROVIDERS = "providers"
        const val KEY_ACTIVE_PROVIDER = "active_provider"
        const val KEY_ACTIVE_MODEL = "active_model"
        const val KEY_REASONING = "reasoning_effort"
        const val KEY_THINKING = "thinking_enabled"
        const val KEY_AUTO_APPROVE = "auto_approve_edits"
        const val KEY_NETWORK = "agent_network"
        const val KEY_EXEC = "exec_allowed"
        const val KEY_WORK = "work_focus"
        const val KEY_REMOTES = "remote_servers"
        const val KEY_ACTIVE_REMOTE = "active_remote"
        const val LEGACY_BASE_URL = "base_url"
        const val LEGACY_MODEL = "model"
        const val LEGACY_API_KEY = "api_key"

        fun migratedDeepSeekUrl(stored: String?): String {
            val url = stored?.trim()?.trimEnd('/').orEmpty()
            return if (url.isEmpty() || url == "https://api.deepseek.com") {
                ModelCatalog.DEEPSEEK_API_URL
            } else {
                url
            }
        }
    }
}
