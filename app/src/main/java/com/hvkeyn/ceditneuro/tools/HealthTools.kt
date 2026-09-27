package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.agent.HealthRecord
import com.hvkeyn.ceditneuro.workspace.Workspace
import kotlinx.serialization.json.JsonObject

class HealthLogTool(private val workspace: Workspace) : Tool {
    override val name = "health_log"
    override val description =
        "Save one number copied from a lab sheet or a report already in the project. " +
            "person is who the sheet is for. name is the row on the sheet. " +
            "low and high are the reference range printed on that sheet. Leave them empty when the sheet has none. " +
            "Do not invent a number, a range, or a disease. source is the file path."
    override val parameters = objectSchema(
        properties = mapOf(
            "person" to stringProp("Who the sheet is for."),
            "name" to stringProp("The row name, such as hemoglobin."),
            "value" to stringProp("The number printed on the sheet."),
            "date" to stringProp("The date printed on the sheet."),
            "unit" to stringProp("The unit printed next to the value."),
            "low" to stringProp("Lower end of the range printed on the sheet, if any."),
            "high" to stringProp("Upper end of the range printed on the sheet, if any."),
            "source" to stringProp("Project path of the file the number came from."),
        ),
        required = listOf("person", "name", "value"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val note = HealthRecord.add(
            root = workspace.root,
            person = args.stringArg("person").orEmpty(),
            name = args.stringArg("name").orEmpty(),
            value = args.stringArg("value").orEmpty(),
            date = args.stringArg("date").orEmpty(),
            unit = args.stringArg("unit").orEmpty(),
            low = args.stringArg("low").orEmpty(),
            high = args.stringArg("high").orEmpty(),
            source = args.stringArg("source").orEmpty(),
        )
        return if (note.error) ToolResult.error(note.text) else ToolResult.ok(note.text)
    }
}

class HealthPanelTool(private val workspace: Workspace) : Tool {
    override val name = "health_panel"
    override val description =
        "List saved readings and mark each one against the range that was saved with it. " +
            "person limits the list to one person. Omit person to list everyone. " +
            "Quote this result. Do not add a disease name. A value outside the printed range is for a doctor to see."
    override val parameters = objectSchema(
        properties = mapOf(
            "person" to stringProp("Who to list. Omit to list every saved person."),
        ),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val note = HealthRecord.panel(workspace.root, args.stringArg("person").orEmpty())
        return if (note.error) ToolResult.error(note.text) else ToolResult.ok(note.text)
    }
}
