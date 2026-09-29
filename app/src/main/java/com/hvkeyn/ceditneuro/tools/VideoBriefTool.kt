package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.agent.VideoBrief
import com.hvkeyn.ceditneuro.net.AgentNet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/** Reads public titles, descriptions, and captions. It does not fetch the video file. */
class VideoBriefTool(
    private val net: AgentNet,
    private val networkAllowed: () -> Boolean,
) : Tool {
    override val name = "video_brief"
    override val description =
        "Read public YouTube, RuTube, or Yandex video pages. " +
            "action=brief reads one page URL: title, author, description, and captions. " +
            "action=search finds up to 8 YouTube titles for a query. " +
            "action=channel lists up to 8 latest uploads from an @handle, a channel URL, or a UC id. " +
            "action=playlist lists up to 12 titles from a playlist URL or id. " +
            "action=batch reads up to 3 page URLs separated by |. " +
            "Search, channel, and playlist lines are titles only. " +
            "Does not download the video. A stream URL is refused. " +
            "If captions is no, the picture was not seen."
    override val parameters = objectSchema(
        properties = mapOf(
            "action" to stringProp("brief, search, channel, playlist, or batch. Defaults to brief when url is set."),
            "url" to stringProp("A video page, a channel, or a playlist."),
            "query" to stringProp("Search words for action=search. Not a URL."),
            "urls" to stringProp("Up to 3 video page URLs separated by |, for action=batch."),
        ),
        required = emptyList(),
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        if (!networkAllowed()) return@withContext ToolResult.error("Agent network is off in Settings.")
        val action = args.stringArg("action")?.trim().orEmpty().ifBlank {
            when {
                !args.stringArg("query").isNullOrBlank() -> "search"
                !args.stringArg("urls").isNullOrBlank() -> "batch"
                else -> "brief"
            }
        }
        runCatching {
            when (action) {
                "brief" -> brief(args.stringArg("url").orEmpty())
                "search" -> search(args.stringArg("query").orEmpty())
                "channel" -> channel(args.stringArg("url").orEmpty())
                "playlist" -> playlist(args.stringArg("url").orEmpty())
                "batch" -> batch(args.stringArg("urls").orEmpty())
                else -> throw java.io.IOException("action is brief, search, channel, playlist, or batch.")
            }
        }.fold(
            onSuccess = { ToolResult.ok(it) },
            onFailure = { ToolResult.error("${it.message ?: "The video page failed."} Do not guess its contents.") },
        )
    }

    private fun brief(url: String): String {
        val page = url.trim()
        VideoBrief.reject(page)?.let { throw java.io.IOException(it) }
        return load(page)
    }

    private fun search(raw: String): String {
        val query = VideoBrief.searchQuery(raw) ?: throw java.io.IOException("Search needs words, not a page URL.")
        val body = post("https://www.youtube.com/youtubei/v1/search", client("\"query\":${VideoBrief.jsonString(query)}"))
        return VideoBrief.renderHits("search", query, VideoBrief.hitsFrom(body, 8))
    }

    private fun channel(raw: String): String {
        val ref = VideoBrief.channelRef(raw) ?: throw java.io.IOException("Pass an @handle, a channel URL, or a UC id.")
        val id = if (ref.startsWith("UC")) {
            ref
        } else {
            val resolved = post(
                "https://www.youtube.com/youtubei/v1/navigation/resolve_url",
                client("\"url\":${VideoBrief.jsonString("https://www.youtube.com/$ref")}"),
            )
            VideoBrief.channelIdFrom(resolved) ?: throw java.io.IOException("That channel was not found. Do not invent its videos.")
        }
        val uploads = "VL" + "UU" + id.drop(2)
        val body = post("https://www.youtube.com/youtubei/v1/browse", client("\"browseId\":${VideoBrief.jsonString(uploads)}"))
        return VideoBrief.renderHits("channel", ref, VideoBrief.hitsFrom(body, 8))
    }

    private fun playlist(raw: String): String {
        val id = VideoBrief.playlistId(raw) ?: throw java.io.IOException("Pass a playlist URL or a PL id.")
        val body = post("https://www.youtube.com/youtubei/v1/browse", client("\"browseId\":${VideoBrief.jsonString("VL$id")}"))
        return VideoBrief.renderHits("playlist", id, VideoBrief.hitsFrom(body, 12))
    }

    private fun batch(raw: String): String {
        val pages = raw.split('|').map { it.trim() }.filter { it.isNotEmpty() }
        if (pages.isEmpty()) throw java.io.IOException("Batch needs page URLs separated by |.")
        if (pages.size > 3) throw java.io.IOException("Batch is 3 videos. A search or a playlist lists titles first.")
        return buildString {
            append("batch: ").append(pages.size).append('\n')
            pages.forEachIndexed { index, page ->
                append("\n# ").append(index + 1).append('\n')
                val one = runCatching { brief(page) }.getOrElse { "${it.message} Do not guess its contents." }
                val clipped = if (one.length > 4_500) one.take(4_500) + "\n[captions cut]\n" else one
                append(clipped)
                if (!clipped.endsWith('\n')) append('\n')
            }
        }
    }

    private fun client(fields: String): String =
        """{"context":{"client":{"clientName":"ANDROID","clientVersion":"20.10.38","androidSdkVersion":30,"hl":"ru","gl":"RU"}},$fields}"""

    private fun load(url: String): String {
        val host = AgentNet.parseUrl(url).host
        val facts = when (VideoBrief.platform(host)) {
            "youtube" -> youtube(url)
            "rutube" -> rutube(url)
            else -> yandex(url)
        }
        return VideoBrief.render(facts, url)
    }

    private fun youtube(url: String): VideoBrief.Facts {
        val id = VideoBrief.youtubeId(url) ?: throw java.io.IOException("No YouTube video id in that URL.")
        val player = runCatching {
            post(
                "https://www.youtube.com/youtubei/v1/player",
                """{"context":{"client":{"clientName":"ANDROID","clientVersion":"20.10.38","androidSdkVersion":30,"hl":"ru","gl":"RU"}},"videoId":"$id"}""",
            )
        }.getOrNull()
        var parsed = player?.let { runCatching { VideoBrief.youtubePlayer(it) }.getOrNull() }
        if (parsed?.first?.title.isNullOrBlank()) {
            val html = runCatching { get("https://www.youtube.com/watch?v=$id&hl=ru") }.getOrNull()
            val fromPage = html?.let { VideoBrief.playerJson(it) }
                ?.let { runCatching { VideoBrief.youtubePlayer(it) }.getOrNull() }
            if (fromPage != null) parsed = fromPage
        }
        val base = parsed?.first?.takeIf { it.title.isNotBlank() } ?: oembed(id)
        val track = parsed?.second
        val captions = track?.let { runCatching { get(it.url) }.getOrNull() }.orEmpty()
        val text = if (captions.isBlank()) "" else VideoBrief.captionsFrom(captions)
        return base.copy(captions = text, captionLang = if (text.isBlank()) "" else track?.lang.orEmpty())
    }

    private fun oembed(id: String): VideoBrief.Facts {
        val watch = "https://www.youtube.com/watch?v=$id"
        val body = get("https://www.youtube.com/oembed?format=json&url=${URLEncoder.encode(watch, "UTF-8")}")
        val root = JSON.parseToJsonElement(body).jsonObject
        fun field(name: String) = root[name]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
        return VideoBrief.Facts("youtube", field("title"), field("author_name"), "", "", "")
    }

    private fun rutube(url: String): VideoBrief.Facts {
        val id = VideoBrief.rutubeId(url) ?: throw java.io.IOException("No RuTube video id in that URL.")
        val facts = VideoBrief.rutubeVideo(get("https://rutube.ru/api/video/$id/"))
        val captionUrl = runCatching { VideoBrief.rutubeCaptionUrl(get("https://rutube.ru/api/play/options/$id/")) }.getOrNull()
        val text = captionUrl?.let { runCatching { VideoBrief.captionsFrom(get(it)) }.getOrNull() }.orEmpty()
        return facts.copy(captions = text, captionLang = if (text.isBlank()) "" else "track")
    }

    private fun yandex(url: String): VideoBrief.Facts {
        val html = get(url)
        val facts = VideoBrief.pageFacts(html)
        val captionUrl = VideoBrief.pageCaptionUrl(html)
        val text = captionUrl?.let { runCatching { VideoBrief.captionsFrom(get(it)) }.getOrNull() }.orEmpty()
        return facts.copy(captions = text, captionLang = if (text.isBlank()) "" else "vtt")
    }

    private fun get(url: String): String {
        val response = net.exchange(url, "GET", mapOf("Accept" to "text/html,application/json"), null, MAX_BYTES)
        if (response.code !in 200..299) throw java.io.IOException("HTTP ${response.code}.")
        return response.body.toString(Charsets.UTF_8)
    }

    private fun post(url: String, body: String): String {
        val response = net.exchange(
            url,
            "POST",
            mapOf("Content-Type" to "application/json", "Accept" to "application/json"),
            body,
            MAX_BYTES,
        )
        if (response.code !in 200..299) throw java.io.IOException("HTTP ${response.code}.")
        return response.body.toString(Charsets.UTF_8)
    }

    private companion object {
        const val MAX_BYTES = 6L * 1024 * 1024
        val JSON = Json { ignoreUnknownKeys = true }
    }
}
