package com.hvkeyn.ceditneuro.tools

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import com.hvkeyn.ceditneuro.video.ReaSample
import com.hvkeyn.ceditneuro.video.ReaVideoPlanner
import com.hvkeyn.ceditneuro.workspace.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.Locale

/**
 * A local, offline first pass at the REA-Video idea: split a reference video into
 * shots, export a storyboard and a remake plan. It deliberately does not claim that
 * a shot is live action, AI, or 3D; the full Python CLI does that with more evidence.
 */
class ReaVideoTool(private val workspace: Workspace) : Tool {
    override val name = "rea_video"
    override val description =
        "Reverse-engineer a reference video that is already in the project. Splits it into shots " +
            "from frame changes, writes storyboard.jpg, shots/, breakdown.json, report.md, and report.html, " +
            "then returns a shot-by-shot remake plan. This offline pass does not classify live/AI/3D and " +
            "does not read speech or music; use the full REA-Video CLI on a PC for that. " +
            "Rights: rebuild the structure with material the user owns; do not reuse the source footage."

    override val parameters = objectSchema(
        properties = mapOf(
            "path" to stringProp("A video already in the project, e.g. downloads/reference.mp4."),
            "out" to stringProp("Project folder. Defaults to rea-video/<video-name>."),
            "sensitivity" to intProp("1 to 100. Higher finds more cuts. Default 70."),
            "max_shots" to intProp("Maximum shots to keep. Default 20."),
        ),
        required = listOf("path"),
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val relative = args.stringArg("path")?.trim().orEmpty()
        if (relative.isEmpty()) return@withContext ToolResult.error("path is required.")
        val source = runCatching { workspace.resolve(relative) }.getOrElse {
            return@withContext ToolResult.error(it.message ?: "Bad path.")
        }
        if (!source.isFile) return@withContext ToolResult.error("No video at $relative.")
        if (source.extension.lowercase(Locale.US) !in setOf("mp4", "m4v", "mov", "webm", "mkv", "3gp", "ts")) {
            return@withContext ToolResult.error("Use an mp4, m4v, mov, webm, mkv, 3gp, or ts video.")
        }
        val outArg = args.stringArg("out")?.trim()?.trim('/').orEmpty()
            .ifBlank { "rea-video/${source.nameWithoutExtension}" }
        val outDir = runCatching { workspace.resolve(outArg) }.getOrElse {
            return@withContext ToolResult.error(it.message ?: "Bad output folder.")
        }
        if (!outDir.exists() && !outDir.mkdirs()) return@withContext ToolResult.error("Could not create $outArg.")
        val shotsDir = File(outDir, "shots").apply { mkdirs() }
        val sensitivity = (args.intArg("sensitivity") ?: 70).coerceIn(1, 100)
        val maxShots = (args.intArg("max_shots") ?: 20).coerceIn(1, 36)
        val threshold = (100 - sensitivity).coerceIn(8, 64)

        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(source.absolutePath)
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.coerceAtLeast(1L) ?: 0L
            if (durationMs <= 0L) return@withContext ToolResult.error("The video has no readable duration.")
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val fps = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CAPTURE_FRAMERATE)?.toFloatOrNull()
            val step = (durationMs / 32L).coerceAtLeast(250L)
            val samples = mutableListOf<ReaSample>()
            var t = 0L
            while (t < durationMs && samples.size < 64) {
                val frame = frame(retriever, t * 1000L, 160, 90) ?: break
                samples += ReaSample(t, signature(frame))
                frame.recycle()
                t += step
            }
            val shots = ReaVideoPlanner.plan(durationMs, samples, threshold, maxShots)
            if (shots.isEmpty()) return@withContext ToolResult.error("No readable frames were decoded.")
            val shotJson = JSONArray()
            val rows = mutableListOf<File>()
            shots.forEach { shot ->
                val bitmap = frame(retriever, shot.startMs * 1000L, 640, 360)
                val name = "shot_%03d.jpg".format(shot.index)
                val target = File(shotsDir, name)
                if (bitmap != null) {
                    FileOutputStream(target).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 86, it) }
                    bitmap.recycle()
                    rows += target
                }
                shotJson.put(JSONObject().apply {
                    put("index", shot.index)
                    put("start_ms", shot.startMs)
                    put("end_ms", shot.endMs)
                    put("duration_ms", shot.durationMs)
                    put("frame", "shots/$name")
                    put("method", "unknown")
                    put("evidence", JSONArray().put("offline frame change ${shot.score}; frame extracted"))
                })
            }
            val storyboard = File(outDir, "storyboard.jpg")
            writeStoryboard(rows, storyboard)
            val breakdown = JSONObject().apply {
                put("source", relative)
                put("duration_ms", durationMs)
                put("width", width)
                put("height", height)
                put("rotation", rotation)
                put("fps", fps ?: 0f)
                put("sensitivity", sensitivity)
                put("shots", shotJson)
                put("limitations", JSONArray().apply {
                    put("No live/AI/3D/2D classification in this local pass")
                    put("No speech, music, BPM, or sound-effect detection")
                    put("No prompt generation or paid generation")
                })
            }
            File(outDir, "breakdown.json").writeText(breakdown.toString(2), Charsets.UTF_8)
            val md = reportMarkdown(relative, durationMs, width, height, fps, shots, outArg)
            File(outDir, "report.md").writeText(md, Charsets.UTF_8)
            File(outDir, "report.html").writeText(reportHtml(relative, durationMs, width, height, shots, breakdown), Charsets.UTF_8)
            ToolResult.ok(
                "REA-Video offline pass: ${shots.size} shot(s), ${formatDuration(durationMs)}, " +
                    "${width}x$height${if (rotation != 0) " rot=$rotation" else ""}.\n" +
                    "folder: $outArg\n" +
                    "storyboard: ${workspace.relativize(storyboard)}\n" +
                    "breakdown: ${workspace.relativize(File(outDir, "breakdown.json"))}\n" +
                    "report: ${workspace.relativize(File(outDir, "report.md"))} and report.html\n" +
                    "plan: keep the cut times and shot lengths, rebuild each shot with material you own.\n" +
                    "limit: this offline pass does not label live/AI/3D or read audio; use the full REA-Video CLI on a PC for that.",
            )
        } catch (error: Throwable) {
            ToolResult.error("REA-Video pass failed: ${error.message ?: error.javaClass.simpleName}")
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun frame(retriever: MediaMetadataRetriever, timeUs: Long, width: Int, height: Int): Bitmap? {
        val raw = runCatching {
            if (android.os.Build.VERSION.SDK_INT >= 27) {
                retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, width, height)
            } else {
                retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            }
        }.getOrNull() ?: return null
        if (raw.width == width && raw.height == height) return raw
        val scaled = Bitmap.createScaledBitmap(raw, width, height, true)
        if (scaled !== raw) raw.recycle()
        return scaled
    }

    private fun signature(bitmap: Bitmap): Int {
        val small = Bitmap.createScaledBitmap(bitmap, 8, 8, true)
        var r = 0; var g = 0; var b = 0
        for (y in 0 until 8) for (x in 0 until 8) {
            val p = small.getPixel(x, y)
            r += Color.red(p); g += Color.green(p); b += Color.blue(p)
        }
        if (small !== bitmap) small.recycle()
        return ((r / 64) shl 16) or ((g / 64) shl 8) or (b / 64)
    }

    private fun writeStoryboard(rows: List<File>, target: File) {
        if (rows.isEmpty()) return
        val tileW = 320; val tileH = 180; val label = 28; val cols = 4
        val rowsCount = (rows.size + cols - 1) / cols
        val usedCols = minOf(cols, rows.size)
        val sheet = Bitmap.createBitmap(usedCols * tileW, rowsCount * (tileH + label), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(sheet)
        canvas.drawColor(Color.rgb(18, 18, 18))
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE; textSize = 18f; typeface = Typeface.MONOSPACE
        }
        rows.forEachIndexed { index, file ->
            val x = (index % cols) * tileW
            val y = (index / cols) * (tileH + label)
            BitmapFactory.decodeFile(file.absolutePath)?.let { bitmap ->
                val scaled = Bitmap.createScaledBitmap(bitmap, tileW, tileH, true)
                canvas.drawBitmap(scaled, x.toFloat(), y.toFloat(), null)
                if (scaled !== bitmap) scaled.recycle()
                bitmap.recycle()
            }
            canvas.drawText("shot ${index + 1}", x + 10f, y + tileH + 20f, paint)
        }
        FileOutputStream(target).use { sheet.compress(Bitmap.CompressFormat.JPEG, 88, it) }
        sheet.recycle()
    }

    private fun reportMarkdown(
        source: String,
        durationMs: Long,
        width: Int,
        height: Int,
        fps: Float?,
        shots: List<com.hvkeyn.ceditneuro.video.ReaShot>,
        outArg: String,
    ): String = buildString {
        appendLine("# REA-Video local breakdown: ${File(source).name}")
        appendLine()
        appendLine("Status: offline first pass. It measures cuts and exports frames; it does **not** classify live/AI/3D or read audio.")
        appendLine()
        appendLine("- source: `$source`")
        appendLine("- duration: ${formatDuration(durationMs)}")
        appendLine("- picture: ${width}x$height${if (fps != null && fps > 0f) ", ~${"%.2f".format(Locale.US, fps)} fps" else ""}")
        appendLine("- shots: ${shots.size}")
        appendLine("- average shot: ${formatDuration(if (shots.isEmpty()) 0 else durationMs / shots.size)}")
        appendLine()
        appendLine("| # | start | end | length | frame |")
        appendLine("|---|---|---|---|---|")
        shots.forEach { s ->
            appendLine("| ${s.index} | ${formatDuration(s.startMs)} | ${formatDuration(s.endMs)} | ${formatDuration(s.durationMs)} | shots/shot_%03d.jpg |".format(s.index))
        }
        appendLine()
        appendLine("## Remake plan")
        appendLine()
        appendLine("1. Keep the cut times and each shot's length; that is the structure of the reference.")
        appendLine("2. For each shot, choose footage you own, a code/HTML animation, or a generated clip. Do not reuse the source footage, music, or a person's likeness.")
        appendLine("3. Build a quiet animatic first: `hyperframes` can turn the shot table into one HTML piece; render it when the timing is right.")
        appendLine("4. Replace one shot at a time and compare it with the storyboard at the same timecode.")
        appendLine()
        appendLine("## What is still unknown")
        appendLine()
        appendLine("- whether a shot is live action, AI video, 3D, code, or a screen recording;")
        appendLine("- the speech/music source, BPM, key, and sound-effect anchors;")
        appendLine("- on-screen text and its timing;")
        appendLine("- a model-ready prompt for each shot.")
        appendLine()
        appendLine("Run the full [REA-Video](https://github.com/JuneYaooo/rea-video) CLI on a PC for those; it is Apache-2.0 and needs Python 3.10+, ffmpeg, and optionally a vision model. Do not upload a private video to a third-party service.")
        appendLine()
        appendLine("Report folder: `$outArg` · storyboard: `storyboard.jpg` · data: `breakdown.json`.")
    }

    private fun reportHtml(
        source: String,
        durationMs: Long,
        width: Int,
        height: Int,
        shots: List<com.hvkeyn.ceditneuro.video.ReaShot>,
        breakdown: JSONObject,
    ): String {
        val rows = shots.joinToString("\n") { s ->
            "<tr><td>${s.index}</td><td>${formatDuration(s.startMs)}</td><td>${formatDuration(s.endMs)}</td>" +
                "<td>${formatDuration(s.durationMs)}</td><td><img src=\"shots/shot_%03d.jpg\" alt=\"shot ${s.index}\"></td></tr>".format(s.index)
        }
        return """<!doctype html><html lang="en"><meta charset="utf-8">
<title>REA-Video local breakdown</title>
<style>body{font:15px/1.5 system-ui,sans-serif;max-width:1100px;margin:32px auto;padding:0 20px;background:#faf8f4;color:#171717}h1{font-size:26px}table{border-collapse:collapse;width:100%}td,th{border-bottom:1px solid #ddd;padding:8px;text-align:left;vertical-align:top}img{width:240px;border-radius:4px}code{background:#eee;padding:1px 4px}.note{background:#fff3cd;padding:12px;border-left:4px solid #c28b00}</style>
<h1>REA-Video local breakdown</h1>
<p><code>${escape(source)}</code> · ${formatDuration(durationMs)} · ${width}x$height · ${shots.size} shots</p>
<p class="note">This offline pass measures cuts and exports frames. It does not classify live/AI/3D or read audio. Use the full REA-Video CLI for those calls.</p>
<h2>Storyboard</h2><p><img src="storyboard.jpg" alt="storyboard" style="width:100%"></p>
<h2>Shots</h2><table><thead><tr><th>#</th><th>start</th><th>end</th><th>length</th><th>frame</th></tr></thead><tbody>$rows</tbody></table>
<h2>Remake plan</h2><ol><li>Keep the cut times and shot lengths.</li><li>Use footage you own, code animation, or generated clips; do not reuse the source footage or music.</li><li>Build a quiet animatic first, then replace one shot at a time.</li></ol>
<p>Generated from <code>${escape(source)}</code>. Data: <code>breakdown.json</code>.</p>
</html>"""
    }

    private fun formatDuration(ms: Long): String {
        val total = (ms / 1000).coerceAtLeast(0)
        val minutes = total / 60
        val seconds = total % 60
        return if (minutes > 0) "%d:%02d".format(Locale.US, minutes, seconds) else "${seconds}s"
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
