package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.video.ImageShrink
import com.hvkeyn.ceditneuro.video.ShrinkPlan
import com.hvkeyn.ceditneuro.video.VideoShrink
import com.hvkeyn.ceditneuro.workspace.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File

/**
 * Shrinks a picture or a video that is already in the project.
 * The phone's own encoders do it. CompressO, FFmpeg, pngquant, and jpegoptim are not installed.
 */
class CompressMediaTool(private val workspace: Workspace) : Tool {
    override val name = "compress_media"
    override val description =
        "Shrink a jpg, png, webp, or mp4 that is already in the project. " +
            "quality is 1 to 100. A lower number is smaller. max_side is the longest side in pixels. " +
            "format is jpg, png, webp, or mp4, and it stays the same kind as the source. " +
            "The smaller file is written beside the source unless dest is set. The source is kept. " +
            "Do not install CompressO, FFmpeg, pngquant, or jpegoptim. A gif or an svg is not shrunk."
    override val parameters = objectSchema(
        properties = mapOf(
            "path" to stringProp("The picture or video in the project."),
            "quality" to intProp("1 to 100. Default 60."),
            "format" to stringProp("jpg, png, webp, or mp4. Default is the source type."),
            "max_side" to intProp("Longest side in pixels. Optional."),
            "dest" to stringProp("Output path. Optional."),
        ),
        required = listOf("path"),
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val path = args.stringArg("path")?.trim().orEmpty()
        if (path.isEmpty()) return@withContext ToolResult.error("path is required.")
        val input = runCatching { workspace.resolve(path) }.getOrElse {
            return@withContext ToolResult.error(it.message ?: "Bad path.")
        }
        if (!input.isFile) return@withContext ToolResult.error("No file at $path.")
        val sourceKind = kind(input.extension)
            ?: return@withContext ToolResult.error("Use a jpg, png, webp, or mp4. A gif or an svg stays as it is.")
        val asked = args.stringArg("format")?.trim()?.lowercase().orEmpty()
        val format = if (asked.isEmpty()) sourceKind else asked
        val outKind = kind(format)
            ?: return@withContext ToolResult.error("format is jpg, png, webp, or mp4.")
        if ((sourceKind == "mp4") != (outKind == "mp4")) {
            return@withContext ToolResult.error("A picture stays a picture and a video stays an mp4.")
        }
        val quality = ShrinkPlan.quality(args.intArg("quality") ?: 60)
        val maxSide = args.intArg("max_side")?.takeIf { it > 0 }
        val destArg = args.stringArg("dest")?.trim().orEmpty()
        val output = if (destArg.isEmpty()) {
            File(input.parentFile, "${input.nameWithoutExtension}.small.$outKind")
        } else {
            runCatching { workspace.resolve(destArg) }.getOrElse {
                return@withContext ToolResult.error(it.message ?: "Bad dest.")
            }
        }
        if (output.extension.lowercase().let { kind(it) } != outKind && destArg.isNotEmpty()) {
            return@withContext ToolResult.error("The output name has to end in .$outKind.")
        }
        val text = try {
            if (outKind == "mp4") {
                val report = VideoShrink.write(input, output, quality, maxSide)
                val sound = if (report.audio) "Sound was copied." else "No AAC sound was copied."
                "Wrote ${output.name}. Before ${report.before} bytes, after ${report.after} bytes. " +
                    "${report.width}x${report.height} at ${report.fps} fps, quality $quality. $sound"
            } else {
                val named = if (output.extension.isEmpty()) File(output.parentFile, output.name + ".$outKind") else output
                val report = ImageShrink.write(input, named, quality, maxSide)
                "Wrote ${named.name}. Before ${report.before} bytes, after ${report.after} bytes. " +
                    "${report.width}x${report.height}, quality $quality, $outKind."
            }
        } catch (error: Throwable) {
            return@withContext ToolResult.error(error.message ?: "The file was not shrunk.")
        }
        ToolResult.ok(text)
    }

    private fun kind(extension: String): String? = when (extension.lowercase()) {
        "jpg", "jpeg" -> "jpg"
        "png" -> "png"
        "webp" -> "webp"
        "mp4" -> "mp4"
        else -> null
    }
}
