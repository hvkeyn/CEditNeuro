package com.hvkeyn.ceditneuro.video

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ViewGroup
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.Executors
import kotlin.coroutines.resume

/** Covers the app while a composition is drawn frame by frame into an MP4. */
@Composable
fun RenderHost() {
    val job by VideoRenderHub.job.collectAsState()
    val current = job ?: return
    val progress by VideoRenderHub.progress.collectAsState()
    val html = remember(current) { runCatching { current.html.readText() }.getOrDefault("") }
    val info = remember(html) { Composition.parse(html) }
    if (info == null) {
        LaunchedEffect(current) {
            VideoRenderHub.complete(current, VideoRenderHub.Outcome(false, "That file is not a composition."))
        }
        return
    }
    val (outW, outH) = remember(info, current.maxSide) { Composition.outputSize(info, current.maxSide) }
    val holder = remember(current) { WebHolder() }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xF0000000)),
    ) {
        Column(
            Modifier.fillMaxSize().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("Rendering ${current.out.name}", color = Color.White, style = MaterialTheme.typography.titleMedium)
            val shown = progress
            val label = if (shown == null) {
                "Loading the composition"
            } else {
                "Frame ${shown.frame} of ${shown.total} · ${shown.width}×${shown.height} · ${current.fps} fps"
            }
            Text(label, color = Color(0xFFCCCCCC), style = MaterialTheme.typography.bodyMedium)
            LinearProgressIndicator(
                progress = { if (shown == null || shown.total == 0) 0f else shown.frame / shown.total.toFloat() },
                modifier = Modifier.fillMaxWidth(),
            )
            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val density = LocalDensity.current
                val boxW = with(density) { maxWidth.toPx() }
                val boxH = with(density) { maxHeight.toPx() }
                val fit = minOf(boxW / outW, boxH / outH, 1f)
                val shownW = with(density) { (outW * fit).toDp() }
                val shownH = with(density) { (outH * fit).toDp() }
                AndroidView(
                    modifier = Modifier.size(shownW, shownH),
                    factory = { context ->
                        FrameLayout(context).apply {
                            clipChildren = true
                            val web = holder.create(context, outW, outH)
                            web.pivotX = 0f
                            web.pivotY = 0f
                            web.scaleX = fit
                            web.scaleY = fit
                            addView(web, FrameLayout.LayoutParams(outW, outH))
                        }
                    },
                )
            }
            TextButton(onClick = {
                VideoRenderHub.complete(current, VideoRenderHub.Outcome(false, "Stopped by the user."))
            }) { Text("Cancel", color = Color.White) }
        }
    }

    LaunchedEffect(current) {
        val outcome = runCatching { renderFrames(current, html, holder, outW, outH) }
            .getOrElse { VideoRenderHub.Outcome(false, "The render failed: ${it.message ?: it.javaClass.simpleName}.") }
        VideoRenderHub.complete(current, outcome)
    }
}

private class WebHolder {
    var web: WebView? = null
    val loaded = CompletableDeferred<Unit>()

    @SuppressLint("SetJavaScriptEnabled")
    fun create(context: android.content.Context, width: Int, height: Int): WebView {
        web?.let { (it.parent as? ViewGroup)?.removeView(it); return it }
        return WebView(context).apply {
            settings.javaScriptEnabled = true
            settings.allowFileAccess = true
            settings.mediaPlaybackRequiresUserGesture = false
            settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            settings.useWideViewPort = false
            settings.loadWithOverviewMode = false
            setBackgroundColor(android.graphics.Color.BLACK)
            isVerticalScrollBarEnabled = false
            isHorizontalScrollBarEnabled = false
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    loaded.complete(Unit)
                }
            }
            minimumWidth = width
            minimumHeight = height
            web = this
        }
    }
}

private suspend fun renderFrames(
    job: VideoRenderHub.Job,
    html: String,
    holder: WebHolder,
    outW: Int,
    outH: Int,
): VideoRenderHub.Outcome {
    var web: WebView? = null
    for (attempt in 0 until 100) {
        web = holder.web
        if (web != null && web.width == outW) break
        delay(50)
    }
    val view = web ?: return VideoRenderHub.Outcome(false, "The render view did not open.")
    val base = "file://" + (job.html.parentFile?.absolutePath ?: "/") + "/"
    view.loadDataWithBaseURL(base, Composition.hosted(html, play = false), "text/html", "utf-8", null)
    withTimeoutOrNull(60_000) { holder.loaded.await() }
        ?: return VideoRenderHub.Outcome(false, "The composition did not load within 60 seconds.")
    val density = view.resources.displayMetrics.density
    val cssW = "%.3f".format(java.util.Locale.US, outW / density)
    val cssH = "%.3f".format(java.util.Locale.US, outH / density)
    var meta: kotlinx.serialization.json.JsonObject? = null
    for (attempt in 0 until 40) {
        val raw = view.eval("window.__hfSize ? (window.__hfSize($cssW, $cssH), window.__hfInfo()) : ''")
        val text = runCatching { Json.parseToJsonElement(raw).jsonPrimitive.content }.getOrDefault("")
        if (text.startsWith("{")) {
            val parsed = Json.parseToJsonElement(text).jsonObject
            meta = parsed
            if (parsed["tl"]?.jsonPrimitive?.content == "true") break
        }
        delay(250)
    }
    val found = meta ?: return VideoRenderHub.Outcome(false, "The page did not start. Check the script in ${job.html.name}.")
    val hasTimeline = found["tl"]?.jsonPrimitive?.content == "true"
    val seconds = found["d"]?.jsonPrimitive?.content?.toDoubleOrNull()?.takeIf { it > 0 }
        ?: return VideoRenderHub.Outcome(false, "The composition has no length. Set data-duration on the root.")
    val clipped = seconds.coerceAtMost(VideoRenderHub.MAX_SECONDS)
    val total = kotlin.math.ceil(clipped * job.fps).toInt().coerceAtLeast(1)
    val encoderThread = Executors.newSingleThreadExecutor()
    val encoding = encoderThread.asCoroutineDispatcher()
    val bitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val encoder = withContext(encoding) { Mp4Encoder(job.out, outW, outH, job.fps) }
    var finished = false
    try {
        for (frame in 0 until total) {
            if (job.result.isCompleted) return VideoRenderHub.Outcome(false, "Stopped by the user.")
            val time = frame.toDouble() / job.fps
            view.eval("window.__hfFrame(" + "%.4f".format(java.util.Locale.US, time) + ")")
            for (attempt in 0 until 40) {
                val pending = view.eval("window.__hfPending()").toIntOrNull() ?: 0
                if (pending == 0) break
                delay(25)
            }
            view.awaitDrawn()
            bitmap.eraseColor(android.graphics.Color.BLACK)
            view.draw(canvas)
            withContext(encoding) { encoder.add(bitmap) }
            VideoRenderHub.report(VideoRenderHub.Progress(frame + 1, total, outW, outH))
        }
        withContext(encoding) { encoder.finish() }
        finished = true
    } finally {
        if (!finished) {
            withContext(encoding) { encoder.release() }
            job.out.delete()
        }
        encoding.close()
        bitmap.recycle()
    }
    val note = buildString {
        append("Rendered ").append(job.out.absolutePath).append(" (").append(job.out.length()).append(" bytes). ")
        append(outW).append('×').append(outH).append(", ").append(job.fps).append(" fps, ")
        append(total).append(" frames, ").append("%.1f".format(java.util.Locale.US, total.toDouble() / job.fps)).append(" s. ")
        if (seconds > clipped) append("The composition is longer than ${VideoRenderHub.MAX_SECONDS.toInt()} s; the rest was not rendered. ")
        if (!hasTimeline) append("No timeline was registered on window.__timelines, so nothing moved. ")
        append("The video has no sound track: audio in the composition is not mixed in.")
    }
    return VideoRenderHub.Outcome(job.out.length() > 0, note)
}

private suspend fun WebView.eval(script: String): String = withContext(Dispatchers.Main) {
    suspendCancellableCoroutine { cont ->
        evaluateJavascript(script) { value -> if (cont.isActive) cont.resume(value ?: "") }
    }
}

private suspend fun WebView.awaitDrawn() {
    withTimeoutOrNull(1_000) {
        suspendCancellableCoroutine<Unit> { cont ->
            postVisualStateCallback(System.nanoTime(), object : WebView.VisualStateCallback() {
                override fun onComplete(requestId: Long) {
                    if (cont.isActive) cont.resume(Unit)
                }
            })
        }
    }
}
