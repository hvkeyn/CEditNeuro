package com.hvkeyn.ceditneuro.tools

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.hvkeyn.ceditneuro.agent.ResearchNotebook
import com.hvkeyn.ceditneuro.workspace.Workspace
import kotlinx.serialization.json.JsonObject
import java.io.File

class ResearchLogTool(private val workspace: Workspace) : Tool {
    override val name = "research_log"
    override val description =
        "Append one short entry to research/log.md. " +
            "kind is question, hypothesis, evidence, check, or build. " +
            "Evidence must name a file or URL this run actually opened."
    override val parameters = objectSchema(
        properties = mapOf(
            "kind" to stringProp("question, hypothesis, evidence, check, or build."),
            "text" to stringProp("One short entry. Do not paste a whole paper."),
        ),
        required = listOf("kind", "text"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val note = ResearchNotebook.log(
            workspace.root,
            args.stringArg("kind").orEmpty(),
            args.stringArg("text").orEmpty(),
        )
        return if (note.error) ToolResult.error(note.text) else ToolResult.ok(note.text)
    }
}

class ResearchReportTool(private val workspace: Workspace) : Tool {
    override val name = "research_report"
    override val description =
        "Write research/report.md and research/report.pdf. " +
            "Every number must already have been printed by a tool. Write in the user's language."
    override val parameters = objectSchema(
        properties = mapOf(
            "title" to stringProp("Short title."),
            "body" to stringProp("Markdown: what was asked, what was checked, a table if needed, and what is still open."),
        ),
        required = listOf("title", "body"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val title = args.stringArg("title").orEmpty()
        val body = args.stringArg("body").orEmpty()
        val note = ResearchNotebook.report(workspace.root, title, body)
        if (note.error) return ToolResult.error(note.text)
        val pdf = File(ResearchNotebook.directory(workspace.root), "report.pdf")
        val written = runCatching { TextPdf.write(pdf, title.trim(), body.trim()) }.getOrElse {
            return ToolResult.ok("Saved research/report.md. The PDF was not written: ${it.message}")
        }
        return ToolResult.ok("Saved research/report.md and research/report.pdf ($written bytes).")
    }
}

class ResearchFigureTool(private val workspace: Workspace) : Tool {
    override val name = "research_figure"
    override val description = "Save one small SVG scheme as research/<name>.svg. No script."
    override val parameters = objectSchema(
        properties = mapOf(
            "name" to stringProp("File name without a folder. Default is figure."),
            "svg" to stringProp("One <svg> document."),
        ),
        required = listOf("svg"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val note = ResearchNotebook.figure(
            workspace.root,
            args.stringArg("name").orEmpty(),
            args.stringArg("svg").orEmpty(),
        )
        return if (note.error) ToolResult.error(note.text) else ToolResult.ok(note.text)
    }
}

class ResearchPlotTool(private val workspace: Workspace) : Tool {
    override val name = "research_plot"
    override val description =
        "Draw a line or bar chart from numbers into research/<name>.svg. Send only data; the app draws it. " +
            "Use numbers a tool already printed."
    override val parameters = objectSchema(
        properties = mapOf(
            "name" to stringProp("File name without a folder. Default is plot."),
            "title" to stringProp("Chart title."),
            "kind" to stringProp("line or bar. Default is line."),
            "labels" to stringProp("X labels separated by ';', for example 'Mon; Tue; Wed'. Optional."),
            "series" to stringProp("One series per line: 'name: 1, 2, 3'. At most 4 series and 60 values."),
        ),
        required = listOf("title", "series"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val svg = runCatching {
            com.hvkeyn.ceditneuro.agent.ResearchPlot.svg(
                title = args.stringArg("title").orEmpty(),
                kind = args.stringArg("kind").orEmpty(),
                labels = args.stringArg("labels").orEmpty().split(';').map { it.trim() }.filter { it.isNotEmpty() },
                series = com.hvkeyn.ceditneuro.agent.ResearchPlot.parseSeries(args.stringArg("series").orEmpty()),
            )
        }.getOrElse { return ToolResult.error(it.message ?: "Could not draw the chart.") }
        val note = ResearchNotebook.figure(workspace.root, args.stringArg("name").orEmpty().ifBlank { "plot" }, svg)
        return if (note.error) ToolResult.error(note.text) else ToolResult.ok(note.text + " open_file shows it.")
    }
}

internal object TextPdf {
    fun write(file: File, title: String, body: String): Int {
        val paint = Paint().apply {
            color = Color.BLACK
            textSize = 11f
            isAntiAlias = true
        }
        val titlePaint = Paint(paint).apply {
            textSize = 16f
            isFakeBoldText = true
        }
        val lines = wrap("$title\n\n$body", paint, PAGE_WIDTH - 72f)
        val doc = PdfDocument()
        val perPage = 44
        val pages = lines.chunked(perPage).take(12)
        pages.forEachIndexed { index, chunk ->
            val page = doc.startPage(PdfDocument.PageInfo.Builder(PAGE_WIDTH, PAGE_HEIGHT, index + 1).create())
            var y = 48f
            chunk.forEach { line ->
                val used = if (index == 0 && y == 48f) titlePaint else paint
                page.canvas.drawText(line, 36f, y, used)
                y += 16f
            }
            doc.finishPage(page)
        }
        file.parentFile?.mkdirs()
        file.outputStream().use { doc.writeTo(it) }
        doc.close()
        return file.length().toInt()
    }

    private fun wrap(text: String, paint: Paint, width: Float): List<String> {
        val out = mutableListOf<String>()
        text.replace('\r', '\n').lineSequence().forEach { raw ->
            if (raw.isBlank()) {
                out += ""
                return@forEach
            }
            var rest = raw
            while (rest.isNotEmpty()) {
                var end = rest.length
                while (end > 1 && paint.measureText(rest.take(end)) > width) end--
                if (end < rest.length && rest[end - 1] != ' ') {
                    val space = rest.lastIndexOf(' ', end - 1)
                    if (space > 20) end = space
                }
                out += rest.take(end).trimEnd()
                rest = rest.substring(end).trimStart()
            }
        }
        return out.ifEmpty { listOf("") }
    }

    private const val PAGE_WIDTH = 595
    private const val PAGE_HEIGHT = 842
}
