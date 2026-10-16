package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.video.CutList
import com.hvkeyn.ceditneuro.workspace.Workspace
import kotlinx.serialization.json.JsonObject

/** Writes a composition that cuts, captions, and fades any video already in the project. */
class EditVideoTool(private val workspace: Workspace) : Tool {
    override val name = "edit_video"
    override val description =
        "Cut any video already in the project. path is the mp4, webm, or 3gp. " +
            "cuts is one line per piece: start end, then optional fade or cut, then optional voice:file.wav, then the caption. " +
            "Times are seconds in the source. fade overlaps the previous piece by 0.35 seconds. " +
            "A spoken line is a wav from voiceover, named with voice: that file. " +
            "Writes an html composition beside the video. Call render_video on that html. " +
            "Do not install Concat, Whisper, or a voice-clone model. This phone does not remove a background."
    override val parameters = objectSchema(
        properties = mapOf(
            "path" to stringProp("The video in the project."),
            "cuts" to stringProp("Lines of start end fade|cut voice:file.wav caption."),
            "width" to intProp("Frame width. Default 1080."),
            "height" to intProp("Frame height. Default 1920."),
        ),
        required = listOf("path", "cuts"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val path = args.stringArg("path")?.trim()?.trimStart('/').orEmpty()
        if (path.isEmpty()) return ToolResult.error("path is required.")
        val file = runCatching { workspace.resolve(path) }.getOrElse { return ToolResult.error(it.message ?: "Bad path.") }
        if (!file.isFile) return ToolResult.error("No video at $path.")
        val pieces = CutList.parse(args.stringArg("cuts").orEmpty())
            ?: return ToolResult.error("Each line is start end, then fade or cut, then the caption. End must be after start.")
        val width = args.intArg("width") ?: 1080
        val height = args.intArg("height") ?: 1920
        val sheet = CutList.page(file.name, width, height, pieces)
            ?: return ToolResult.error("The cut is empty or longer than 300 seconds.")
        val out = path.substringBeforeLast('.') + ".cut.html"
        val reel = path.substringBeforeLast('.') + ".reel"
        val project = CutList.Project(file.name, width, height, pieces)
        runCatching {
            workspace.write(reel, CutList.write(project))
            workspace.write(out, sheet.html)
        }.getOrElse { return ToolResult.error(it.message ?: "Could not write the cut.") }
        return ToolResult.ok(
            "Saved $reel (${sheet.pieces.size} pieces, ${sheet.seconds}s). Opening that file edits the same cut. Call render_video path=$out.",
        )
    }
}
