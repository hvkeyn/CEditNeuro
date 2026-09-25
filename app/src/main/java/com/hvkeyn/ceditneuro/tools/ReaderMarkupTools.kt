package com.hvkeyn.ceditneuro.tools

import kotlinx.serialization.json.JsonObject

/** Writes the teacher's explanation into the open book's notes. */
class ReaderNoteTool(private val add: (String) -> Unit) : Tool {
    override val name = "reader_note"
    override val description = "Save a short explanation into the notes of the book that is open. One note per call."
    override val parameters = objectSchema(
        properties = mapOf("text" to stringProp("The explanation to keep on this page.")),
        required = listOf("text"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val text = args.stringArg("text")?.trim().orEmpty()
        if (text.isEmpty()) return ToolResult.error("text is required.")
        add(text)
        return ToolResult.ok("Saved the explanation into the page notes.")
    }
}

/** Places short labels on the open page, one per line, as a schematic. */
class ReaderSketchTool(private val place: (List<String>) -> Unit) : Tool {
    override val name = "reader_sketch"
    override val description =
        "Place a schematic on the open book page. labels is one short box caption per line, in reading order."
    override val parameters = objectSchema(
        properties = mapOf("labels" to stringProp("One caption per line.")),
        required = listOf("labels"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val labels = args.stringArg("labels").orEmpty().lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(8).toList()
        if (labels.isEmpty()) return ToolResult.error("labels is required.")
        place(labels)
        return ToolResult.ok("Placed ${labels.size} labels on the page.")
    }
}
