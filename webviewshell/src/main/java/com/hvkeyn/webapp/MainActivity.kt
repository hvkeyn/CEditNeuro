package com.hvkeyn.webapp

import android.app.Activity
import android.os.Bundle
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/** Opens the page stored in assets by CEditNeuro. No JavaScript bridge and no custom certificate trust. */
class MainActivity : Activity() {
    private lateinit var web: WebView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val start = assets.open("start.txt").bufferedReader().use { it.readText() }.trim()
        val title = runCatching {
            assets.open("title.txt").bufferedReader().use { it.readText() }.trim()
        }.getOrDefault("")
        if (title.isNotEmpty()) setTitle(title)
        web = WebView(this)
        setContentView(web)
        val settings = web.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.allowContentAccess = false
        settings.allowFileAccess = start.startsWith("file:///android_asset/")
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url?.toString().orEmpty()
                val allowed = url.startsWith("https://") ||
                    url.startsWith("http://") ||
                    url.startsWith("file:///android_asset/")
                return !allowed
            }
        }
        if (start.isNotEmpty()) web.loadUrl(start)
    }

    @Deprecated("This shell targets API 28.")
    @Suppress("DEPRECATION")
    override fun onBackPressed() {
        if (web.canGoBack()) web.goBack() else super.onBackPressed()
    }
}
