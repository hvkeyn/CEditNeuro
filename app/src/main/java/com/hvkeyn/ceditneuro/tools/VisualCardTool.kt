package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.chat.LayoutCard
import kotlinx.serialization.json.JsonObject

/** Draws a small technical SVG or diagram in the chat as a paper-like card. */
class VisualCardTool : Tool {
    override val name = "visual_card"

    override val description =
        "Draw a static diagram in the chat: a process flow, architecture, sequence, timeline, comparison, block diagram, exploded view, section, or technical sheet. " +
            "Pass a complete responsive SVG with a viewBox; it is shown on a light paper card. " +
            "Keep the SVG under 10000 characters and use no script, external image, foreignObject, or event handler. " +
            "The reply must include the returned html block unchanged. For ASCII art use ascii_art; for a large drawing write drawing.svg and open_file."

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
        if (svg.length > 10_000) return ToolResult.error("svg is longer than 10000 characters.")
        val blocked = listOf("<script", "<iframe", "<object", "<embed", "<foreignObject", "<image", "javascript:")
        if (blocked.any { svg.contains(it, ignoreCase = true) } ||
            Regex("""\son[a-z]+\s*=""", RegexOption.IGNORE_CASE).containsMatchIn(svg)
        ) {
            return ToolResult.error("svg contains a script, handler, external image, or embedded object. Use shapes only.")
        }
        val title = args.stringArg("title")?.trim().orEmpty().take(60)
        val caption = args.stringArg("caption")?.trim().orEmpty().take(240)
        val body = buildString {
            append("<style>")
            append(".vc{background:#f7f4ec;color:#1c1b1f;border:1px solid #d8d1c2;border-radius:12px;padding:16px 16px 12px;overflow:auto}")
            append(".vc .t{font:700 12px/1.2 -apple-system,system-ui,sans-serif;letter-spacing:.12em;color:#5f6368;text-transform:uppercase;margin:0 0 8px}")
            append(".vc svg{display:block;width:100%;height:auto;margin:0 auto}")
            append(".vc .c{font:14px/1.4 -apple-system,system-ui,sans-serif;color:#4a4a4a;margin:10px 0 0}")
            append("</style>")
            append("<div class=\"vc\">")
            if (title.isNotEmpty()) append("<p class=\"t\">").append(esc(title)).append("</p>")
            append(svg)
            if (caption.isNotEmpty()) append("<p class=\"c\">").append(esc(caption)).append("</p>")
            append("</div>")
        }
        val page = LayoutCard.page(body) ?: return ToolResult.error("The drawing card is empty or too large.")
        return ToolResult.ok(LayoutCard.fence(page))
    }

    private fun esc(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
