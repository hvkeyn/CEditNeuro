package com.hvkeyn.ceditneuro.video

import java.net.URI
import java.util.Locale

/**
 * A media address found on a page: a direct file, an HLS playlist, or a DASH manifest.
 * [headers] are the headers that page or the extractor said this address needs.
 */
data class MediaLink(
    val url: String,
    val kind: MediaKind,
    val label: String = "",
    val ext: String = "",
    val height: Int? = null,
    val bytes: Long? = null,
    val headers: Map<String, String> = emptyMap(),
)

enum class MediaKind { FILE, HLS, DASH }

/** What a page or an extractor says about one video, before any bytes are fetched. */
data class MediaInfo(
    val pageUrl: String,
    val title: String = "",
    val durationSeconds: Int? = null,
    val thumbnail: String = "",
    val links: List<MediaLink> = emptyList(),
    val engine: String = "",
    val note: String = "",
)

/** One entry of an HLS master playlist. */
data class HlsVariant(
    val uri: String,
    val bandwidth: Long? = null,
    val width: Int? = null,
    val height: Int? = null,
    val codecs: String = "",
    val audio: Boolean = false,
    val name: String = "",
)

data class HlsKey(
    val method: String,
    val uri: String = "",
    val iv: ByteArray? = null,
)

data class HlsSegment(
    val uri: String,
    val sequence: Long,
    val key: HlsKey? = null,
    val range: String? = null,
)

data class HlsPlaylist(
    val master: Boolean,
    val variants: List<HlsVariant> = emptyList(),
    val segments: List<HlsSegment> = emptyList(),
    val initUri: String? = null,
    val ended: Boolean = false,
    /** `#EXT-X-PLAYLIST-TYPE:VOD` is a finished recording even when ENDLIST is absent. */
    val vod: Boolean = false,
    val targetDuration: Int = 0,
    /** A human reason when the playlist is protected by DRM, or blank. */
    val drm: String = "",
)

/**
 * Reads media addresses out of a page and HLS playlists. It does not fetch anything.
 * A scrambled or DRM-protected manifest is named, never silently treated as a download.
 */
object VideoSource {

    private val MEDIA_EXTENSIONS = listOf(
        "mp4", "webm", "mov", "m4v", "mkv", "ogv", "avi", "flv", "ts", "m3u8", "mpd",
    )

    private val URL_PATTERN = Regex(
        """https?://[^\s"'<>\\]+?\.(?:mp4|webm|mov|m4v|mkv|ogv|avi|flv|ts|m3u8|mpd)(?:\?[^\s"'<>\\]*)?""",
        RegexOption.IGNORE_CASE,
    )

    private val OG_VIDEO = Regex(
        """property\s*=\s*["']og:video(?::(?:url|secure_url))?["'][^>]*content\s*=\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )

    private val OG_VIDEO_REVERSED = Regex(
        """content\s*=\s*["']([^"']+)["'][^>]*property\s*=\s*["']og:video(?::(?:url|secure_url))?["']""",
        RegexOption.IGNORE_CASE,
    )

    private val VIDEO_TAG = Regex(
        """<(?:video|source)\b[^>]*\bsrc\s*=\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )

    private val SOURCE_TAG = Regex(
        """<(?:video|source)\b[^>]*>""",
        RegexOption.IGNORE_CASE,
    )

    private val CONTENT_URL = Regex(
        """"(?:contentUrl|content_url|videoUrl|video_url|hlsUrl|hls_url|playlistUrl|playlist_url)"\s*:\s*"([^"]+)"""",
        RegexOption.IGNORE_CASE,
    )

    private val TITLE = Regex(
        """property\s*=\s*["']og:title["'][^>]*content\s*=\s*["']([^"']+)["']""",
        RegexOption.IGNORE_CASE,
    )

    private val TITLE_REVERSED = Regex(
        """content\s*=\s*["']([^"']+)["'][^>]*property\s*=\s*["']og:title["']""",
        RegexOption.IGNORE_CASE,
    )

    private val HTML_TITLE = Regex("""<title[^>]*>(.*?)</title>""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))

    /**
     * A page address whose last path part already is a media file or a manifest.
     * Such a link is fetched, not parsed as HTML.
     */
    fun isDirectMedia(url: String): Boolean {
        val path = runCatching { URI(url.trim()).path.orEmpty() }.getOrDefault("")
        val ext = path.substringAfterLast('.', "").lowercase(Locale.US)
        return ext in MEDIA_EXTENSIONS
    }

    fun kindOf(url: String): MediaKind? {
        val ext = extensionOf(url)
        return when (ext) {
            "m3u8" -> MediaKind.HLS
            "mpd" -> MediaKind.DASH
            in MEDIA_EXTENSIONS -> MediaKind.FILE
            else -> null
        }
    }

    fun extensionOf(url: String): String {
        val path = runCatching { URI(url.trim()).path.orEmpty() }.getOrDefault("")
        return path.substringAfterLast('.', "").lowercase(Locale.US)
    }

    /** JSON pages write `\/` and `\u0026`; both have to become the real characters. */
    fun normalizeHtml(html: String): String {
        if (!html.contains('\\')) return html
        return html
            .replace("\\/", "/")
            .replace("\\u0026", "&")
            .replace("\\u003d", "=")
            .replace("\\u003f", "?")
            .replace("\\u0025", "%")
            .replace("\\u002F", "/")
            .replace("&amp;", "&")
    }

    fun titleFromHtml(html: String): String {
        val normalized = normalizeHtml(html)
        val raw = TITLE.find(normalized)?.groupValues?.get(1)
            ?: TITLE_REVERSED.find(normalized)?.groupValues?.get(1)
            ?: HTML_TITLE.find(normalized)?.groupValues?.get(1)
            ?: return ""
        return decodeEntities(raw).trim().take(300)
    }

    /** Every candidate the page prints, with a direct file before an adaptive manifest. */
    fun pageLinks(html: String, pageUrl: String): List<MediaLink> {
        val text = normalizeHtml(html)
        val found = LinkedHashMap<String, MediaLink>()

        fun add(raw: String?, label: String) {
            val value = raw?.trim()?.trim('"', '\'', ' ') ?: return
            if (value.isEmpty() || value.startsWith("data:") || value.startsWith("blob:")) return
            val absolute = resolve(pageUrl, value) ?: return
            val kind = kindOf(absolute)
            if (kind == null || kind == MediaKind.DASH) return
            if (VideoSource.extensionOf(absolute) == "ts") return
            val key = absolute.substringBefore('#')
            if (key in found) return
            found[key] = MediaLink(
                url = key,
                kind = kind,
                label = label,
                ext = extensionOf(key).ifEmpty { if (kind == MediaKind.HLS) "ts" else "mp4" },
                height = heightFromLabel(label),
            )
        }

        OG_VIDEO.findAll(text).forEach { add(it.groupValues[1], "og:video") }
        OG_VIDEO_REVERSED.findAll(text).forEach { add(it.groupValues[1], "og:video") }
        VIDEO_TAG.findAll(text).forEach { add(it.groupValues[1], "video") }
        CONTENT_URL.findAll(text).forEach { add(it.groupValues[1], it.groupValues[1].substringAfterLast('/')) }
        URL_PATTERN.findAll(text).forEach { match ->
            val value = match.value.trimEnd('.', ',', ';', ')', ']', '}')
            add(value, value.substringAfterLast('/').substringBefore('?'))
        }

        // A media file first, then HLS: a small trailer mp4 is worse than a 1080p master.
        return found.values.sortedWith(
            compareBy<MediaLink> { if (it.kind == MediaKind.FILE) 0 else 1 }
                .thenByDescending { it.height ?: 0 },
        )
    }

    /** Absolute http(s) media addresses that appear in a `HEAD`/`GET` body. */
    fun mediaUrls(text: String, baseUrl: String): List<String> =
        URL_PATTERN.findAll(normalizeHtml(text)).mapNotNull { match ->
            resolve(baseUrl, match.value.trimEnd('.', ',', ';', ')', ']', '}'))?.takeIf { kindOf(it) != null }
        }.distinct().take(40).toList()

    fun parseHls(text: String, baseUrl: String): HlsPlaylist {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        val master = lines.any { it.startsWith("#EXT-X-STREAM-INF") }
        val drm = lines.firstNotNullOfOrNull { drmReason(it) }.orEmpty()

        if (master) {
            val variants = mutableListOf<HlsVariant>()
            var pending: Map<String, String>? = null
            for (line in lines) {
                if (line.startsWith("#EXT-X-STREAM-INF")) {
                    pending = attributes(line)
                    continue
                }
                if (line.startsWith("#")) continue
                val attrs = pending ?: continue
                pending = null
                val uri = resolve(baseUrl, line) ?: continue
                val resolution = attrs["RESOLUTION"].orEmpty()
                variants += HlsVariant(
                    uri = uri,
                    bandwidth = attrs["BANDWIDTH"]?.toLongOrNull(),
                    width = resolution.substringBefore('x').toIntOrNull(),
                    height = resolution.substringAfter('x', "").toIntOrNull(),
                    codecs = attrs["CODECS"].orEmpty(),
                )
            }
            lines.filter { it.startsWith("#EXT-X-MEDIA") }.forEach { line ->
                val attrs = attributes(line)
                if (!attrs["TYPE"].equals("AUDIO", ignoreCase = true)) return@forEach
                val uri = attrs["URI"] ?: return@forEach
                val absolute = resolve(baseUrl, uri) ?: return@forEach
                variants += HlsVariant(
                    uri = absolute,
                    audio = true,
                    name = attrs["NAME"].orEmpty().ifEmpty { attrs["GROUP-ID"].orEmpty() },
                )
            }
            return HlsPlaylist(master = true, variants = variants, drm = drm)
        }

        val segments = mutableListOf<HlsSegment>()
        var key: HlsKey? = null
        var map: String? = null
        var sequence = 0L
        var byterange: String? = null
        var ended = false
        var vod = false
        var target = 0

        for (line in lines) {
            when {
                line.startsWith("#EXT-X-TARGETDURATION") ->
                    target = line.substringAfter(':').trim().toIntOrNull() ?: target
                line.startsWith("#EXT-X-PLAYLIST-TYPE") ->
                    vod = line.substringAfter(':').trim().equals("VOD", ignoreCase = true)
                line.startsWith("#EXT-X-MEDIA-SEQUENCE") ->
                    sequence = line.substringAfter(':').trim().toLongOrNull() ?: sequence
                line.startsWith("#EXT-X-ENDLIST") -> ended = true
                line.startsWith("#EXT-X-KEY") -> {
                    val attrs = attributes(line)
                    val method = attrs["METHOD"].orEmpty().uppercase(Locale.US)
                    key = when {
                        method.isEmpty() || method == "NONE" -> null
                        else -> HlsKey(
                            method = method,
                            uri = attrs["URI"]?.let { resolve(baseUrl, it) }.orEmpty(),
                            iv = attrs["IV"]?.let { parseIv(it) },
                        )
                    }
                }
                line.startsWith("#EXT-X-MAP") -> {
                    val attrs = attributes(line)
                    map = attrs["URI"]?.let { resolve(baseUrl, it) }
                }
                line.startsWith("#EXT-X-BYTERANGE") -> {
                    val raw = line.substringAfter(':').trim()
                    if (raw.contains('@')) byterange = raw
                }
                line.startsWith("#") -> Unit
                else -> {
                    val uri = resolve(baseUrl, line) ?: continue
                    segments += HlsSegment(uri = uri, sequence = sequence, key = key, range = byterange)
                    sequence++
                    byterange = null
                }
            }
        }
        return HlsPlaylist(
            master = false,
            segments = segments,
            initUri = map,
            ended = ended,
            vod = vod,
            targetDuration = target,
            drm = drm,
        )
    }

    /** The variant a quality name asks for. `best` keeps the tallest, `worst` the smallest. */
    fun chooseVariant(playlist: HlsPlaylist, quality: String): HlsVariant? {
        val q = quality.lowercase(Locale.US)
        val video = playlist.variants.filter { !it.audio }
        if (q == "audio") {
            return playlist.variants.firstOrNull { it.audio }
        }
        val wanted = when {
            q.contains("1080") -> 1080
            q.contains("720") -> 720
            q.contains("480") -> 480
            q.contains("360") -> 360
            else -> null
        }
        val pool = if (wanted == null) video else video.filter { (it.height ?: 0) in 1..wanted }
        val usable = pool.ifEmpty { video }
        return when (q) {
            "worst" -> usable.minWithOrNull(compareBy({ it.height ?: 0 }, { it.bandwidth ?: 0 }))
            else -> usable.maxWithOrNull(compareBy({ it.height ?: 0 }, { it.bandwidth ?: 0 }))
        }
    }

    /** `KEY=VALUE,KEY="quoted, value"` → map. Quotes are removed. */
    fun attributes(line: String): Map<String, String> {
        val body = line.substringAfter(':', "").ifEmpty { return emptyMap() }
        val result = linkedMapOf<String, String>()
        val current = StringBuilder()
        var quoted = false
        val parts = mutableListOf<String>()
        for (char in body) {
            when {
                char == '"' -> {
                    quoted = !quoted
                    current.append(char)
                }
                char == ',' && !quoted -> {
                    parts += current.toString()
                    current.setLength(0)
                }
                else -> current.append(char)
            }
        }
        if (current.isNotEmpty()) parts += current.toString()
        parts.forEach { part ->
            val name = part.substringBefore('=', "").trim()
            if (name.isEmpty()) return@forEach
            result[name.uppercase(Locale.US)] = part.substringAfter('=', "").trim().trim('"')
        }
        return result
    }

    /** A file name the phone and the project can both hold. */
    fun safeFileName(raw: String, fallback: String = "video"): String {
        var name = raw.trim()
            .replace(Regex("""[\\/:*?"<>|\u0000-\u001F]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .trim('.', ' ')
        if (name.length > 110) name = name.take(110).trim().trim('.', ' ')
        if (name.isEmpty()) name = fallback
        val reserved = setOf("con", "prn", "aux", "nul", "com1", "com2", "lpt1")
        if (name.substringBefore('.').lowercase(Locale.US) in reserved) name = "video-$name"
        return name
    }

    fun withExtension(name: String, ext: String): String {
        val clean = ext.lowercase(Locale.US).trim().trimStart('.')
        val current = extensionOf(name)
        if (clean.isEmpty() || current == clean) return name
        return name.substringBeforeLast('.', name) + "." + clean
    }

    fun formatBytes(bytes: Long): String {
        if (bytes <= 0) return "unknown"
        val units = listOf("B", "KB", "MB", "GB", "TB")
        var size = bytes.toDouble()
        var unit = 0
        while (size >= 1024 && unit < units.lastIndex) {
            size /= 1024
            unit++
        }
        return if (unit == 0) "${bytes} ${units[unit]}" else String.format(Locale.US, "%.1f %s", size, units[unit])
    }

    fun formatDuration(seconds: Int?): String {
        if (seconds == null || seconds <= 0) return "unknown"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s) else String.format(Locale.US, "%d:%02d", m, s)
    }

    private fun drmReason(line: String): String? {
        val upper = line.uppercase(Locale.US)
        if (!upper.startsWith("#EXT-X-KEY") && !upper.startsWith("#EXT-X-SESSION-KEY")) return null
        if (upper.contains("METHOD=NONE")) return null
        return when {
            upper.contains("METHOD=SAMPLE-AES") -> "SAMPLE-AES encrypted stream"
            upper.contains("WIDEVINE") || upper.contains("PLAYREADY") ||
                upper.contains("URN:UUID") || upper.contains("KEYFORMAT=\"COM.APPLE.STREAMINGKEYDELIVERY\"") ->
                "DRM-protected stream"
            else -> null
        }
    }

    private fun parseIv(raw: String): ByteArray? {
        val clean = raw.trim().removePrefix("0x").removePrefix("0X")
        if (clean.length != 32 || clean.any { it.digitToIntOrNull(16) == null }) return null
        return ByteArray(16) { index ->
            clean.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
    }

    private fun resolve(base: String, value: String): String? {
        val trimmed = value.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        if (trimmed.startsWith("//")) return "https:" + trimmed
        if (trimmed.startsWith("data:") || trimmed.startsWith("blob:") || trimmed.startsWith("javascript:")) return null
        return runCatching { URI(base).resolve(trimmed).toString() }.getOrNull()
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
    }

    private fun heightFromLabel(label: String): Int? =
        Regex("""(\d{3,4})p""").find(label)?.groupValues?.get(1)?.toIntOrNull()

    private fun decodeEntities(raw: String): String = raw
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&amp;", "&")
}
