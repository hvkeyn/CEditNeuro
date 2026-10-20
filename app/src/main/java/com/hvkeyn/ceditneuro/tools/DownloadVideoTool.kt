package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.video.MediaProgress
import com.hvkeyn.ceditneuro.video.VideoEngine
import com.hvkeyn.ceditneuro.video.VideoSource
import com.hvkeyn.ceditneuro.workspace.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.util.Locale

/**
 * Saves a movie or a video from a page into the project. A direct file and an HLS stream
 * are read by the phone itself; a page an extractor knows goes through yt-dlp, which is
 * installed once under this app's Python. A DRM or login wall is refused, not bypassed.
 */
class DownloadVideoTool(
    private val workspace: Workspace,
    private val engine: VideoEngine,
    private val networkAllowed: () -> Boolean,
    private val installPython: suspend () -> Unit,
    private val onProgress: (MediaProgress) -> Unit = {},
    private val onSaved: (String) -> Unit = {},
) : Tool {

    override val name = "download_video"
    override val description =
        "Download a movie or a video into the project. url is a video page or a direct " +
            ".mp4, .webm, or .m3u8 address. quality is best (default), 1080, 720, 480, 360, " +
            "worst, or audio. folder is relative to the project and defaults to downloads. " +
            "name overrides the file name. max_mb caps the size; default 4096. " +
            "A direct file and an HLS stream are saved by the phone. A page an extractor knows " +
            "uses yt-dlp, which may install Python and yt-dlp once on this phone. " +
            "A DRM, paid, or login-only video is refused; do not look for a workaround. " +
            "The result prints the saved path, the byte size, and the format."

    override val parameters = objectSchema(
        properties = mapOf(
            "url" to stringProp("A video page or a direct media address."),
            "quality" to stringProp("best, 1080, 720, 480, 360, worst, or audio. Defaults to best."),
            "folder" to stringProp("Project-relative folder. Defaults to downloads."),
            "name" to stringProp("Optional file name without a path."),
            "max_mb" to intProp("Largest file to accept, in MB. Defaults to 4096."),
        ),
        required = listOf("url"),
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        if (!networkAllowed()) return@withContext ToolResult.error("Agent network is off in Settings.")
        val url = args.stringArg("url")?.trim().orEmpty()
        if (url.isEmpty()) return@withContext ToolResult.error("Missing 'url'.")
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            return@withContext ToolResult.error("Only an http or https address is downloaded.")
        }
        val quality = args.stringArg("quality")?.trim()?.lowercase(Locale.US)?.ifBlank { "best" } ?: "best"
        val folder = args.stringArg("folder")?.trim()?.trim('/')?.ifBlank { "downloads" } ?: "downloads"
        val name = args.stringArg("name")?.trim()?.ifBlank { null }
        val maxMb = (args.intArg("max_mb") ?: 4096).coerceIn(1, 16_384)
        val destDir = runCatching { workspace.resolve(folder) }
            .getOrElse { return@withContext ToolResult.error(it.message ?: "That folder is outside the project.") }
        if (destDir.exists() && !destDir.isDirectory) {
            return@withContext ToolResult.error("$folder is a file, not a folder.")
        }
        destDir.mkdirs()

        return@withContext runCatching {
            val report = engine.download(
                url = url,
                quality = quality,
                maxBytes = maxMb.toLong() * 1024L * 1024L,
                destDir = destDir,
                name = name,
                allowYtDlpInstall = true,
                installPython = installPython,
                onProgress = onProgress,
            )
            val relative = runCatching { report.file.relativeTo(workspace.root).invariantSeparatorsPath }
                .getOrDefault(report.file.name)
            onSaved(relative)
            ToolResult.ok(buildString {
                append("Downloaded ").append(report.title.ifBlank { report.file.nameWithoutExtension }).append('\n')
                append("file: ").append(relative).append('\n')
                append("size: ").append(VideoSource.formatBytes(report.bytes)).append('\n')
                append("format: ").append(report.description).append('\n')
                append("engine: ").append(report.engine).append('\n')
                append("time: ").append(report.seconds).append(" s\n")
                if (report.note.isNotBlank()) append("note: ").append(report.note).append('\n')
                val extension = report.file.extension.lowercase(Locale.US)
                if (extension in VIDEO_EXTENSIONS) {
                    append("A video tab opens the file. The player shows it and the reel editor can cut it.")
                } else {
                    append("Saved. This container may need another player.")
                }
            })
        }.getOrElse { error ->
            val detail = error.message?.lineSequence()?.firstOrNull { it.isNotBlank() }?.take(600)
                ?: "The download failed."
            ToolResult.error(
                "Download failed: $detail\n" +
                    "Do not call the same URL and quality again in this run. If it says the page needs a login or " +
                    "DRM, stop and tell the user. If it says Python is not installed, call install_runtime " +
                    "name=python, then tell the user to send the link again.",
            )
        }
    }

    private companion object {
        val VIDEO_EXTENSIONS = setOf("mp4", "webm", "mkv", "m4v", "3gp", "ts")
    }
}
