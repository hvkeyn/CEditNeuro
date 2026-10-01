package com.hvkeyn.ceditneuro.agent.qwen.web

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import com.hvkeyn.ceditneuro.agent.deepseek.web.WebLogin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Sign-in to chat.qwen.ai inside the app, the same way [WebLogin] signs in to DeepSeek:
 * the real page in a WebView, then `localStorage.token` and the cookies.
 */
object QwenLogin {
    const val SIGN_IN = "https://chat.qwen.ai/auth"
    const val CHAT = "https://chat.qwen.ai/"

    /**
     * The chat token only, and only while it still has a couple of minutes left.
     * A Google id token is also a JWT, and an expired access token must not count as signed in.
     */
    const val READ_TOKEN = "(function(){try{" +
        "function live(t){if(!t||t.indexOf('eyJ')!==0)return '';" +
        "try{var b=t.split('.')[1].replace(/-/g,'+').replace(/_/g,'/');while(b.length%4)b+='=';" +
        "var p=JSON.parse(atob(b));if(p&&typeof p.exp==='number'&&p.exp*1000<Date.now()+120000)return '';}catch(e){}" +
        "return t;}" +
        "var raw=localStorage.getItem('qwen_access_token_state');" +
        "if(raw){var o=JSON.parse(raw);if(o&&typeof o.token==='string'){var a=live(o.token);if(a)return a;}}" +
        "return live(localStorage.getItem('token')||'');" +
        "}catch(e){return '';}})()"

    const val READ_VERSION = "(function(){try{" +
        "var nodes=document.querySelectorAll('script[src],link[href]');" +
        "for(var i=0;i<nodes.length;i++){var s=nodes[i].src||nodes[i].href||'';" +
        "var m=s.match(/qwen-chat-fe\\/([0-9]+\\.[0-9]+\\.[0-9]+)/);if(m)return m[1];}" +
        "return '';}catch(e){return '';}})()"

    /** Same WebView setup as DeepSeek: scripts, storage, cookies, and a normal mobile Chrome agent. */
    @SuppressLint("SetJavaScriptEnabled")
    fun configure(view: WebView) {
        WebLogin.configure(view)
    }

    suspend fun token(view: WebView): String = js(view, READ_TOKEN)

    /**
     * A sign-in is finished only after the chat site itself holds the token.
     * The Google page and the auth host also carry JWTs, and saving one of those fails Check.
     */
    suspend fun take(view: WebView): QwenSession? {
        if (!view.url.orEmpty().startsWith("https://chat.qwen.ai")) return null
        if (token(view).isBlank()) return null
        delay(1_200)
        if (!view.url.orEmpty().startsWith("https://chat.qwen.ai")) return null
        val settled = token(view)
        if (settled.isBlank()) return null
        return capture(view, settled, version(view))
    }

    suspend fun version(view: WebView): String = js(view, READ_VERSION)

    private suspend fun js(view: WebView, script: String): String = suspendCancellableCoroutine { done ->
        view.evaluateJavascript(script) { raw ->
            val value = raw?.trim()?.removeSurrounding("\"").orEmpty()
            if (done.isActive) done.resume(if (value == "null") "" else value)
        }
    }

    fun capture(view: WebView, token: String, webVersion: String = ""): QwenSession {
        val manager = CookieManager.getInstance()
        manager.flush()
        return QwenSession(
            token = token,
            cookies = cookies(manager),
            userAgent = view.settings.userAgentString.orEmpty(),
            capturedAt = System.currentTimeMillis(),
            webVersion = webVersion.ifBlank { QwenClient.WEB_VERSION },
        )
    }

    private fun cookies(manager: CookieManager): String {
        val values = linkedMapOf<String, String>()
        listOf(CHAT, "https://auth.qwen.ai/", "https://qwen.ai/").forEach { url ->
            manager.getCookie(url)?.split(';')?.forEach { part ->
                val name = part.substringBefore('=').trim()
                if (name.isNotEmpty()) values[name] = part.substringAfter('=', "").trim()
            }
        }
        return values.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    /** Opens chat.qwen.ai in an unseen WebView and takes a fresh token, or null when signed out. */
    suspend fun refresh(context: Context, seconds: Long = 25): QwenSession? = withContext(Dispatchers.Main) {
        val view = WebView(context.applicationContext)
        try {
            configure(view)
            view.webViewClient = WebViewClient()
            view.loadUrl(CHAT)
            withTimeoutOrNull(seconds * 1000) {
                while (true) {
                    delay(1_000)
                    val token = token(view)
                    if (token.isNotBlank()) return@withTimeoutOrNull capture(view, token, version(view))
                }
                @Suppress("UNREACHABLE_CODE")
                null
            }
        } finally {
            view.stopLoading()
            view.destroy()
        }
    }

    fun signOut(store: QwenSessionStore) {
        store.clear()
        val manager = CookieManager.getInstance()
        manager.getCookie(CHAT)?.split(';')?.map { it.substringBefore('=').trim() }?.filter { it.isNotEmpty() }?.forEach { name ->
            manager.setCookie(CHAT, "$name=; Max-Age=0; Path=/")
            manager.setCookie(".qwen.ai", "$name=; Max-Age=0; Path=/; Domain=.qwen.ai")
        }
        manager.flush()
    }
}
