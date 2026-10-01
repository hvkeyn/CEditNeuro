package com.hvkeyn.ceditneuro.ui.editor

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** image, video, svg, html, or null when the file is only text. */
fun previewKind(path: String): String? = when (path.substringAfterLast('.', "").lowercase()) {
    "png", "jpg", "jpeg", "webp", "gif", "bmp" -> "image"
    "mp4", "webm", "3gp", "mkv", "m4v" -> "video"
    "svg" -> "svg"
    "html", "htm" -> "html"
    else -> null
}

/** Height divided by width, so a scheme can use the full pane width without cropping. */
internal fun svgAspect(markup: String): Float {
    val width = Regex("""\bwidth\s*=\s*"([\d.]+)""").find(markup)?.groupValues?.get(1)?.toFloatOrNull()
    val height = Regex("""\bheight\s*=\s*"([\d.]+)""").find(markup)?.groupValues?.get(1)?.toFloatOrNull()
    if (width == null || height == null || width < 1f) return 0.62f
    return (height / width).coerceIn(0.28f, 1.7f)
}

/** Wraps markup so a scheme fills the frame and keeps its colors on a white page. */
internal fun previewPage(kind: String, text: String): String = when (kind) {
    "svg" -> "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1, minimum-scale=1, maximum-scale=8, user-scalable=yes\">" +
        "<style>html,body{margin:0;background:#fff}svg{display:block;width:100%;height:auto}</style>" +
        "</head><body>$text</body></html>"
    else -> if (com.hvkeyn.ceditneuro.video.Composition.isComposition(text)) {
        com.hvkeyn.ceditneuro.video.Composition.hosted(text, play = true)
    } else {
        text
    }
}

@Composable
fun ImagePreview(file: File, modifier: Modifier = Modifier) {
    val bitmap by produceState<android.graphics.Bitmap?>(null, file.path, file.lastModified()) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(file.path, bounds)
                var sample = 1
                while (bounds.outWidth / sample > 4096 || bounds.outHeight / sample > 4096) sample *= 2
                BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample })
            }.getOrNull()
        }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var enlarged by remember { mutableStateOf(false) }
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFF202124))
            .pointerInput(file.path) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 8f)
                    offset = if (scale == 1f) Offset.Zero else offset + pan
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        val shown = bitmap
        if (shown == null) {
            Text("Cannot show this image.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Image(
                bitmap = shown.asImageBitmap(),
                contentDescription = file.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        scaleX = scale
                        scaleY = scale
                        translationX = offset.x
                        translationY = offset.y
                    },
            )
        }
        IconButton(
            onClick = { enlarged = true },
            modifier = Modifier.align(Alignment.TopEnd).padding(8.dp),
        ) {
            Icon(Icons.Filled.ZoomIn, contentDescription = "Enlarge", tint = Color.White)
        }
    }
    if (enlarged && bitmap != null) {
        com.hvkeyn.ceditneuro.ui.PictureStage(onClose = { enlarged = false }) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = file.name,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/**
 * A scheme or HTML page. A tap opens it; inside that view a pinch zooms, because a
 * WebView sits above Compose and would swallow a gesture drawn on top of it.
 */
@Composable
fun ExpandableMarkup(
    kind: String,
    text: String,
    baseDir: File?,
    modifier: Modifier = Modifier,
    onZone: ((Float) -> Unit)? = null,
    onFlip: ((Int) -> Unit)? = null,
) {
    var open by remember { mutableStateOf(false) }
    MarkupPreview(
        kind,
        text,
        baseDir,
        modifier,
        onTap = { open = true },
        allowZoom = false,
        onZone = onZone,
        onFlip = onFlip,
    )
    if (open) {
        com.hvkeyn.ceditneuro.ui.WebPictureStage(onClose = { open = false }) {
            MarkupPreview(kind, text, baseDir, Modifier.fillMaxSize(), allowZoom = true)
        }
    }
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun MarkupPreview(
    kind: String,
    text: String,
    baseDir: File?,
    modifier: Modifier = Modifier,
    onTap: (() -> Unit)? = null,
    allowZoom: Boolean = false,
    onZone: ((Float) -> Unit)? = null,
    onFlip: ((Int) -> Unit)? = null,
) {
    val page = remember(kind, text) { previewPage(kind, text) }
    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { context ->
            PreviewWeb(context).apply {
                settings.javaScriptEnabled = false
                settings.allowFileAccess = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.displayZoomControls = false
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = false
                setBackgroundColor(android.graphics.Color.WHITE)
            }
        },
        update = { view ->
            view.onTap = onTap
            view.onZone = onZone
            view.onFlip = onFlip
            view.zoomEnabled = allowZoom
            val animate = kind != "svg" && text.contains("data-composition-id")
            view.settings.javaScriptEnabled = animate
            if (animate) {
                view.settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
            }
            if (view.tag != page) {
                view.tag = page
                if (kind == "svg") {
                    // Base64 keeps url(#marker) arrows intact. A plain load treats '#' as a fragment.
                    val encoded = Base64.encodeToString(page.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
                    view.loadData(encoded, "text/html", "base64")
                } else {
                    val base = baseDir?.let { "file://" + it.absolutePath + "/" }
                    view.loadDataWithBaseURL(base, page, "text/html", "utf-8", null)
                }
            }
        },
    )
}

/** Tap opens the picture. While zoom is on, the WebView keeps the pinch for itself. */
@SuppressLint("ClickableViewAccessibility")
private class PreviewWeb(context: Context) : WebView(context) {
    var onTap: (() -> Unit)? = null
    var onZone: ((Float) -> Unit)? = null
    var onFlip: ((Int) -> Unit)? = null
    var zoomEnabled: Boolean = false
        set(value) {
            field = value
            settings.setSupportZoom(value)
            settings.builtInZoomControls = value
            settings.displayZoomControls = false
        }
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var moved = false
    private var lastTap = 0L

    init {
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                settings.setSupportZoom(zoomEnabled)
                settings.builtInZoomControls = zoomEnabled
                settings.displayZoomControls = false
            }
        }
        setOnTouchListener { _, event ->
            if (zoomEnabled) return@setOnTouchListener false
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.x
                    downY = event.y
                    moved = false
                }
                MotionEvent.ACTION_POINTER_DOWN -> moved = true
                MotionEvent.ACTION_MOVE -> {
                    if (kotlin.math.abs(event.x - downX) > slop || kotlin.math.abs(event.y - downY) > slop) {
                        moved = true
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (!moved) {
                        val now = event.eventTime
                        if (now - lastTap < 320L) {
                            onTap?.invoke()
                            lastTap = 0L
                        } else {
                            lastTap = now
                            val zone = onZone
                            if (zone != null) zone(event.x / width.coerceAtLeast(1))
                            else onTap?.invoke()
                        }
                    } else if (kotlin.math.abs(dx) > slop * 3 && kotlin.math.abs(dx) > kotlin.math.abs(dy)) {
                        onFlip?.invoke(if (dx < 0f) 1 else -1)
                    }
                }
            }
            true
        }
    }
}
