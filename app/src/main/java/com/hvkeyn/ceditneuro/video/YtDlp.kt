package com.hvkeyn.ceditneuro.video

import com.hvkeyn.ceditneuro.net.AgentNet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit

data class YtFormat(
    val id: String,
    val ext: String,
    val height: Int?,
    val width: Int?,
    val vcodec: String,
    val acodec: String,
    val filesize: Long?,
    val protocol: String,
) {
    /** An extractor may leave the codec fields out; only an explicit `none` means a separate track. */
    val muxed: Boolean
        get() = vcodec != "none" && acodec != "none"
}

data class YtInfo(
    val title: String,
    val durationSeconds: Int?,
    val thumbnail: String,
    val live: Boolean,
    val formats: List<YtFormat>,
)

/**
 * yt-dlp as the site extractor. It runs under the CPython that install_runtime puts in this
 * app. Only a muxed (picture and sound in one file) format is chosen: without ffmpeg the
 * phone cannot join a separate video track and audio track.
 */
class YtDlp(
    private val toolchain: File,
    private val net: AgentNet,
    private val allowed: () -> Boolean,
) {
    private val json = Json { ignoreUnknownKeys = true }

    fun python(): File? = listOf("python3.14", "python3", "python")
        .map { File(toolchain, "bin/$it") }
        .firstOrNull { it.isFile }

    fun script(): File? = File(toolchain, "bin/yt-dlp").takeIf { it.isFile }

    fun available(): Boolean = python() != null && script() != null

    /**
     * Download the single-file yt-dlp zipapp. Python itself is installed by install_runtime.
     * The app always starts it as `python3 yt-dlp`; the zipapp's own shebang names
     * /usr/bin/env, which does not exist on Android.
     */
    fun install(): String {
        if (!allowed()) throw IOException("Agent network is off in Settings.")
        val python = python() ?: throw IOException(
            "Python is not installed. Call install_runtime name=python first, then call download_video again.",
        )
        val target = File(toolchain, "bin/yt-dlp")
        val staging = File(toolchain, ".yt-dlp-download")
        staging.delete()
        try {
            net.download(YT_DLP_URL, staging, 16L * 1024L * 1024L, 240)
            if (staging.length() < 100_000) throw IOException("The yt-dlp download was too small.")
            if (target.exists()) target.delete()
            if (!staging.renameTo(target)) {
                staging.copyTo(target, overwrite = true)
                staging.delete()
            }
            target.setExecutable(true, true)
            val version = version() ?: "unknown"
            return "Installed yt-dlp $version for ${python.name}."
        } finally {
            staging.delete()
        }
    }

    fun version(): String? {
        if (!available()) return null
        return runCatching { run(listOf("--version"), 30, null).output.lineSequence().lastOrNull { it.isNotBlank() } }
            .getOrNull()
            ?.trim()
            ?.take(40)
    }

    suspend fun metadata(url: String, timeoutSeconds: Int = 120): YtInfo = withContext(Dispatchers.IO) {
        val args = listOf(
            "--no-config", "--no-warnings", "--no-playlist", "--no-colors",
            "--socket-timeout", "30", "--retries", "3", "--extractor-retries", "3",
            "-J", url,
        )
        val result = runInterruptible(Dispatchers.IO) {
            run(args, timeoutSeconds, null, maxOutput = 24 * 1024 * 1024)
        }
        if (result.exit != 0) throw IOException(tail(result.output).ifBlank { "yt-dlp could not read that page." })
        val root = runCatching { json.parseToJsonElement(result.output).jsonObject }
            .getOrElse { throw IOException("yt-dlp printed no JSON for that page.") }
        parseInfo(root)
    }

    suspend fun download(
        url: String,
        info: YtInfo,
        format: YtFormat,
        destDir: File,
        name: String,
        maxBytes: Long,
        timeoutSeconds: Int = 60 * 30,
        onProgress: (MediaProgress) -> Unit = {},
    ): DownloadedMedia = withContext(Dispatchers.IO) {
        destDir.mkdirs()
        val started = System.currentTimeMillis()
        val template = File(destDir, "$name.%(ext)s").absolutePath
        val args = mutableListOf(
            "--no-config", "--no-warnings", "--no-playlist", "--no-mtime", "--no-colors", "--newline",
            "--socket-timeout", "30", "--retries", "10", "--fragment-retries", "10",
            "--continue", "--no-overwrites",
            "-f", format.id,
            "-o", template,
            "--print", "after_move:CEFILE:%(filepath)s",
            "--progress-template",
            "download:CEPROG %(progress.downloaded_bytes)s %(progress.total_bytes_estimate)s " +
                "%(progress.speed)s %(progress.eta)s",
            "--max-filesize", maxBytes.toString(),
        )
        if (info.live) throw IOException("That is a live stream, not a finished video.")
        args += url
        var lastFile: String? = null
        val result = runInterruptible(Dispatchers.IO) {
            run(args, timeoutSeconds, onLine = { line ->
                when {
                    line.startsWith("CEPROG ") -> {
                        val parts = line.removePrefix("CEPROG ").trim().split(Regex("\\s+"))
                        val downloaded = parts.getOrNull(0)?.let { numeric(it) } ?: 0L
                        val total = parts.getOrNull(1)?.let { numeric(it) }?.takeIf { it > 0 }
                        val speed = parts.getOrNull(2)?.let { numeric(it) } ?: 0L
                        onProgress(MediaProgress(downloaded, total, speed))
                    }
                    line.startsWith("CEFILE:") -> lastFile = line.removePrefix("CEFILE:").trim()
                }
            })
        }
        if (result.exit != 0) {
            throw IOException(tail(result.output).ifBlank { "yt-dlp exited with ${result.exit}." })
        }
        val saved = lastFile?.let { File(it) }?.takeIf { it.isFile }
            ?: destDir.listFiles()?.filter { it.isFile && it.name.startsWith(name) }
                ?.maxByOrNull { it.lastModified() }
            ?: throw IOException("yt-dlp reported success but wrote no file.")
        if (saved.length() > maxBytes) {
            val tooBig = saved.length()
            saved.delete()
            throw IOException("The file is ${VideoSource.formatBytes(tooBig)}; the limit is ${VideoSource.formatBytes(maxBytes)}.")
        }
        onProgress(MediaProgress(saved.length(), saved.length(), maxOf(1L, saved.length() * 1000 / (System.currentTimeMillis() - started).coerceAtLeast(1))))
        DownloadedMedia(saved, saved.length(), "yt-dlp", (System.currentTimeMillis() - started) / 1000)
    }

    /** A muxed format that this phone can save without ffmpeg. */
    fun pickFormat(info: YtInfo, quality: String): YtFormat? {
        val usable = info.formats.filter { it.muxed && it.ext.lowercase(Locale.US) in CONTAINERS }
        if (usable.isEmpty()) return null
        val q = quality.lowercase(Locale.US)
        if (q == "audio") return null
        val ceiling = when {
            q.contains("1080") -> 1080
            q.contains("720") -> 720
            q.contains("480") -> 480
            q.contains("360") -> 360
            q.contains("worst") -> -1
            else -> Int.MAX_VALUE
        }
        val pool = if (ceiling == Int.MAX_VALUE) usable else usable.filter { (it.height ?: 0) <= ceiling }
        val sized = pool.ifEmpty { usable }
        return if (ceiling == -1) {
            sized.minWithOrNull(compareBy({ it.height ?: 0 }, { it.filesize ?: Long.MAX_VALUE }))
        } else {
            sized.maxWithOrNull(compareBy({ it.height ?: 0 }, { if (it.ext == "mp4") 1 else 0 }, { it.filesize ?: 0 }))
        }
    }

    fun describeFormats(info: YtInfo, limit: Int = 12): String {
        if (info.formats.isEmpty()) return "No formats."
        return info.formats
            .sortedByDescending { it.height ?: 0 }
            .take(limit)
            .joinToString("\n") { format ->
                val size = format.filesize?.let { VideoSource.formatBytes(it) } ?: "?"
                val kind = when {
                    format.muxed -> "muxed"
                    format.acodec == "none" -> "video only"
                    format.vcodec == "none" -> "audio only"
                    else -> "?"
                }
                "${format.id}: ${format.ext} ${format.height?.let { "${it}p" } ?: ""} $size $kind"
            }
    }

    private fun parseInfo(root: JsonObject): YtInfo {
        val formats = (root["formats"] as? JsonArray)?.mapNotNull { element ->
            val row = element as? JsonObject ?: return@mapNotNull null
            YtFormat(
                id = row.str("format_id"),
                ext = row.str("ext"),
                height = row["height"]?.jsonPrimitive?.intOrNull,
                width = row["width"]?.jsonPrimitive?.intOrNull,
                vcodec = row.str("vcodec"),
                acodec = row.str("acodec"),
                filesize = row["filesize"]?.jsonPrimitive?.longOrNull
                    ?: row["filesize_approx"]?.jsonPrimitive?.longOrNull,
                protocol = row.str("protocol"),
            )
        }.orEmpty()
        return YtInfo(
            title = root.str("title").ifEmpty { root.str("fulltitle") },
            durationSeconds = (root["duration"] as? kotlinx.serialization.json.JsonPrimitive)
                ?.contentOrNull?.toDoubleOrNull()?.toInt(),
            thumbnail = root.str("thumbnail"),
            live = root["is_live"]?.jsonPrimitive?.booleanOrNull == true,
            formats = formats,
        )
    }

    private fun run(
        args: List<String>,
        timeoutSeconds: Int,
        onLine: ((String) -> Unit)?,
        maxOutput: Int = 256 * 1024,
    ): RunResult {
        val python = python() ?: throw IOException("Python is not installed.")
        val script = script() ?: throw IOException("yt-dlp is not installed.")
        val command = mutableListOf(python.absolutePath, script.absolutePath)
        command += args
        val builder = ProcessBuilder(command)
            .directory(toolchain)
            .redirectErrorStream(true)
        builder.environment().apply {
            put("HOME", toolchain.absolutePath)
            put("TMPDIR", File(toolchain, "tmp").apply { mkdirs() }.absolutePath)
            put("PYTHONHOME", toolchain.absolutePath)
            put("PYTHONUNBUFFERED", "1")
            put("PYTHONIOENCODING", "utf-8")
            put("LD_LIBRARY_PATH", File(toolchain, "lib").absolutePath)
            put("SSL_CERT_FILE", File(toolchain, "etc/tls/cert.pem").absolutePath)
            put("PATH", File(toolchain, "bin").absolutePath + ":/system/bin:/system/xbin")
        }
        val process = builder.start()
        val output = StringBuilder()
        val reader = Thread {
            runCatching {
                process.inputStream.bufferedReader().use { lines ->
                    while (true) {
                        val line = lines.readLine() ?: break
                        synchronized(output) {
                            if (output.length < maxOutput) output.append(line).append('\n')
                            else if (output.length < maxOutput + 4096) output.append("[output cut]\n")
                        }
                        runCatching { onLine?.invoke(line) }
                    }
                }
            }
        }.apply {
            isDaemon = true
            name = "yt-dlp"
            start()
        }
        return try {
            val finished = process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
            if (!finished) process.destroyForcibly()
            reader.join(3_000)
            val exit = if (finished) process.exitValue() else -1
            RunResult(exit, synchronized(output) { output.toString() })
        } finally {
            if (process.isAlive) process.destroyForcibly()
        }
    }

    private fun tail(text: String): String {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotBlank() }.toList()
        val errors = lines.filter { it.startsWith("ERROR:") || it.contains("Unsupported URL", true) }
        val chosen = if (errors.isNotEmpty()) errors.takeLast(4) else lines.takeLast(6)
        return chosen.joinToString("\n").take(1200)
    }

    private fun numeric(raw: String): Long? = raw.trim().replace(",", "").toDoubleOrNull()?.toLong()

    private fun JsonObject.str(name: String): String =
        (this[name] as? kotlinx.serialization.json.JsonPrimitive)?.contentOrNull?.trim().orEmpty()

    private data class RunResult(val exit: Int, val output: String)

    companion object {
        const val YT_DLP_URL = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp"
        private val CONTAINERS = setOf("mp4", "webm", "mkv", "m4v", "3gp", "mov")
    }
}
