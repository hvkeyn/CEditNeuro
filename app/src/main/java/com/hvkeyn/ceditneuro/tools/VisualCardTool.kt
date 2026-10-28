package com.hvkeyn.ceditneuro.tools

import kotlinx.serialization.json.JsonObject
import java.util.Locale

/** Draws a small technical SVG or diagram in the chat as a paper-like sheet. */
class VisualCardTool : Tool {
    override val name = "visual_card"

    override val description =
        "Draw a static diagram in the chat: a process flow, architecture, sequence, timeline, comparison, block diagram, exploded view, section, or technical sheet. " +
            "Pass a complete responsive SVG with a viewBox; it is wrapped in a light paper sheet with a title and caption. " +
            "Keep the SVG under 9000 characters and use no script, external image, foreignObject, or event handler. " +
            "The reply must include the returned svg block unchanged. For ASCII art use ascii_art; for a large drawing write drawing.svg and open_file."

    override val parameters = objectSchema(
        properties = mapOf(
            "title" to stringProp("Short sheet title, at most 60 characters."),
            "svg" to stringProp("A complete SVG with a viewBox, no scripts, no external images."),
            "caption" to stringProp("Optional one-sentence caption."),
        ),
        required = listOf("svg"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val raw = args.stringArg("svg")?.trim().orEmpty()
        if (raw.isEmpty()) return ToolResult.error("svg is required.")
        val svg = raw.removePrefix("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n").trim()
        if (!svg.contains("<svg", ignoreCase = true) || !svg.contains("</svg>", ignoreCase = true)) {
            return ToolResult.error("svg must be a complete <svg>…</svg> element.")
        }
        if (!Regex("""viewBox\s*=\s*["'][^"']+["']""", RegexOption.IGNORE_CASE).containsMatchIn(svg)) {
            return ToolResult.error("svg needs a viewBox so it scales on a phone.")
        }
        if (svg.length > 9_000) return ToolResult.error("svg is longer than 9000 characters.")
        val blocked = listOf("<script", "<iframe", "<object", "<embed", "<foreignObject", "<image", "javascript:")
        if (blocked.any { svg.contains(it, ignoreCase = true) } ||
            Regex("""\son[a-z]+\s*=""", RegexOption.IGNORE_CASE).containsMatchIn(svg)
        ) {
            return ToolResult.error("svg contains a script, handler, external image, or embedded object. Use shapes only.")
        }

        val title = args.stringArg("title")?.trim().orEmpty().take(60)
        val caption = args.stringArg("caption")?.trim().orEmpty().take(240)
        val sourceTag = Regex("""<svg\b[^>]*>""", RegexOption.IGNORE_CASE).find(svg)?.value
            ?: return ToolResult.error("svg must be a complete <svg>…</svg> element.")
        val viewBox = Regex("""viewBox\s*=\s*["']([^"']+)["']""", RegexOption.IGNORE_CASE)
            .find(sourceTag)?.groupValues?.get(1)?.trim().orEmpty()
        val numbers = viewBox.split(',', ' ', '\t').mapNotNull { it.toDoubleOrNull() }
        if (numbers.size != 4 || numbers[2] <= 0 || numbers[3] <= 0) {
            return ToolResult.error("svg needs a numeric viewBox with a positive width and height.")
        }
        val sourceInner = svg.substringAfter(sourceTag).substringBeforeLast("</svg>").trim()
        if (sourceInner.isEmpty()) return ToolResult.error("svg has no drawing inside it.")

        val drawingWidth = 912.0
        val drawingHeight = (drawingWidth * numbers[3] / numbers[2]).coerceIn(180.0, 1400.0)
        val titleLines = wrap(title.ifEmpty { "Diagram" }, 58)
        val captionLines = wrap(caption, 82)
        val titleHeight = titleLines.size * 24.0
        val drawingTop = 24.0 + titleHeight + 10
        val captionTop = drawingTop + drawingHeight + 18
        val height = captionTop + captionLines.size * 18 + 24

        val out = buildString {
            append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 960 ")
            append(num(height)).append("\" width=\"100%\" role=\"img\" aria-label=\"")
            append(esc(title.ifEmpty { "Technical diagram" })).append("\">")
            append("<rect width=\"960\" height=\"").append(num(height)).append("\" rx=\"18\" fill=\"#f7f4ec\" stroke=\"#d8d1c2\"/>")
            append("<g font-family=\"ui-monospace,monospace\" fill=\"#5f6368\">")
            titleLines.forEachIndexed { index, line ->
                append("<text x=\"24\" y=\"").append(num(36.0 + index * 24.0))
                    .append("\" font-size=\"12\" letter-spacing=\"1.2\">").append(esc(line.uppercase(Locale.US))).append("</text>")
            }
            append("</g>")
            append("<svg x=\"24\" y=\"").append(num(drawingTop)).append("\" width=\"912\" height=\"")
                .append(num(drawingHeight)).append("\" viewBox=\"").append(esc(viewBox))
                .append("\" preserveAspectRatio=\"xMidYMid meet\">")
            append(sourceInner)
            append("</svg>")
            if (captionLines.isNotEmpty()) {
                append("<g font-family=\"-apple-system,system-ui,sans-serif\" fill=\"#4a4a4a\">")
                captionLines.forEachIndexed { index, line ->
                    append("<text x=\"24\" y=\"").append(num(captionTop + (index + 1) * 18.0))
                        .append("\" font-size=\"13\">").append(esc(line)).append("</text>")
                }
                append("</g>")
            }
            append("</svg>")
        }
        if (out.length > 12_000) return ToolResult.error("The sheet is longer than 12000 characters.")
        return ToolResult.ok("```svg\n$out\n```")
    }

    private fun wrap(value: String, max: Int): List<String> {
        if (value.isEmpty()) return emptyList()
        val words = value.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val lines = mutableListOf<String>()
        val line = StringBuilder()
        for (word in words) {
            if (line.isNotEmpty() && line.length + 1 + word.length > max) {
                lines += line.toString()
                line.setLength(0)
            }
            if (line.isNotEmpty()) line.append(' ')
            line.append(word)
        }
        if (line.isNotEmpty()) lines += line.toString()
        return lines
    }

    private fun num(value: Double): String = String.format(Locale.US, "%.0f", value)

    private fun esc(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
