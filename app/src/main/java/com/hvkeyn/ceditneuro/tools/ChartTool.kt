package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.chat.ChartSheet
import kotlinx.serialization.json.JsonObject

/** Turns a stream of numbers into the chart block the chat draws. */
class ChartTool : Tool {
    override val name = "chart"
    override val description =
        "Draw a chart in the chat from a stream of numbers. " +
            "Call this whenever the user should see a chart, a graph, a numeric list, or a plot. " +
            "type is line, spark, bars, columns, or heatmap. ascii, unicode, and braille are drawn as a line. " +
            "data is rows of numbers, whitespace or commas, with an optional header and an optional label in the first column. " +
            "The reply must include the chart block this returns, unchanged. " +
            "Do not run npx and do not install Chartli. This tool is the drawing."
    override val parameters = objectSchema(
        properties = mapOf(
            "data" to stringProp("The number rows. A header line is allowed."),
            "type" to stringProp("line, spark, bars, columns, or heatmap. Default line."),
            "title" to stringProp("Short title, optional."),
            "x" to stringProp("X axis label, optional."),
            "y" to stringProp("Y axis label, optional."),
            "series" to stringProp("Comma-separated series names, optional."),
        ),
        required = listOf("data"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val data = args.stringArg("data")?.trim().orEmpty()
        if (data.isEmpty()) return ToolResult.error("data is required.")
        if (data.length > 12_000) return ToolResult.error("Keep the numbers under 12000 characters.")
        val header = buildString {
            val type = args.stringArg("type")?.trim().orEmpty()
            if (type.isNotEmpty()) append("type: ").append(type).append('\n')
            val title = args.stringArg("title")?.trim().orEmpty()
            if (title.isNotEmpty()) append("title: ").append(title).append('\n')
            val x = args.stringArg("x")?.trim().orEmpty()
            if (x.isNotEmpty()) append("x: ").append(x).append('\n')
            val y = args.stringArg("y")?.trim().orEmpty()
            if (y.isNotEmpty()) append("y: ").append(y).append('\n')
            val series = args.stringArg("series")?.trim().orEmpty()
            if (series.isNotEmpty()) append("series: ").append(series).append('\n')
            append(data)
        }
        val sheet = ChartSheet.parse(header)
            ?: return ToolResult.error(
                "Could not read the numbers. Use rows of numbers, type line, spark, bars, columns, or heatmap, at most 240 points and 8 series.",
            )
        return ToolResult.ok(ChartSheet.fence(sheet, data))
    }
}
