package com.hvkeyn.ceditneuro.video

import com.hvkeyn.ceditneuro.net.AgentNet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.net.URLDecoder
import java.util.Locale

data class InspectResult(
    val info: MediaInfo,
    val ytInfo: YtInfo? = null,
    val engine: String = "",
)

data class DownloadReport(
    val title: String,
    val description: String,
    val file: File,
    val bytes: Long,
    val seconds: Long,
    val engine: String,
    val note: String = "",
)

/**
 * Chooses how a page is read and downloaded: yt-dlp for the sites it knows, a native
 * downloader for a direct file or an HLS stream. A DRM or login wall is a refusal.
 */
class VideoEngine(
    private val toolchain: File,
    private val net: AgentNet,
    private val allowed: () -> Boolean,
    private val downloader: VideoDownloader = VideoDownloader(),
) {
    val ytDlp = YtDlp(toolchain, net, allowed)

    suspend fun inspect(url: String, allowYtDlpInstall: Boolean = false, installPython: suspend () -> Unit = {}): InspectResult =
        withContext(Dispatchers.IO) {
            requireHttp(url)
            val direct = VideoSource.kindOf(url)
            if (direct == MediaKind.FILE) {
                return@withContext InspectResult(
                    info = MediaInfo(pageUrl = url, title = titleFromUrl(url), links = listOf(fileLink(url))),
                    engine = "direct",
                )
            }
            if (direct == MediaKind.HLS) {
                val playlist = readHls(url)
                return@withContext InspectResult(
                    info = MediaInfo(
                        pageUrl = url,
                        title = titleFromUrl(url),
                        links = hlsLinks(url, playlist),
                        engine = "hls",
                        note = playlist.drm,
                    ),
                    engine = "hls",
                )
            }

            val host = AgentNet.parseUrl(url).host.lowercase(Locale.US)
            if (isExtractorHost(host) && ensureEngine(allowYtDlpInstall, installPython)) {
                runCatching { ytDlp.metadata(url) }.getOrNull()
                    ?.takeIf { it.formats.isNotEmpty() }
                    ?.let { info ->
                        return@withContext InspectResult(
                            info = MediaInfo(
                                pageUrl = url,
                                title = info.title,
                                durationSeconds = info.durationSeconds,
                                thumbnail = info.thumbnail,
                                engine = "yt-dlp",
                            ),
                            ytInfo = info,
                            engine = "yt-dlp",
                        )
                    }
            }

            val page = runCatching { downloader.fetch(url, maxBytes = VideoDownloader.MAX_TEXT) }.getOrNull()
            val links = page?.let { VideoSource.pageLinks(it.text(), it.url) }.orEmpty().map { enrich(it) }
            val title = page?.let { VideoSource.titleFromHtml(it.text()) }.orEmpty().ifEmpty { titleFromUrl(url) }
            if (links.isNotEmpty()) {
                return@withContext InspectResult(
                    info = MediaInfo(
                        pageUrl = page?.url ?: url,
                        title = title,
                        thumbnail = thumbnailFromPage(page?.text().orEmpty(), url),
                        links = links,
                        engine = "page",
                    ),
                    engine = "page",
                )
            }

            if (ensureEngine(allowYtDlpInstall, installPython)) {
                return@withContext runCatching { ytDlp.metadata(url) }.fold(
                    onSuccess = { info ->
                        InspectResult(
                            info = MediaInfo(
                                pageUrl = url,
                                title = info.title,
                                durationSeconds = info.durationSeconds,
                                thumbnail = info.thumbnail,
                                engine = "yt-dlp",
                            ),
                            ytInfo = info,
                            engine = "yt-dlp",
                        )
                    },
                    onFailure = { error -> throw IOException(explain(error, url)) },
                )
            }
            throw IOException("No media link was printed by that page, and the yt-dlp engine is not installed.")
        }

    suspend fun download(
        url: String,
        quality: String,
        maxBytes: Long,
        destDir: File,
        name: String? = null,
        allowYtDlpInstall: Boolean = false,
        installPython: suspend () -> Unit = {},
        onProgress: (MediaProgress) -> Unit = {},
    ): DownloadReport = withContext(Dispatchers.IO) {
        requireHttp(url)
        if (!destDir.isDirectory && !destDir.mkdirs()) throw IOException("Could not create ${destDir.name}.")
        if (destDir.usableSpace < MIN_FREE) {
            throw IOException("The phone has less than ${VideoSource.formatBytes(MIN_FREE)} free on that storage.")
        }

        val direct = VideoSource.kindOf(url)
        if (direct == MediaKind.FILE) {
            val link = fileLink(url)
            val title = name ?: titleFromUrl(url)
            val dest = destination(destDir, title, link.ext)
            return@withContext finish(title, downloader.downloadFile(link, dest, maxBytes, onProgress), "direct")
        }
        if (direct == MediaKind.HLS) {
            val playlist = readHls(url)
            if (playlist.drm.isNotBlank()) throw IOException("${playlist.drm}; this phone does not bypass it.")
            val link = MediaLink(url, MediaKind.HLS, ext = "ts")
            val title = name ?: titleFromUrl(url)
            val dest = destination(destDir, title, "mp4")
            return@withContext finish("", downloader.downloadHls(link, dest, quality, maxBytes, onProgress), "hls")
        }

        val host = AgentNet.parseUrl(url).host.lowercase(Locale.US)
        if (isExtractorHost(host) && ensureEngine(allowYtDlpInstall, installPython)) {
            val info = runCatching { ytDlp.metadata(url) }.getOrNull()
            if (info != null) {
                val attempt = runCatching { downloadWithYtDlp(url, info, quality, maxBytes, destDir, name, onProgress) }
                attempt.getOrNull()?.let { return@withContext it }
                if (attempt.exceptionOrNull() != null && attempt.exceptionOrNull() !is NoMuxedFormat) {
                    throw IOException(explain(attempt.exceptionOrNull()!!, url))
                }
            }
        }

        val page = runCatching { downloader.fetch(url, maxBytes = VideoDownloader.MAX_TEXT) }.getOrNull()
        val links = page?.let { VideoSource.pageLinks(it.text(), it.url) }.orEmpty().map { enrich(it) }
        if (links.isNotEmpty()) {
            val link = choose(links, quality) ?: throw IOException(
                if (quality.equals("audio", ignoreCase = true)) {
                    "That page prints no audio-only stream. Choose best or a height."
                } else {
                    "That page printed media links, but none matches quality '$quality'."
                },
            )
            val title = name ?: page?.let { VideoSource.titleFromHtml(it.text()) }.orEmpty()
                .ifEmpty { titleFromUrl(url) }
            val dest = destination(destDir, title, if (link.kind == MediaKind.HLS) "mp4" else link.ext)
            val outcome = if (link.kind == MediaKind.HLS) {
                downloader.downloadHls(link, dest, quality, maxBytes, onProgress)
            } else {
                downloader.downloadFile(link, dest, maxBytes, onProgress)
            }
            return@withContext finish(title, outcome, "page")
        }

        if (ensureEngine(allowYtDlpInstall, installPython)) {
            val info = ytDlp.metadata(url)
            return@withContext downloadWithYtDlp(url, info, quality, maxBytes, destDir, name, onProgress)
        }
        throw IOException("No media link was found. Call install_runtime name=python, then try again.")
    }

    private suspend fun downloadWithYtDlp(
        url: String,
        info: YtInfo,
        quality: String,
        maxBytes: Long,
        destDir: File,
        name: String?,
        onProgress: (MediaProgress) -> Unit,
    ): DownloadReport {
        val chosen = if (quality.lowercase(Locale.US) == "audio") {
            pickAudio(info) ?: throw NoMuxedFormat(info)
        } else {
            ytDlp.pickFormat(info, quality) ?: throw NoMuxedFormat(info)
        }
        val title = name ?: info.title.ifBlank { titleFromUrl(url) }
        val base = VideoSource.safeFileName(title)
        val outcome = ytDlp.download(url, info, chosen, destDir, uniqueBase(destDir, base), maxBytes, onProgress = onProgress)
        val note = if (chosen.muxed) "" else "Audio only."
        return DownloadReport(
            title = info.title.ifBlank { base },
            description = "${chosen.ext} ${chosen.height?.let { "${it}p" } ?: ""}".trim(),
            file = outcome.file,
            bytes = outcome.bytes,
            seconds = outcome.seconds,
            engine = "yt-dlp",
            note = note,
        )
    }

    private fun pickAudio(info: YtInfo): YtFormat? =
        info.formats.filter { it.acodec.isNotEmpty() && it.acodec != "none" && (it.vcodec.isEmpty() || it.vcodec == "none") }
            .maxByOrNull { it.filesize ?: 0 }

    private fun finish(title: String, outcome: DownloadedMedia, engine: String): DownloadReport =
        DownloadReport(
            title = title.ifBlank { outcome.file.nameWithoutExtension },
            description = outcome.file.extension.ifBlank { outcome.kind },
            file = outcome.file,
            bytes = outcome.bytes,
            seconds = outcome.seconds,
            engine = engine,
            note = outcome.note,
        )

    private fun choose(links: List<MediaLink>, quality: String): MediaLink? {
        val q = quality.lowercase(Locale.US)
        if (q == "audio") {
            return links.firstOrNull {
                it.label.equals("audio", ignoreCase = true) ||
                    (it.kind == MediaKind.HLS && it.ext.lowercase(Locale.US) in setOf("m4a", "aac", "mp3"))
            }
        }
        val wanted = when {
            q.contains("1080") -> 1080
            q.contains("720") -> 720
            q.contains("480") -> 480
            q.contains("360") -> 360
            else -> null
        }
        val usable = links.filter { it.kind == MediaKind.FILE || it.kind == MediaKind.HLS }
        if (usable.isEmpty()) return null
        val pool = if (wanted == null) usable else usable.filter { (it.height ?: 0) in 1..wanted }
        val sized = pool.ifEmpty { usable }
        return if (q == "worst") {
            sized.minWithOrNull(compareBy({ it.height ?: Int.MAX_VALUE }, { if (it.kind == MediaKind.FILE) 0 else 1 }))
        } else {
            sized.maxWithOrNull(
                compareBy({ it.height ?: 0 }, { if (it.kind == MediaKind.HLS) 1 else 0 }),
            )
        }
    }

    /** A direct file with its real size when the host answers a HEAD request. */
    private fun fileLink(url: String): MediaLink {
        val ext = VideoSource.extensionOf(url).ifEmpty { "mp4" }
        val head = runCatching {
            val request = okhttp3.Request.Builder().url(url)
                .head()
                .header("User-Agent", VideoDownloader.BROWSER_AGENT)
                .build()
            okhttp3.OkHttpClient.Builder()
                .followRedirects(true)
                .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
                .build()
                .newCall(request)
                .execute()
                .use { response -> response.body?.contentLength()?.takeIf { it > 0 } }
        }.getOrNull()
        return MediaLink(url, MediaKind.FILE, ext = ext, bytes = head)
    }

    private fun readHls(url: String): HlsPlaylist {
        val page = downloader.fetch(url, maxBytes = VideoDownloader.MAX_TEXT)
        val playlist = VideoSource.parseHls(page.text(), page.url)
        if (playlist.drm.isNotBlank()) return playlist
        return playlist
    }

    private fun hlsLinks(url: String, playlist: HlsPlaylist): List<MediaLink> {
        if (!playlist.master) {
            return listOf(MediaLink(url, MediaKind.HLS, label = "HLS", ext = "ts"))
        }
        return playlist.variants.sortedByDescending { it.height ?: 0 }.take(8).map { variant ->
            MediaLink(
                url = variant.uri,
                kind = MediaKind.HLS,
                label = variant.height?.let { "${it}p" } ?: (variant.name.ifEmpty { "audio" }),
                ext = if (variant.audio) "m4a" else "ts",
                height = variant.height,
            )
        }.ifEmpty { listOf(MediaLink(url, MediaKind.HLS, ext = "ts")) }
    }

    private fun enrich(link: MediaLink): MediaLink {
        if (link.kind != MediaKind.HLS || link.height != null) return link
        return runCatching {
            val playlist = readHls(link.url)
            val best = VideoSource.chooseVariant(playlist, "best")
            if (playlist.drm.isNotBlank()) link.copy(label = playlist.drm)
            else link.copy(height = best?.height, ext = if (best?.audio == true) "m4a" else link.ext)
        }.getOrDefault(link)
    }

    private suspend fun ensureEngine(install: Boolean, installPython: suspend () -> Unit): Boolean {
        if (ytDlp.available()) return true
        if (!install) return false
        if (ytDlp.python() == null) installPython()
        if (!ytDlp.available() && ytDlp.python() != null) ytDlp.install()
        return ytDlp.available()
    }

    private fun destination(destDir: File, rawTitle: String, ext: String): File {
        val base = VideoSource.safeFileName(rawTitle)
        val unique = uniqueBase(destDir, base)
        return File(destDir, VideoSource.withExtension(unique, ext))
    }

    private fun uniqueBase(destDir: File, base: String): String {
        var candidate = base
        var index = 2
        // A `.part` is the same download, not a finished copy: the next call continues it.
        val finished = destDir.listFiles()?.filterNot { it.name.endsWith(".part", ignoreCase = true) }.orEmpty()
        while (finished.any {
                it.name.substringBeforeLast('.').equals(candidate, ignoreCase = true) ||
                    it.name.startsWith("$candidate.")
            }
        ) {
            candidate = "$base ($index)"
            index++
            if (index > 99) break
        }
        return candidate
    }

    private fun titleFromUrl(url: String): String {
        val name = runCatching { java.net.URI(url).path.substringAfterLast('/') }.getOrDefault("")
            .substringBeforeLast('.')
        val decoded = runCatching { URLDecoder.decode(name, "UTF-8") }.getOrDefault(name)
        return decoded.replace('-', ' ').replace('_', ' ').trim().ifEmpty { "video" }
    }

    private fun thumbnailFromPage(html: String, baseUrl: String): String {
        if (html.isEmpty()) return ""
        val pattern = Regex(
            """property\s*=\s*["']og:image["'][^>]*content\s*=\s*["']([^"']+)["']""",
            RegexOption.IGNORE_CASE,
        )
        val found = pattern.find(html)?.groupValues?.get(1) ?: return ""
        return runCatching { java.net.URI(baseUrl).resolve(found).toString() }.getOrDefault(found)
    }

    private fun requireHttp(url: String) {
        val parsed = AgentNet.parseUrl(url)
        if (parsed.scheme != "http" && parsed.scheme != "https") throw IOException("Only http and https URLs are allowed.")
    }

    private fun explain(error: Throwable, url: String): String {
        val text = error.message.orEmpty()
        if (text.contains("login", true) || text.contains("sign in", true) ||
            text.contains("private", true) || text.contains("members", true)
        ) {
            return "That video is behind a login. This phone does not sign in for the user."
        }
        if (text.contains("DRM", true) || text.contains("SAMPLE-AES", true)) {
            return "That video is DRM protected. This phone does not bypass DRM."
        }
        return text.ifBlank { "The download engine could not read $url." }
    }

    private class NoMuxedFormat(info: YtInfo) : IOException(
        "Only separate picture and sound tracks are offered; this phone has no ffmpeg to join them. " +
            "Formats: " + info.formats.filter { it.muxed }.take(6).joinToString { "${it.id} ${it.ext} ${it.height ?: '?'}p" },
    )

    companion object {
        const val MIN_FREE = 400L * 1024 * 1024
        private val EXTRACTOR_HOSTS = listOf(
            "youtube.com", "youtu.be", "youtube-nocookie.com", "rutube.ru", "vk.com", "vkvideo.ru",
            "ok.ru", "dailymotion.com", "vimeo.com", "twitch.tv", "tiktok.com", "instagram.com",
            "twitter.com", "x.com", "bilibili.com", "b23.tv", "facebook.com", "soundcloud.com",
            "archive.org", "coub.com", "likee.video", "my.mail.ru",
        )

        fun isExtractorHost(host: String): Boolean = EXTRACTOR_HOSTS.any { host == it || host.endsWith(".$it") }
    }
}
