package com.hvkeyn.ceditneuro.ui

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

@Composable
fun WebPreview(
    url: String,
    generation: Int,
    onClose: () -> Unit,
    onLoaded: (title: String, text: String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, color = MaterialTheme.colorScheme.surface) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = url,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.labelLarge,
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Default.Close, contentDescription = "Close site")
                }
            }
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                Text(
                    text = "No page yet. Ask the agent to open one, or set a site URL on the server.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { context -> PageWebView(context) },
                    update = { view ->
                        view.onLoaded = onLoaded
                        if (view.generation != generation) {
                            view.generation = generation
                            view.loadUrl(url)
                        }
                    },
                )
            }
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
private class PageWebView(context: Context) : WebView(context) {
    var onLoaded: (title: String, text: String) -> Unit = { _, _ -> }
    var generation: Int = -1

    init {
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, finishedUrl: String) {
                if (finishedUrl == "about:blank") return
                evaluateJavascript(PAGE_SCRIPT) { raw ->
                    val (title, text) = parsePage(raw)
                    onLoaded(title, text)
                }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError,
            ) {
                if (request.isForMainFrame) onLoaded("", "Browser error: ${error.description}")
            }
        }
    }
}

private fun parsePage(raw: String?): Pair<String, String> {
    if (raw.isNullOrBlank() || raw == "null") return "" to ""
    val inner = runCatching { pageJson.parseToJsonElement(raw) }.getOrNull()
    val payload = if (inner is JsonPrimitive && inner.isString) inner.content else raw
    val obj = runCatching { pageJson.parseToJsonElement(payload).jsonObject }.getOrNull() ?: return "" to payload.take(4000)
    return obj.string("title") to obj.string("text")
}

private fun JsonObject.string(key: String): String = (this[key] as? JsonPrimitive)?.content.orEmpty()

private val pageJson = Json { ignoreUnknownKeys = true }

private const val PAGE_SCRIPT =
    "(function(){var t=document.title||'';var b=document.body?document.body.innerText:'';" +
        "return JSON.stringify({title:t,text:b.slice(0,6000)});})()"
