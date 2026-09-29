package com.hvkeyn.ceditneuro.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/**
 * Public title, description, and captions of one YouTube, RuTube, or Yandex video.
 * The video file itself is never requested.
 */
object VideoBrief {
    private val json = Json { ignoreUnknownKeys = true }
    private val youtubeId = Regex("""^[A-Za-z0-9_-]{11}$""")
    private val rutubeId = Regex("""^[A-Za-z0-9]{16,40}$""")
    private val playlistPattern = Regex("""^(?:PL|UU|OL|LL|FL)[A-Za-z0-9_-]{10,48}$""")
    private val channelPattern = Regex("""^UC[A-Za-z0-9_-]{22}$""")
    private val handlePattern = Regex("""^@[A-Za-z0-9._-]{3,30}$""")

    data class Track(val url: String, val lang: String, val auto: Boolean)

    data class Facts(
        val platform: String,
        val title: String,
        val author: String,
        val description: String,
        val captions: String,
        val captionLang: String,
    )

    fun reject(url: String): String? {
        val parsed = url.trim().toHttpUrlOrNull()
            ?: return "That is not an http(s) video page."
        if (parsed.scheme != "http" && parsed.scheme != "https") return "That is not an http(s) video page."
        if (isStream(parsed.toString())) {
            return "That is a video stream. Read the public page, not the video file."
        }
        val kind = platform(parsed.host)
            ?: return "This reads a public YouTube, RuTube, or Yandex video page."
        if (kind == "youtube" && youtubeId(parsed.toString()) == null) return "No YouTube video id in that URL."
        if (kind == "rutube" && rutubeId(parsed.toString()) == null) return "No RuTube video id in that URL."
        return null
    }

    fun platform(host: String): String? {
        val name = host.lowercase().removePrefix("www.").removePrefix("m.")
        return when {
            name == "youtu.be" || name.endsWith("youtube.com") || name.endsWith("youtube-nocookie.com") -> "youtube"
            name.endsWith("rutube.ru") -> "rutube"
            name.endsWith("dzen.ru") || name.endsWith("yandex.ru") || name.endsWith("yandex.com") || name == "ya.ru" -> "yandex"
            else -> null
        }
    }

    fun youtubeId(url: String): String? {
        val parsed = url.trim().toHttpUrlOrNull() ?: return null
        if (platform(parsed.host) != "youtube") return null
        val query = parsed.queryParameter("v")?.trim().orEmpty()
        if (youtubeId.matches(query)) return query
        val host = parsed.host.lowercase().removePrefix("www.").removePrefix("m.")
        val parts = parsed.encodedPath.split('/').filter { it.isNotEmpty() }
        val raw = if (host == "youtu.be") {
            parts.firstOrNull().orEmpty()
        } else {
            val marker = parts.indexOfFirst { it == "shorts" || it == "embed" || it == "live" || it == "v" }
            if (marker >= 0) parts.getOrNull(marker + 1).orEmpty() else ""
        }
        val id = raw.substringBefore('?').substringBefore('&')
        return id.takeIf { youtubeId.matches(it) }
    }

    fun rutubeId(url: String): String? {
        val parsed = url.trim().toHttpUrlOrNull() ?: return null
        if (platform(parsed.host) != "rutube") return null
        val parts = parsed.encodedPath.split('/').filter { it.isNotEmpty() }
        val marker = parts.indexOfFirst { it == "video" || it == "shorts" || it == "embed" }
        val raw = if (marker >= 0) parts.getOrNull(marker + 1).orEmpty() else parts.lastOrNull().orEmpty()
        return raw.takeIf { rutubeId.matches(it) }
    }

    fun playerJson(html: String): String? {
        val at = html.indexOf("ytInitialPlayerResponse")
        if (at < 0) return null
        val start = html.indexOf('{', at)
        if (start < 0) return null
        var depth = 0
        var inString = false
        var escape = false
        for (index in start until html.length) {
            val char = html[index]
            if (inString) {
                if (escape) escape = false
                else if (char == '\\') escape = true
                else if (char == '"') inString = false
                continue
            }
            when (char) {
                '"' -> inString = true
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return html.substring(start, index + 1)
                }
            }
        }
        return null
    }

    fun youtubePlayer(body: String): Pair<Facts, Track?> {
        val root = json.parseToJsonElement(body).jsonObject
        val details = root["videoDetails"]?.jsonObject
        val tracks = root["captions"]?.jsonObject
            ?.get("playerCaptionsTracklistRenderer")?.jsonObject
            ?.get("captionTracks")?.jsonArray
            .orEmpty()
            .mapNotNull { element ->
                val item = element as? JsonObject ?: return@mapNotNull null
                val url = item.str("baseUrl")
                if (!url.startsWith("https://www.youtube.com/")) return@mapNotNull null
                Track(url, item.str("languageCode"), item.str("kind") == "asr")
            }
        return Facts(
            platform = "youtube",
            title = details?.str("title").orEmpty(),
            author = details?.str("author").orEmpty(),
            description = details?.str("shortDescription").orEmpty().take(1_500),
            captions = "",
            captionLang = "",
        ) to pickTrack(tracks)
    }

    fun rutubeVideo(body: String): Facts {
        val root = json.parseToJsonElement(body).jsonObject
        val author = root["author"]?.jsonObject?.str("name").orEmpty()
        return Facts(
            platform = "rutube",
            title = root.str("title"),
            author = author,
            description = root.str("description").take(1_500),
            captions = "",
            captionLang = "",
        )
    }

    fun rutubeCaptionUrl(body: String): String? {
        val root = runCatching { json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val lists = listOf(root["subtitles"], root["captions"]).filterIsInstance<JsonArray>()
        return lists.asSequence()
            .flatMap { it.asSequence() }
            .mapNotNull { it as? JsonObject }
            .mapNotNull { item -> item.str("file").ifBlank { item.str("url") }.ifBlank { null } }
            .firstOrNull { url ->
                url.startsWith("https://") &&
                    (url.contains("rutube.ru") || url.contains("rtbcdn.ru")) &&
                    !isStream(url)
            }
    }

    fun pageFacts(html: String): Facts {
        val title = meta(html, "og:title").ifBlank { meta(html, "title") }
        val description = meta(html, "og:description").ifBlank { meta(html, "description") }
        return Facts("yandex", unescape(title).take(300), "", unescape(description).take(1_500), "", "")
    }

    fun pageCaptionUrl(html: String): String? =
        Regex("""https://[^"'\\\s>]+\.vtt[^"'\\\s>]*""").findAll(html)
            .map { unescape(it.value) }
            .firstOrNull { url ->
                val host = url.toHttpUrlOrNull()?.host?.lowercase().orEmpty()
                (host.endsWith("yandex.ru") || host.endsWith("yandex.net") || host.endsWith("dzen.ru")) && !isStream(url)
            }

    fun captionsFrom(body: String): String {
        val text = if (body.trimStart().startsWith("{")) jsonCaptions(body) else xmlCaptions(body)
        return text.replace(Regex("""\s+"""), " ").trim().take(12_000)
    }

    fun render(facts: Facts, pageUrl: String): String = buildString {
        append("platform: ").append(facts.platform).append('\n')
        append("title: ").append(facts.title.ifBlank { "(no title)" }).append('\n')
        if (facts.author.isNotBlank()) append("author: ").append(facts.author).append('\n')
        append("url: ").append(pageUrl).append('\n')
        append("captions: ").append(if (facts.captions.isBlank()) "no" else "yes ${facts.captionLang}").append('\n')
        if (facts.description.isNotBlank()) {
            append("\nDescription:\n").append(facts.description.trim()).append('\n')
        }
        if (facts.captions.isNotBlank()) {
            append("\nCaptions:\n").append(facts.captions).append('\n')
        } else {
            append("\nNo public captions. The picture was not seen. Judge only from the description.\n")
        }
        append("Do not invent a scene, a quote, or a number that is not printed above.\n")
    }

    data class Hit(
        val id: String,
        val title: String,
        val author: String,
        val length: String,
        val whenText: String,
    )

    /** A search word, or null when it is empty or is itself a page URL. */
    fun searchQuery(raw: String): String? {
        val text = raw.trim().replace(Regex("""\s+"""), " ").take(120)
        if (text.isBlank()) return null
        if (text.contains("://") || text.startsWith("www.")) return null
        return text
    }

    fun playlistId(raw: String): String? {
        val text = raw.trim()
        val fromQuery = text.toHttpUrlOrNull()?.queryParameter("list")?.trim().orEmpty()
        val candidate = fromQuery.ifBlank { text.substringAfterLast('/').substringBefore('?') }
        return candidate.takeIf { playlistPattern.matches(it) }
    }

    /** A UC id, or an @handle. A watch page is neither. */
    fun channelRef(raw: String): String? {
        val text = raw.trim()
        if (channelPattern.matches(text) || handlePattern.matches(text)) return text
        val url = text.toHttpUrlOrNull() ?: return null
        if (platform(url.host) != "youtube") return null
        val parts = url.encodedPath.split('/').filter { it.isNotEmpty() }
        val at = parts.indexOf("channel")
        if (at >= 0) {
            val id = parts.getOrNull(at + 1).orEmpty()
            if (channelPattern.matches(id)) return id
        }
        return parts.firstOrNull { handlePattern.matches(it) }
    }

    fun channelIdFrom(body: String): String? {
        var found: String? = null
        fun walk(element: JsonElement) {
            if (found != null) return
            when (element) {
                is JsonObject -> {
                    val id = element.str("browseId")
                    if (channelPattern.matches(id)) found = id
                    else element.values.forEach { walk(it) }
                }
                is JsonArray -> element.forEach { walk(it) }
                else -> Unit
            }
        }
        runCatching { walk(json.parseToJsonElement(body)) }
        return found
    }

    fun hitsFrom(body: String, limit: Int): List<Hit> {
        val hits = ArrayList<Hit>()
        val seen = HashSet<String>()
        fun walk(element: JsonElement) {
            if (hits.size >= limit) return
            when (element) {
                is JsonObject -> {
                    val renderer = element["compactVideoRenderer"]?.jsonObject
                        ?: element["videoRenderer"]?.jsonObject
                        ?: element["playlistVideoRenderer"]?.jsonObject
                    if (renderer != null) {
                        val id = renderer.str("videoId")
                        if (youtubeId.matches(id) && seen.add(id)) {
                            hits += Hit(
                                id = id,
                                title = textOf(renderer["title"]).take(180),
                                author = textOf(renderer["longBylineText"]).ifBlank { textOf(renderer["shortBylineText"]) }.take(120),
                                length = textOf(renderer["lengthText"]).take(20),
                                whenText = textOf(renderer["publishedTimeText"]).take(40),
                            )
                        }
                    }
                    if (hits.size < limit) element.values.forEach { walk(it) }
                }
                is JsonArray -> element.forEach { if (hits.size < limit) walk(it) }
                else -> Unit
            }
        }
        runCatching { walk(json.parseToJsonElement(body)) }
        return hits
    }

    fun renderHits(kind: String, label: String, hits: List<Hit>): String = buildString {
        append("kind: ").append(kind).append('\n')
        append("label: ").append(label).append('\n')
        append("count: ").append(hits.size).append('\n')
        if (hits.isEmpty()) {
            append("No public videos matched. Do not invent a title.\n")
            return@buildString
        }
        hits.forEachIndexed { index, hit ->
            append(index + 1).append(". ").append(hit.title.ifBlank { "(no title)" }).append('\n')
            if (hit.author.isNotBlank()) append("   author: ").append(hit.author).append('\n')
            if (hit.length.isNotBlank()) append("   length: ").append(hit.length).append('\n')
            if (hit.whenText.isNotBlank()) append("   when: ").append(hit.whenText).append('\n')
            append("   url: https://www.youtube.com/watch?v=").append(hit.id).append('\n')
        }
        append("Titles only. The picture was not seen. Do not invent a title that is not printed above.\n")
    }

    fun jsonString(value: String): String = buildString {
        append('"')
        value.forEach { char ->
            when (char) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n', '\r' -> append(' ')
                else -> if (char.code < 32) append(' ') else append(char)
            }
        }
        append('"')
    }

    private fun textOf(element: JsonElement?): String {
        val obj = element as? JsonObject ?: return ""
        val simple = obj["simpleText"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        if (simple.isNotBlank()) return simple
        return obj["runs"]?.jsonArray.orEmpty().joinToString("") { run ->
            (run as? JsonObject)?.str("text").orEmpty()
        }.trim()
    }

    private fun pickTrack(tracks: List<Track>): Track? {
        if (tracks.isEmpty()) return null
        val manual = tracks.filter { !it.auto }
        val pool = manual.ifEmpty { tracks }
        return listOf("ru", "en").firstNotNullOfOrNull { lang ->
            pool.firstOrNull { it.lang.startsWith(lang) }
        } ?: pool.first()
    }

    private fun jsonCaptions(body: String): String {
        val events = runCatching { json.parseToJsonElement(body).jsonObject["events"]?.jsonArray }.getOrNull()
            ?: return ""
        return events.joinToString(" ") { event ->
            (event as? JsonObject)?.get("segs")?.jsonArray.orEmpty().joinToString("") { seg ->
                (seg as? JsonObject)?.str("utf8").orEmpty()
            }
        }
    }

    private fun xmlCaptions(body: String): String =
        Regex("""<(?:text|p)(?:\s[^>]*)?>(.*?)</(?:text|p)>""", RegexOption.DOT_MATCHES_ALL)
            .findAll(body)
            .joinToString(" ") { unescape(it.groupValues[1].replace(Regex("<[^>]+>"), " ")) }

    private fun meta(html: String, name: String): String {
        val property = Regex(
            """<meta[^>]+(?:property|name|itemprop)=["']${Regex.escape(name)}["'][^>]*content=["'](.*?)["']""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.get(1)
        if (!property.isNullOrBlank()) return property
        return Regex(
            """<meta[^>]+content=["'](.*?)["'][^>]*(?:property|name|itemprop)=["']${Regex.escape(name)}["']""",
            RegexOption.IGNORE_CASE,
        ).find(html)?.groupValues?.get(1).orEmpty()
    }

    private fun unescape(text: String): String =
        text.replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("\\n", " ")
            .replace(Regex("""\s+"""), " ")
            .trim()

    private fun isStream(url: String): Boolean {
        val lower = url.lowercase()
        return "googlevideo.com" in lower ||
            ".m3u8" in lower ||
            "mime=video" in lower ||
            lower.contains(".mp4") ||
            "videoplayback" in lower
    }

    private fun JsonObject.str(name: String): String =
        this[name]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
}
