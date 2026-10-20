package com.hvkeyn.ceditneuro.video

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import com.hvkeyn.ceditneuro.net.NetProxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

data class MediaProgress(
    val receivedBytes: Long,
    val totalBytes: Long?,
    val speedBytesPerSecond: Long,
    val label: String = "",
) {
    val percent: Int?
        get() = totalBytes?.takeIf { it > 0 }?.let { ((receivedBytes * 100) / it).toInt().coerceIn(0, 100) }
}

data class DownloadedMedia(
    val file: File,
    val bytes: Long,
    val kind: String,
    val seconds: Long,
    val note: String = "",
)

data class FetchedPage(
    val url: String,
    val contentType: String,
    val body: ByteArray,
) {
    fun text(): String = body.toString(Charsets.UTF_8)
}

/**
 * Downloads one media address into the project. A direct file is streamed and can resume
 * from its `.part`. An HLS playlist is resolved here, including AES-128 segments; a TS
 * result is remuxed to MP4 with the phone's own extractor, because the preview plays MP4.
 */
class VideoDownloader(
    private val proxy: () -> NetProxy = { NetProxy() },
) {

    private val base: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(45, TimeUnit.SECONDS)
        .build()

    private fun client(): OkHttpClient {
        val chosen = proxy()
        val jump = chosen.javaProxy() ?: return base
        chosen.applyCredentials()
        return base.newBuilder().proxy(jump).build()
    }

    /** A page, a manifest, or a JSON response. */
    fun fetch(url: String, headers: Map<String, String> = emptyMap(), maxBytes: Long = MAX_TEXT): FetchedPage {
        val request = Request.Builder()
            .url(url)
            .header("User-Agent", BROWSER_AGENT)
            .header("Accept", "text/html,application/xhtml+xml,application/json,application/vnd.apple.mpegurl,*/*")
            .apply { headers.forEach { (name, value) -> header(name, value) } }
            .build()
        client().newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            val body = response.body ?: throw IOException("Empty response from $url")
            val length = body.contentLength()
            if (length > maxBytes) throw IOException("Response is $length bytes, limit is $maxBytes.")
            val bytes = body.byteStream().use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    if (total > maxBytes) throw IOException("Response exceeded $maxBytes bytes.")
                    out.write(buffer, 0, read)
                }
                out.toByteArray()
            }
            return FetchedPage(
                url = response.request.url.toString(),
                contentType = body.contentType()?.toString().orEmpty(),
                body = bytes,
            )
        }
    }

    /**
     * A direct media file. A partial file is continued with a Range request. The final
     * name is written only after every byte arrived.
     */
    suspend fun downloadFile(
        link: MediaLink,
        dest: File,
        maxBytes: Long,
        onProgress: (MediaProgress) -> Unit = {},
    ): DownloadedMedia = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, dest.name + ".part")
        var offset = if (part.isFile) part.length() else 0L
        if (offset > 0 && offset >= maxBytes) {
            throw IOException("The partial file is already $offset bytes; the limit is $maxBytes.")
        }
        val request = Request.Builder()
            .url(link.url)
            .header("User-Agent", BROWSER_AGENT)
            .header("Accept", "*/*")
            .apply {
                link.headers.forEach { (name, value) -> header(name, value) }
                if (offset > 0) header("Range", "bytes=$offset-")
            }
            .build()

        client().newCall(request).execute().use { response ->
            if (response.code == 416) {
                val total = response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()
                if (total != null && part.isFile && part.length() == total) {
                    replace(part, dest)
                    return@withContext DownloadedMedia(dest, dest.length(), "file", elapsed(started))
                }
                throw IOException("That part cannot be resumed (HTTP 416).")
            }
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for ${link.url}")
            val body = response.body ?: throw IOException("Empty response from ${link.url}")
            val type = body.contentType()?.toString().orEmpty()
            if (type.startsWith("text/html", ignoreCase = true)) {
                throw IOException("That address is a web page, not a media file.")
            }
            val remaining = body.contentLength()
            val total = if (remaining >= 0) offset + remaining else null
            if (total != null && total > maxBytes) {
                throw IOException("The file is ${VideoSource.formatBytes(total)}; the limit is ${VideoSource.formatBytes(maxBytes)}.")
            }
            if (response.code == 200) offset = 0L
            if (offset > 0 && response.code != 206) {
                if (part.isFile && !part.delete()) throw IOException("Could not restart ${part.name}.")
                offset = 0L
            }
            if (offset == 0L && part.isFile) part.delete()
            var written = offset
            var lastReport = 0L
            val writeStarted = System.currentTimeMillis()
            val output = RandomAccessFile(part, "rw")
            try {
                output.seek(offset)
                body.byteStream().use { input ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        written += read
                        if (written > maxBytes) throw IOException("The download passed $maxBytes bytes.")
                        val now = System.currentTimeMillis()
                        if (now - lastReport >= 500) {
                            lastReport = now
                            onProgress(progress(written, total, writeStarted, read = written - offset))
                        }
                    }
                }
                output.fd.sync()
            } finally {
                output.close()
            }
            if (total != null && written < total) {
                throw IOException("The server stopped at $written of $total bytes. Run the same call to continue.")
            }
            onProgress(progress(written, total ?: written, writeStarted, read = written - offset))
            replace(part, dest)
            DownloadedMedia(dest, dest.length(), "file", elapsed(started))
        }
    }

    /** An HLS stream: variants, segments, AES-128 keys, and a TS remux when needed. */
    suspend fun downloadHls(
        link: MediaLink,
        dest: File,
        quality: String,
        maxBytes: Long,
        onProgress: (MediaProgress) -> Unit = {},
    ): DownloadedMedia = withContext(Dispatchers.IO) {
        val started = System.currentTimeMillis()
        dest.parentFile?.mkdirs()
        val first = fetch(link.url, link.headers)
        var playlist = VideoSource.parseHls(first.text(), first.url)
        var separateAudio = false
        if (playlist.drm.isNotBlank()) throw IOException("${playlist.drm}; this phone does not bypass it.")
        if (playlist.master) {
            separateAudio = playlist.variants.any { it.audio }
            val variant = VideoSource.chooseVariant(playlist, quality)
                ?: throw IOException("That master playlist has no usable variant.")
            val next = fetch(variant.uri, link.headers)
            playlist = VideoSource.parseHls(next.text(), next.url)
            if (playlist.drm.isNotBlank()) throw IOException("${playlist.drm}; this phone does not bypass it.")
            if (playlist.master) throw IOException("That playlist points to another master playlist.")
        }
        if (!playlist.ended && !playlist.vod) throw IOException("That is a live stream. Only a finished recording is saved.")
        if (playlist.segments.isEmpty()) throw IOException("The playlist has no segments.")

        val extension = segmentExtension(playlist)
        val target = if (dest.extension.lowercase(Locale.US) == extension) dest else File(dest.parentFile, dest.nameWithoutExtension + "." + extension)
        val part = File(target.parentFile, target.name + ".part")
        part.delete()
        var written = 0L
        var lastReport = 0L
        val keys = HashMap<String, ByteArray>()
        val output = part.outputStream()
        try {
            playlist.initUri?.let { init ->
                val data = fetch(init, link.headers).body
                output.write(data)
                written += data.size
            }
            val totalSegments = playlist.segments.size
            playlist.segments.forEachIndexed { index, segment ->
                currentCoroutineContext().ensureActive()
                val segmentHeaders = if (segment.range == null) link.headers else link.headers + ("Range" to segment.range)
                val page = fetch(segment.uri, segmentHeaders)
                var bytes = page.body
                val key = segment.key
                if (key != null && key.method == "AES-128") {
                    if (key.uri.isBlank()) throw IOException("An AES-128 segment has no key address.")
                    val keyBytes = keys.getOrPut(key.uri) { fetch(key.uri, link.headers, MAX_KEY).body }
                    bytes = decrypt(bytes, keyBytes, key.iv ?: sequenceIv(segment.sequence))
                } else if (key != null && key.method != "NONE") {
                    throw IOException("${key.method} encryption; this phone does not bypass it.")
                }
                output.write(bytes)
                written += bytes.size
                if (written > maxBytes) throw IOException("The stream passed ${VideoSource.formatBytes(maxBytes)}.")
                val now = System.currentTimeMillis()
                if (now - lastReport >= 500 || index == totalSegments - 1) {
                    lastReport = now
                    onProgress(
                        MediaProgress(
                            receivedBytes = written,
                            totalBytes = null,
                            speedBytesPerSecond = speed(written, started),
                            label = "${index + 1}/$totalSegments segments",
                        ),
                    )
                }
            }
            output.flush()
        } finally {
            output.close()
        }
        if (!part.isFile || part.length() == 0L) throw IOException("The stream produced no bytes.")
        val note = StringBuilder()
        if (separateAudio && quality.lowercase(Locale.US) != "audio") {
            note.append("The master lists a separate audio track; this file holds the picture track. ")
        }
        var finalFile = target
        if (extension == "ts") {
            val mp4 = File(target.parentFile, target.nameWithoutExtension + ".mp4")
            if (runCatching { TsRemux.toMp4(part, mp4) }.getOrDefault(false)) {
                part.delete()
                finalFile = mp4
                note.append("MPEG-TS remuxed to MP4 on the phone.")
            } else {
                replace(part, target)
                finalFile = target
                note.append("Saved as MPEG-TS; the preview may not play it.")
            }
        } else {
            replace(part, target)
        }
        onProgress(MediaProgress(written, written, speed(written, started), "done"))
        DownloadedMedia(finalFile, finalFile.length(), "hls", elapsed(started), note.toString())
    }

    private fun segmentExtension(playlist: HlsPlaylist): String {
        val last = playlist.segments.last().uri.substringBefore('?').substringAfterLast('.', "")
        return when (last.lowercase(Locale.US)) {
            "m4s", "mp4", "m4v", "cmfv" -> "mp4"
            "aac" -> "aac"
            "mp3" -> "mp3"
            else -> "ts"
        }
    }

    private fun decrypt(data: ByteArray, key: ByteArray, iv: ByteArray): ByteArray {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.DECRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return cipher.doFinal(data)
    }

    private fun sequenceIv(sequence: Long): ByteArray {
        val iv = ByteArray(16)
        var value = sequence
        for (index in 15 downTo 0) {
            iv[index] = (value and 0xFF).toByte()
            value = value shr 8
        }
        return iv
    }

    private fun replace(from: File, to: File) {
        if (to.exists() && !to.delete()) throw IOException("Could not replace ${to.name}.")
        if (!from.renameTo(to)) {
            from.copyTo(to, overwrite = true)
            from.delete()
        }
    }

    private fun progress(received: Long, total: Long?, started: Long, read: Long): MediaProgress =
        MediaProgress(received, total, speed(received, started), if (total == null) VideoSource.formatBytes(received) else "")

    private fun speed(bytes: Long, started: Long): Long {
        val seconds = ((System.currentTimeMillis() - started).coerceAtLeast(250)) / 1000.0
        return (bytes / seconds).toLong()
    }

    private fun elapsed(started: Long): Long = (System.currentTimeMillis() - started) / 1000

    companion object {
        const val MAX_TEXT = 8L * 1024 * 1024
        const val MAX_KEY = 64L * 1024
        val BROWSER_AGENT: String =
            "Mozilla/5.0 (Linux; Android ${android.os.Build.VERSION.RELEASE}; ${android.os.Build.MODEL}) " +
                "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36"
    }
}

/** MediaExtractor + MediaMuxer, so a downloaded TS opens in the app's MP4 preview. */
object TsRemux {
    fun toMp4(ts: File, out: File): Boolean {
        if (out.exists()) out.delete()
        val extractor = MediaExtractor()
        val muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var started = false
        var stopped = false
        return try {
            extractor.setDataSource(ts.absolutePath)
            val tracks = HashMap<Int, Int>()
            var biggest = 0
            for (index in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(index)
                val mime = format.getString(MediaFormat.KEY_MIME).orEmpty()
                if (!mime.startsWith("video/") && !mime.startsWith("audio/")) continue
                extractor.selectTrack(index)
                tracks[index] = muxer.addTrack(format)
                if (format.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) {
                    biggest = maxOf(biggest, format.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE))
                }
            }
            if (tracks.isEmpty()) return false
            muxer.start()
            started = true
            val buffer = ByteBuffer.allocateDirect(maxOf(biggest, 2 * 1024 * 1024))
            val info = MediaCodec.BufferInfo()
            while (true) {
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val track = extractor.sampleTrackIndex
                val mapped = tracks[track]
                if (mapped != null) {
                    info.set(0, size, extractor.sampleTime, extractor.sampleFlags)
                    muxer.writeSampleData(mapped, buffer, info)
                }
                extractor.advance()
            }
            muxer.stop()
            stopped = true
            out.isFile && out.length() > 0
        } catch (_: Exception) {
            out.delete()
            false
        } finally {
            if (started && !stopped) runCatching { muxer.stop() }
            runCatching { muxer.release() }
            runCatching { extractor.release() }
        }
    }
}
