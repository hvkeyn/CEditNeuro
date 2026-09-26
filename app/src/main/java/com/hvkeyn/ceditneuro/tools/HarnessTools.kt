package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.agent.ProjectMemory
import com.hvkeyn.ceditneuro.agent.SessionSearch
import com.hvkeyn.ceditneuro.agent.SkillLibrary
import com.hvkeyn.ceditneuro.data.StoredSession
import kotlinx.serialization.json.JsonObject
import java.io.File

class ListSkillsTool(private val skills: SkillLibrary) : Tool {
    override val name = "list_skills"
    override val description =
        "List procedure files for this project and for this phone. Each line is a name, a scope, and a one-line summary."
    override val parameters = objectSchema(emptyMap())

    override suspend fun execute(args: JsonObject): ToolResult {
        val entries = skills.list()
        if (entries.isEmpty()) {
            return ToolResult.ok("No skills yet. save_skill creates one. scope is project or app.")
        }
        return ToolResult.ok(entries.joinToString("\n") { "${it.name} (${it.scope}): ${it.summary}" })
    }
}

class ReadSkillTool(private val skills: SkillLibrary) : Tool {
    override val name = "read_skill"
    override val description =
        "Read one skill before following it. scope is project or app."
    override val parameters = objectSchema(
        properties = mapOf(
            "name" to stringProp("Skill name."),
            "scope" to stringProp("project or app."),
        ),
        required = listOf("name", "scope"),
    )

    override suspend fun execute(args: JsonObject): ToolResult = note(
        skills.read(args.stringArg("name").orEmpty(), args.stringArg("scope").orEmpty()),
    )
}

class SaveSkillTool(private val skills: SkillLibrary) : Tool {
    override val name = "save_skill"
    override val description =
        "Create or replace a skill. The text is a procedure the next run can read. " +
            "scope project stays in this folder. scope app is available in every project. " +
            "A skill does not add a permission or a tool. Do not store passwords or keys."
    override val parameters = objectSchema(
        properties = mapOf(
            "name" to stringProp("Short name, letters and hyphens."),
            "text" to stringProp("The full procedure in Markdown."),
            "scope" to stringProp("project or app."),
        ),
        required = listOf("name", "text", "scope"),
    )

    override suspend fun execute(args: JsonObject): ToolResult = note(
        skills.save(
            args.stringArg("name").orEmpty(),
            args.stringArg("text").orEmpty(),
            args.stringArg("scope").orEmpty(),
        ),
    )
}

class AppendSkillTool(private val skills: SkillLibrary) : Tool {
    override val name = "append_skill"
    override val description =
        "Add steps to an existing skill. Read it first, then append the new part. scope is project or app."
    override val parameters = objectSchema(
        properties = mapOf(
            "name" to stringProp("Skill name."),
            "text" to stringProp("The extra steps to add."),
            "scope" to stringProp("project or app."),
        ),
        required = listOf("name", "text", "scope"),
    )

    override suspend fun execute(args: JsonObject): ToolResult = note(
        skills.append(
            args.stringArg("name").orEmpty(),
            args.stringArg("text").orEmpty(),
            args.stringArg("scope").orEmpty(),
        ),
    )
}

class DeleteSkillTool(private val skills: SkillLibrary) : Tool {
    override val name = "delete_skill"
    override val description = "Delete one skill. scope is project or app."
    override val parameters = objectSchema(
        properties = mapOf(
            "name" to stringProp("Skill name."),
            "scope" to stringProp("project or app."),
        ),
        required = listOf("name", "scope"),
    )

    override suspend fun execute(args: JsonObject): ToolResult = note(
        skills.delete(args.stringArg("name").orEmpty(), args.stringArg("scope").orEmpty()),
    )
}

class RememberTool(private val root: File) : Tool {
    override val name = "remember"
    override val description =
        "Save a short note about this project for the next run. " +
            "append true adds a line. append false replaces the note. Do not store passwords or keys."
    override val parameters = objectSchema(
        properties = mapOf(
            "text" to stringProp("The note to keep."),
            "append" to boolProp("True adds the note. False replaces it. Default is true."),
        ),
        required = listOf("text"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val append = args.boolArg("append") ?: true
        val note = ProjectMemory.store(root, args.stringArg("text").orEmpty(), append)
        return if (note.error) ToolResult.error(note.text) else ToolResult.ok(note.text)
    }
}

class SearchSessionsTool(private val session: () -> StoredSession) : Tool {
    override val name = "search_sessions"
    override val description =
        "Search this project's earlier chat for a short phrase. " +
            "Returns a few snippets. Does not search other projects or other apps."
    override val parameters = objectSchema(
        properties = mapOf("query" to stringProp("A word or short phrase from an earlier message.")),
        required = listOf("query"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val query = args.stringArg("query").orEmpty()
        val text = runCatching { SessionSearch.query(session(), query) }.getOrElse {
            return ToolResult.error(it.message ?: "Could not search this project's chat.")
        }
        return if (text.startsWith("Query must")) ToolResult.error(text) else ToolResult.ok(text)
    }
}

private fun note(result: com.hvkeyn.ceditneuro.agent.SkillNote): ToolResult =
    if (result.error) ToolResult.error(result.text) else ToolResult.ok(result.text)
