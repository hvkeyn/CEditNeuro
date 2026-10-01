package com.hvkeyn.ceditneuro.agent.qwen.web

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** A signed-in chat.qwen.ai session captured from the in-app sign-in page. */
data class QwenSession(
    val token: String,
    val cookies: String,
    val userAgent: String,
    val capturedAt: Long,
    val webVersion: String = "",
)

/** Keeps the session in encrypted prefs on this phone. It is never written to a project or a profile. */
class QwenSessionStore(context: Context) {
    private val prefs: SharedPreferences = runCatching {
        val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
        EncryptedSharedPreferences.create(
            context,
            FILE,
            key,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrElse { context.getSharedPreferences(FILE + "_plain", Context.MODE_PRIVATE) }

    fun load(): QwenSession? {
        val token = prefs.getString(TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        return QwenSession(
            token = token,
            cookies = prefs.getString(COOKIES, null).orEmpty(),
            userAgent = prefs.getString(AGENT, null).orEmpty(),
            capturedAt = prefs.getLong(AT, 0L),
            webVersion = prefs.getString(VERSION, null).orEmpty(),
        )
    }

    fun save(session: QwenSession) {
        prefs.edit()
            .putString(TOKEN, session.token)
            .putString(COOKIES, session.cookies)
            .putString(AGENT, session.userAgent)
            .putLong(AT, session.capturedAt)
            .putString(VERSION, session.webVersion)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val FILE = "qwen_web_session"
        const val TOKEN = "token"
        const val COOKIES = "cookies"
        const val AGENT = "user_agent"
        const val AT = "captured_at"
        const val VERSION = "web_version"
    }
}
