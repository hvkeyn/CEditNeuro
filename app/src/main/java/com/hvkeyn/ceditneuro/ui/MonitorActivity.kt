package com.hvkeyn.ceditneuro.ui

import android.app.Activity
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.view.WindowManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.hvkeyn.ceditneuro.tools.SecondMonitor

/** Full-screen page for the extra display. The screen stays on while this is open. */
class MonitorActivity : Activity() {
    private var web: WebView? = null
    private var start: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (!SecondMonitor.acceptPage(url)) {
            finish()
            return
        }
        start = Uri.parse(url)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val view = WebView(this)
        web = view
        view.setBackgroundColor(Color.BLACK)
        view.settings.javaScriptEnabled = true
        view.settings.domStorageEnabled = true
        view.settings.useWideViewPort = true
        view.settings.loadWithOverviewMode = true
        view.settings.mediaPlaybackRequiresUserGesture = false
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(webView: WebView, request: WebResourceRequest): Boolean {
                val next = request.url ?: return true
                val host = start?.host ?: return true
                return !host.equals(next.host, ignoreCase = true)
            }
        }
        setContentView(view)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, view).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        view.loadUrl(url)
    }

    override fun onNewIntent(intent: android.content.Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val url = intent.getStringExtra(EXTRA_URL).orEmpty()
        if (!SecondMonitor.acceptPage(url)) return
        start = Uri.parse(url)
        web?.loadUrl(url)
    }

    override fun onDestroy() {
        web?.apply {
            stopLoading()
            destroy()
        }
        web = null
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
    }
}
