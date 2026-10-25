package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.agent.SkillLibrary
import com.hvkeyn.ceditneuro.agent.SkillScanner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/**
 * A static vulnerability review for one skill. It reads the file, never executes it,
 * and returns either a chat card, Markdown, or JSON.
 */
class ScanSkillTool(private val skills: SkillLibrary) : Tool {
    override val name = "scan_skill"

    override val description =
        "Check a skill for vulnerabilities before saving or installing it. " +
            "Reports a 0-100 risk score, severity, recommendation, findings with line numbers and fixes, and what looks good. " +
            "Pass name and scope for a saved skill, or text for a pasted skill. " +
            "format is card (default chat card), markdown, or json. " +
            "The scan is static: it never runs the skill and never uploads it. " +
            "It checks text patterns only; it does not prove safety."

    override val parameters = objectSchema(
        properties = mapOf(
            "name" to stringProp("Name of a saved skill, for example android-apk."),
            "scope" to stringProp("app or project. Used with name."),
            "text" to stringProp("Skill text to scan instead of a saved skill."),
            "format" to stringProp("card, markdown, or json. Default card."),
        ),
        required = emptyList(),
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val name = args.stringArg("name")?.trim().orEmpty()
        val scope = args.stringArg("scope")?.trim().orEmpty()
        val text = args.stringArg("text")?.trim().orEmpty()
        val format = args.stringArg("format")?.trim()?.lowercase().orEmpty().ifEmpty { "card" }
        if (format !in setOf("card", "markdown", "json")) {
            return@withContext ToolResult.error("format is card, markdown, or json.")
        }
        val body: String
        val label: String
        val source: String
        if (text.isNotEmpty()) {
            if (text.length > 48_000) return@withContext ToolResult.error("Keep the text under 48,000 characters.")
            body = text
            label = name.ifEmpty { "pasted skill" }
            source = "pasted text"
        } else if (name.isNotEmpty()) {
            val note = skills.read(name, scope)
            if (note.error) return@withContext ToolResult.error(note.text)
            body = note.text
            label = name
            source = "saved skill · ${scope.ifEmpty { "app" }}"
        } else {
            return@withContext ToolResult.error("Pass a saved skill name, or text to scan.")
        }
        val report = SkillScanner.scan(body, label, source)
        when (format) {
            "json" -> ToolResult.ok(SkillScanner.json(report))
            "markdown" -> ToolResult.ok(SkillScanner.markdown(report))
            else -> {
                val card = SkillScanner.card(report)
                if (card.isBlank()) ToolResult.error("The report card is empty or too large.")
                else ToolResult.ok(card)
            }
        }
    }
}
