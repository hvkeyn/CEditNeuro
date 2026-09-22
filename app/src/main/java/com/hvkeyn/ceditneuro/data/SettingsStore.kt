package com.hvkeyn.ceditneuro.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class AgentSettings(
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = DEFAULT_MODEL,
    val apiKey: String = "",
    val reasoningEffort: String = "medium",
    val autoApproveEdits: Boolean = false,
) {
    companion object {
        const val DEFAULT_BASE_URL = "https://api.deepseek.com"
        const val DEFAULT_MODEL = "deepseek-flash"
        val REASONING_EFFORTS = listOf("low", "medium", "high")
    }
}

/**
 * Stores the DeepSeek credentials and agent preferences. The API key is kept in
 * [EncryptedSharedPreferences]; if the keystore is unavailable we fall back to plain
 * preferences rather than crashing on start-up.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences = createPrefs(context)

    private val _settings = MutableStateFlow(read())
    val settings: StateFlow<AgentSettings> = _settings.asStateFlow()

    val current: AgentSettings get() = _settings.value

    fun update(transform: (AgentSettings) -> AgentSettings) {
        val next = transform(_settings.value)
        prefs.edit()
            .putString(KEY_BASE_URL, next.baseUrl)
            .putString(KEY_MODEL, next.model)
            .putString(KEY_API_KEY, next.apiKey)
            .putString(KEY_REASONING, next.reasoningEffort)
            .putBoolean(KEY_AUTO_APPROVE, next.autoApproveEdits)
            .apply()
        _settings.value = next
    }

    private fun read(): AgentSettings = AgentSettings(
        baseUrl = prefs.getString(KEY_BASE_URL, null) ?: AgentSettings.DEFAULT_BASE_URL,
        model = prefs.getString(KEY_MODEL, null) ?: AgentSettings.DEFAULT_MODEL,
        apiKey = prefs.getString(KEY_API_KEY, null).orEmpty(),
        reasoningEffort = prefs.getString(KEY_REASONING, null) ?: "medium",
        autoApproveEdits = prefs.getBoolean(KEY_AUTO_APPROVE, false),
    )

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
        const val KEY_BASE_URL = "base_url"
        const val KEY_MODEL = "model"
        const val KEY_API_KEY = "api_key"
        const val KEY_REASONING = "reasoning_effort"
        const val KEY_AUTO_APPROVE = "auto_approve_edits"
    }
}
