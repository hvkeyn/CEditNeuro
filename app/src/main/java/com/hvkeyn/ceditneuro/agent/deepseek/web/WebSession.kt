package com.hvkeyn.ceditneuro.agent.deepseek.web

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** A signed-in chat.deepseek.com session captured from the in-app sign-in page. */
data class WebSession(
    val token: String,
    val cookies: String,
    val userAgent: String,
    val capturedAt: Long,
)

/** Keeps the session in encrypted prefs on this phone. It is never written to a project or a profile. */
class WebSessionStore(context: Context) {
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

    fun load(): WebSession? {
        val token = prefs.getString(TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        return WebSession(
            token = token,
            cookies = prefs.getString(COOKIES, null).orEmpty(),
            userAgent = prefs.getString(AGENT, null).orEmpty(),
            capturedAt = prefs.getLong(AT, 0L),
        )
    }

    fun save(session: WebSession) {
        prefs.edit()
            .putString(TOKEN, session.token)
            .putString(COOKIES, session.cookies)
            .putString(AGENT, session.userAgent)
            .putLong(AT, session.capturedAt)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val FILE = "deepseek_web_session"
        const val TOKEN = "token"
        const val COOKIES = "cookies"
        const val AGENT = "user_agent"
        const val AT = "captured_at"
    }
}
