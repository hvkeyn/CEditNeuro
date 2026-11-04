package com.hvkeyn.ceditneuro.ui.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import java.io.File

/**
 * A real browser window for a local HTML file: JavaScript, forms, links, storage,
 * media, and relative assets under the file's folder. The inline preview intentionally
 * blocks scripts; this mode executes the page.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun HtmlBrowser(
    file: File?,
    markup: String,
    onClose: () -> Unit,
) {
    var canBack by remember { mutableStateOf(false) }
    var canForward by remember { mutableStateOf(false) }
    var title by remember { mutableStateOf(file?.name ?: "HTML preview") }
    var web by remember { mutableStateOf<WebView?>(null) }

    BackHandler { if (canBack) web?.goBack() else onClose() }

    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(horizontal = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    IconButton(onClick = { web?.goBack() }, enabled = canBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                    IconButton(onClick = { web?.goForward() }, enabled = canForward) {
                        Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Forward")
                    }
                    IconButton(onClick = { web?.reload() }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "Reload")
                    }
                    Text(
                        text = title,
                        modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelLarge,
                    )
                    IconButton(onClick = onClose) {
                        Icon(Icons.Filled.Close, contentDescription = "Close browser")
                    }
                }
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context ->
                        BrowserWeb(context).apply {
                            onState = { b, f, t ->
                                canBack = b
                                canForward = f
                                if (t.isNotBlank() && t != "about:blank" && !t.startsWith("file://")) title = t
                            }
                            loadDocument(file, markup)
                            web = this
                        }
                    },
                    update = { view ->
                        if (view.tag != file?.path + "\u0000" + markup) {
                            view.loadDocument(file, markup)
                        }
                    },
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private class BrowserWeb(context: Context) : WebView(context) {
    var onState: (Boolean, Boolean, String) -> Unit = { _, _, _ -> }

    init {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.allowFileAccessFromFileURLs = true
        settings.allowUniversalAccessFromFileURLs = true
        settings.loadsImagesAutomatically = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.javaScriptCanOpenWindowsAutomatically = true
        settings.setSupportMultipleWindows(false)
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        setBackgroundColor(android.graphics.Color.WHITE)
        isVerticalScrollBarEnabled = true
        overScrollMode = OVER_SCROLL_IF_CONTENT_SCROLLS

        webChromeClient = object : WebChromeClient() {
            override fun onReceivedTitle(view: WebView, title: String?) {
                if (!title.isNullOrBlank()) onState(canGoBack(), canGoForward(), title)
            }
        }
        webViewClient = object : WebViewClient() {
            override fun onPageStarted(view: WebView, url: String?, favicon: Bitmap?) {
                onState(canGoBack(), canGoForward(), view.title.orEmpty())
            }

            override fun onPageFinished(view: WebView, url: String?) {
                onState(canGoBack(), canGoForward(), view.title.orEmpty())
            }
        }
    }

    fun loadDocument(file: File?, markup: String) {
        tag = (file?.path ?: "untitled") + "\u0000" + markup
        val base = file?.parentFile?.let { "file://" + it.absolutePath.trimEnd('/') + "/" }
        loadDataWithBaseURL(base, markup, "text/html", "utf-8", null)
    }
}
