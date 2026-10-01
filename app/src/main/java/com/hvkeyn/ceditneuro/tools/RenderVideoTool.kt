package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.video.VideoRenderHub
import com.hvkeyn.ceditneuro.workspace.Workspace
import kotlinx.serialization.json.JsonObject

/** Draws a HyperFrames composition frame by frame and encodes it to MP4 with the phone's encoder. */
class RenderVideoTool(
    private val workspace: Workspace,
    private val onRendered: (String) -> Unit = {},
) : Tool {
    override val name = "render_video"
    override val description =
        "Render an HTML composition (a root with data-composition-id and a paused timeline on window.__timelines) to an MP4. " +
            "The app seeks the timeline frame by frame in its own browser and encodes H.264 with the phone's encoder. " +
            "No package is installed. The video has no sound track. " +
            "path is the html file. out defaults to the same name with .mp4. fps is 24, 25, 30, or 60 (default 30). " +
            "size is the long side in pixels, 480 to 1920 (default 1280). Several minutes for a long piece."
    override val parameters = objectSchema(
        properties = mapOf(
            "path" to stringProp("The composition html in the project."),
            "out" to stringProp("Optional mp4 path in the project."),
            "fps" to intProp("24, 25, 30, or 60. Defaults to 30."),
            "size" to intProp("Long side in pixels. Defaults to 1280."),
        ),
        required = listOf("path"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val path = args.stringArg("path")?.trim()?.trimStart('/').orEmpty()
        if (path.isEmpty()) return ToolResult.error("Missing 'path'.")
        val html = runCatching { workspace.resolve(path) }.getOrElse { return ToolResult.error(it.message ?: "Bad path.") }
        if (!html.isFile) return ToolResult.error("No file at $path. Write the composition first.")
        val outArg = args.stringArg("out")?.trim()?.trimStart('/')?.ifBlank { null }
            ?: (path.substringBeforeLast('.') + ".mp4")
        if (!outArg.endsWith(".mp4", ignoreCase = true)) return ToolResult.error("out must end with .mp4.")
        val out = runCatching { workspace.resolve(outArg) }.getOrElse { return ToolResult.error(it.message ?: "Bad out.") }
        val fps = (args.intArg("fps") ?: 30).let { if (it in setOf(24, 25, 30, 60)) it else 30 }
        val size = (args.intArg("size") ?: 1280).coerceIn(480, 1920)
        val outcome = VideoRenderHub.render(html, out, fps, size)
        if (!outcome.ok) return ToolResult.error(outcome.text)
        onRendered(outArg)
        return ToolResult.ok(outcome.text.replace(out.absolutePath, outArg))
    }
}
