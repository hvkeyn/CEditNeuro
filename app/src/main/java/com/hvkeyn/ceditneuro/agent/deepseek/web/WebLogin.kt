package com.hvkeyn.ceditneuro.agent.deepseek.web

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Sign-in to chat.deepseek.com inside the app. The user signs in on the real page in a
 * WebView; the app then reads `localStorage.userToken` and the cookies, the same way the
 * desktop bridge reads them from its browser profile.
 */
object WebLogin {
    const val SIGN_IN = "https://chat.deepseek.com/sign_in"
    const val CHAT = "https://chat.deepseek.com/"

    const val READ_TOKEN = "(function(){try{var r=localStorage.getItem('userToken');if(!r)return '';" +
        "var o=JSON.parse(r);return (o&&o.value)?o.value:(typeof o==='string'?o:'');}catch(e){return '';}})()"

    @SuppressLint("SetJavaScriptEnabled")
    fun configure(view: WebView) {
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.databaseEnabled = true
        view.settings.javaScriptCanOpenWindowsAutomatically = true
        view.settings.userAgentString = browserAgent(view.settings.userAgentString.orEmpty())
        val cookies = CookieManager.getInstance()
        cookies.setAcceptCookie(true)
        cookies.setAcceptThirdPartyCookies(view, true)
    }

    /** The WebView agent as plain mobile Chrome: sign-in pages refuse the embedded `; wv` marker. */
    fun browserAgent(agent: String): String = agent
        .replace("; wv)", ")")
        .replace(Regex("\\s*Version/\\d+(\\.\\d+)*"), "")

    /** Reads the token from the page; empty until the user has signed in. */
    suspend fun token(view: WebView): String = suspendCancellableCoroutine { done ->
        view.evaluateJavascript(READ_TOKEN) { raw ->
            val value = raw?.trim()?.removeSurrounding("\"").orEmpty()
            if (done.isActive) done.resume(if (value == "null") "" else value)
        }
    }

    fun capture(view: WebView, token: String): WebSession {
        val manager = CookieManager.getInstance()
        manager.flush()
        return WebSession(
            token = token,
            cookies = manager.getCookie(CHAT).orEmpty(),
            userAgent = view.settings.userAgentString.orEmpty(),
            capturedAt = System.currentTimeMillis(),
        )
    }

    /**
     * Opens chat.deepseek.com in an unseen WebView with the saved cookies and takes a fresh
     * token, or null when the page is signed out and the user has to sign in again.
     */
    suspend fun refresh(context: Context, seconds: Long = 25): WebSession? = withContext(Dispatchers.Main) {
        val view = WebView(context.applicationContext)
        try {
            configure(view)
            view.webViewClient = WebViewClient()
            view.loadUrl(CHAT)
            withTimeoutOrNull(seconds * 1000) {
                while (true) {
                    delay(1_000)
                    val token = token(view)
                    if (token.isNotBlank()) return@withTimeoutOrNull capture(view, token)
                }
                @Suppress("UNREACHABLE_CODE")
                null
            }
        } finally {
            view.stopLoading()
            view.destroy()
        }
    }

    fun signOut(store: WebSessionStore) {
        store.clear()
        val manager = CookieManager.getInstance()
        manager.getCookie(CHAT)?.split(';')?.map { it.substringBefore('=').trim() }?.filter { it.isNotEmpty() }?.forEach { name ->
            manager.setCookie(CHAT, "$name=; Max-Age=0; Path=/")
            manager.setCookie(".deepseek.com", "$name=; Max-Age=0; Path=/; Domain=.deepseek.com")
        }
        manager.flush()
    }
}
