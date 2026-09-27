package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.agent.DesignCatalog
import kotlinx.serialization.json.JsonObject

/** Looks up a public design system by plain words. It does not download a component library. */
class DesignSystemTool : Tool {
    override val name = "design_system"
    override val description =
        "Look up a public design system by name: Material, Polaris, Carbon, Apple, Stripe, or another gallery name. " +
            "Returns the gallery page and the swatches printed on that card. " +
            "Those swatches are not a full token set. Quote them, or a value printed on the page. Do not invent a hex."
    override val parameters = objectSchema(
        properties = mapOf(
            "name" to stringProp("The system or company, for example Material 3 or Polaris."),
        ),
        required = listOf("name"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val name = args.stringArg("name")?.trim().orEmpty()
        if (name.length < 2) return ToolResult.error("Name a design system, for example Material, Polaris, Carbon, Apple, or Stripe.")
        return ToolResult.ok(DesignCatalog.format(name, DesignCatalog.find(name)))
    }
}
