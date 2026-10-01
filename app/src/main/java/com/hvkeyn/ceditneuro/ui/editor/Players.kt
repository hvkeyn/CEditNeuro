package com.hvkeyn.ceditneuro.ui.editor

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.media.MediaPlayer
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Forward5
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOn
import androidx.compose.material.icons.filled.Replay5
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.automirrored.filled.VolumeOff
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import kotlinx.coroutines.delay
import java.io.File

private tailrec fun Context.activity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> null
}

/** Hides the system bars and turns the screen to the clip's orientation until [restore]. */
private class Immersion(private val activity: Activity) {
    private val before = activity.requestedOrientation

    fun enter(landscape: Boolean) {
        activity.requestedOrientation =
            if (landscape) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    fun restore() {
        activity.requestedOrientation = before
        WindowCompat.getInsetsController(activity.window, activity.window.decorView).show(WindowInsetsCompat.Type.systemBars())
    }
}

/** The page's own full-screen request: its view is laid over the whole window, and Back leaves it. */
private class PlayerChrome(private val activity: Activity?, var landscape: Boolean) : WebChromeClient() {
    private var shown: View? = null
    private var callback: CustomViewCallback? = null
    private var immersion: Immersion? = null
    private var back: OnBackPressedCallback? = null

    override fun onShowCustomView(view: View, callback: CustomViewCallback) {
        val host = activity ?: return callback.onCustomViewHidden()
        if (shown != null) return callback.onCustomViewHidden()
        val decor = host.window.decorView as? FrameLayout ?: return callback.onCustomViewHidden()
        view.setBackgroundColor(android.graphics.Color.BLACK)
        decor.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        shown = view
        this.callback = callback
        immersion = Immersion(host).also { it.enter(landscape) }
        (host as? ComponentActivity)?.let { component ->
            back = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() = exit()
            }.also { component.onBackPressedDispatcher.addCallback(it) }
        }
    }

    override fun onHideCustomView() {
        val view = shown ?: return
        (activity?.window?.decorView as? FrameLayout)?.removeView(view)
        shown = null
        callback = null
        immersion?.restore()
        immersion = null
        back?.remove()
        back = null
    }

    fun exit() {
        val pending = callback
        onHideCustomView()
        pending?.onCustomViewHidden()
    }
}

/** A HyperFrames composition played by this app's runtime, with the page's own player bar. */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun CompositionPreview(text: String, baseDir: File?, modifier: Modifier = Modifier) {
    val page = remember(text) { com.hvkeyn.ceditneuro.video.Composition.hosted(text, play = true) }
    val info = remember(text) { com.hvkeyn.ceditneuro.video.Composition.parse(text) }
    val landscape = (info?.width ?: 16) >= (info?.height ?: 9)
    AndroidView(
        modifier = modifier.fillMaxSize().background(Color.Black),
        factory = { context ->
            WebView(context).apply {
                settings.javaScriptEnabled = true
                settings.allowFileAccess = true
                settings.mediaPlaybackRequiresUserGesture = false
                settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                settings.useWideViewPort = true
                settings.loadWithOverviewMode = false
                settings.setSupportZoom(false)
                setBackgroundColor(android.graphics.Color.BLACK)
                webViewClient = WebViewClient()
                webChromeClient = PlayerChrome(context.activity(), landscape)
            }
        },
        update = { view ->
            (view.webChromeClient as? PlayerChrome)?.landscape = landscape
            if (view.tag != page) {
                view.tag = page
                val base = baseDir?.let { "file://" + it.absolutePath + "/" }
                view.loadDataWithBaseURL(base, page, "text/html", "utf-8", null)
            }
        },
        onRelease = { view ->
            (view.webChromeClient as? PlayerChrome)?.exit()
            view.stopLoading()
            view.loadUrl("about:blank")
            view.destroy()
        },
    )
}

/** Sizes its one child to the largest box of [aspect] that fits, centered. */
private class FitFrame(context: Context) : FrameLayout(context) {
    var aspect = 16f / 9f
        set(value) {
            if (value > 0f && value != field) { field = value; requestLayout() }
        }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        var cw = w
        var ch = (w / aspect).toInt()
        if (ch > h) { ch = h; cw = (h * aspect).toInt() }
        for (i in 0 until childCount) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY))
        }
        setMeasuredDimension(w, h)
    }
}

private class PlayerState {
    var position by mutableLongStateOf(0L)
    var duration by mutableLongStateOf(0L)
    var playing by mutableStateOf(true)
    var speed by mutableFloatStateOf(1f)
    var loop by mutableStateOf(true)
    var muted by mutableStateOf(false)
    var width by mutableIntStateOf(16)
    var height by mutableIntStateOf(9)
    var failed by mutableStateOf(false)
}

private val SPEEDS = listOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

private fun clock(ms: Long): String {
    val s = (ms / 1000).coerceAtLeast(0)
    return "%d:%02d".format(s / 60, s % 60)
}

/** A finished video with player controls: seek, five-second jumps, speed, loop, mute, and full screen. */
@Composable
fun VideoPreview(file: File, modifier: Modifier = Modifier) {
    val state = remember(file.path, file.lastModified()) { PlayerState() }
    var fullscreen by remember(file.path) { mutableStateOf(false) }
    if (!fullscreen) {
        VideoSurface(file, state, modifier, fullscreen = false, onFullscreen = { fullscreen = true })
    } else {
        Box(modifier.fillMaxSize().background(Color.Black))
        val activity = LocalContext.current.activity()
        DisposableEffect(Unit) {
            val decor = activity?.window?.decorView as? FrameLayout
            if (activity == null || decor == null) {
                fullscreen = false
                return@DisposableEffect onDispose {}
            }
            val immersion = Immersion(activity).also { it.enter(state.width >= state.height) }
            val layer = androidx.compose.ui.platform.ComposeView(activity).apply {
                setContent {
                    VideoSurface(file, state, Modifier.fillMaxSize(), fullscreen = true, onFullscreen = { fullscreen = false })
                }
            }
            decor.addView(layer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
            val back = object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() { fullscreen = false }
            }
            (activity as? ComponentActivity)?.onBackPressedDispatcher?.addCallback(back)
            onDispose {
                back.remove()
                decor.removeView(layer)
                immersion.restore()
            }
        }
    }
}

@Composable
private fun VideoSurface(
    file: File,
    state: PlayerState,
    modifier: Modifier,
    fullscreen: Boolean,
    onFullscreen: () -> Unit,
) {
    var player by remember { mutableStateOf<MediaPlayer?>(null) }
    var video by remember { mutableStateOf<android.widget.VideoView?>(null) }
    var controls by remember { mutableStateOf(true) }
    var touched by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var speedMenu by remember { mutableStateOf(false) }

    fun applySpeed() {
        val mp = player ?: return
        if (!state.playing) return
        runCatching { mp.playbackParams = mp.playbackParams.setSpeed(state.speed) }
    }
    fun wake() { touched = System.currentTimeMillis(); controls = true }
    fun seek(ms: Long) {
        val target = ms.coerceIn(0L, state.duration.coerceAtLeast(0L))
        state.position = target
        player?.seekTo(target, MediaPlayer.SEEK_CLOSEST) ?: video?.seekTo(target.toInt())
        wake()
    }
    fun setPlaying(on: Boolean) {
        val view = video ?: return
        if (on && state.duration > 0 && state.position >= state.duration - 100) seek(0)
        state.playing = on
        if (on) { view.start(); applySpeed() } else view.pause()
        wake()
    }

    LaunchedEffect(video) {
        while (true) {
            val view = video
            if (view != null && player != null) {
                state.position = view.currentPosition.toLong()
                if (!view.isPlaying && state.playing && !state.loop && state.duration > 0 &&
                    state.position >= state.duration - 250
                ) {
                    state.playing = false
                }
            }
            if (state.playing && System.currentTimeMillis() - touched > 3000 && !speedMenu) controls = false
            delay(200)
        }
    }

    Box(
        modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                if (controls) setPlaying(!state.playing) else wake()
            },
    ) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                FitFrame(context).apply {
                    setBackgroundColor(android.graphics.Color.BLACK)
                    aspect = state.width.toFloat() / state.height
                    val frame = this
                    val view = android.widget.VideoView(context)
                    view.setOnPreparedListener { mp ->
                        player = mp
                        state.failed = false
                        state.duration = mp.duration.toLong().coerceAtLeast(0L)
                        state.width = mp.videoWidth.coerceAtLeast(1)
                        state.height = mp.videoHeight.coerceAtLeast(1)
                        frame.aspect = state.width.toFloat() / state.height
                        mp.isLooping = state.loop
                        mp.setVolume(if (state.muted) 0f else 1f, if (state.muted) 0f else 1f)
                        if (state.position > 0) mp.seekTo(state.position, MediaPlayer.SEEK_CLOSEST)
                        if (state.playing) {
                            view.start()
                            runCatching { mp.playbackParams = mp.playbackParams.setSpeed(state.speed) }
                        }
                    }
                    view.setOnCompletionListener { if (!state.loop) state.playing = false }
                    view.setOnErrorListener { _, _, _ ->
                        state.failed = true
                        state.playing = false
                        true
                    }
                    addView(
                        view,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT,
                            android.view.Gravity.CENTER,
                        ),
                    )
                    tag = view
                    video = view
                }
            },
            update = { frame ->
                val view = frame.tag as android.widget.VideoView
                val key = file.path + "@" + file.lastModified()
                if (view.tag != key) {
                    view.tag = key
                    player = null
                    val shared = runCatching {
                        androidx.core.content.FileProvider.getUriForFile(view.context, view.context.packageName + ".files", file)
                    }.getOrNull()
                    if (shared != null) view.setVideoURI(shared) else view.setVideoPath(file.absolutePath)
                }
            },
            onRelease = { frame ->
                (frame.tag as? android.widget.VideoView)?.let { view ->
                    state.position = view.currentPosition.toLong().takeIf { it > 0 } ?: state.position
                    view.stopPlayback()
                }
                player = null
                video = null
            },
        )
        if (state.failed) {
            Text(
                "This video cannot be played. The file is damaged or was not finished; render it again.",
                color = Color.White,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        } else if (controls) {
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xD9000000))))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { wake() }
                    .padding(horizontal = 8.dp, vertical = if (fullscreen) 12.dp else 4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Slider(
                        value = state.position.toFloat(),
                        onValueChange = { seek(it.toLong()) },
                        valueRange = 0f..state.duration.coerceAtLeast(1L).toFloat(),
                        colors = SliderDefaults.colors(
                        thumbColor = Color.White,
                        activeTrackColor = Color(0xFF4C8DFF),
                        inactiveTrackColor = Color(0x55FFFFFF),
                    ),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        "${clock(state.position)} / ${clock(state.duration)}",
                        color = Color.White,
                        fontSize = 13.sp,
                        modifier = Modifier.padding(start = 8.dp),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(0.dp)) {
                    if (fullscreen) IconButton(onClick = { seek(0) }) { Icon(Icons.Filled.SkipPrevious, "Start", tint = Color.White) }
                    IconButton(onClick = { seek(state.position - 5000) }) { Icon(Icons.Filled.Replay5, "Back 5 seconds", tint = Color.White) }
                    IconButton(onClick = { setPlaying(!state.playing) }) {
                        Icon(if (state.playing) Icons.Filled.Pause else Icons.Filled.PlayArrow, if (state.playing) "Pause" else "Play", tint = Color.White)
                    }
                    IconButton(onClick = { seek(state.position + 5000) }) { Icon(Icons.Filled.Forward5, "Forward 5 seconds", tint = Color.White) }
                    Spacer(Modifier.weight(1f))
                    Box {
                        TextButton(onClick = { speedMenu = true; wake() }) {
                            Text(SPEEDS.firstOrNull { it == state.speed }?.let { "${it}×".replace(".0×", "×") } ?: "1×", color = Color.White)
                        }
                        DropdownMenu(expanded = speedMenu, onDismissRequest = { speedMenu = false }) {
                            for (speed in SPEEDS) {
                                DropdownMenuItem(
                                    text = { Text("${speed}×".replace(".0×", "×")) },
                                    onClick = {
                                        state.speed = speed
                                        speedMenu = false
                                        applySpeed()
                                        wake()
                                    },
                                )
                            }
                        }
                    }
                    IconButton(onClick = { state.loop = !state.loop; player?.isLooping = state.loop; wake() }) {
                        Icon(if (state.loop) Icons.Filled.RepeatOn else Icons.Filled.Repeat, "Loop", tint = Color.White)
                    }
                    IconButton(onClick = {
                        state.muted = !state.muted
                        val v = if (state.muted) 0f else 1f
                        player?.setVolume(v, v)
                        wake()
                    }) {
                        Icon(if (state.muted) Icons.AutoMirrored.Filled.VolumeOff else Icons.AutoMirrored.Filled.VolumeUp, "Sound", tint = Color.White)
                    }
                    IconButton(onClick = onFullscreen) {
                        Icon(if (fullscreen) Icons.Filled.FullscreenExit else Icons.Filled.Fullscreen, "Full screen", tint = Color.White)
                    }
                }
            }
        }
    }
}
