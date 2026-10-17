package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.chat.LayoutCard
import kotlinx.serialization.json.JsonObject

/** Turns a list or a small HTML card into the block the chat draws. */
class LayoutTool : Tool {
    override val name = "layout"
    override val description =
        "Draw a visual list, a card, or a small page layout in the chat. " +
            "body is HTML or plain lines, one item per line. A line starting with [x] is done. " +
            "The reply must include the html block this returns, unchanged. " +
            "Do not write this card to a file, do not use PIL, and do not install a font. " +
            "A chart of numbers is the chart tool, not this one."
    override val parameters = objectSchema(
        properties = mapOf(
            "body" to stringProp("The card: HTML, or one list line per row."),
        ),
        required = listOf("body"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val body = args.stringArg("body")?.trim().orEmpty()
        if (body.isEmpty()) return ToolResult.error("body is required.")
        val page = LayoutCard.page(body)
            ?: return ToolResult.error("The card is empty or longer than 12000 characters.")
        return ToolResult.ok(LayoutCard.fence(page))
    }
}
