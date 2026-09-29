package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.agent.ResearchRun
import com.hvkeyn.ceditneuro.workspace.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** Keeps one deep study in order and refuses a report whose cites do not match the quotes. */
class ResearchRunTool(private val workspace: Workspace) : Tool {
    override val name = "research_run"
    override val description =
        "Track one deep study until it passes. " +
            "action=open needs question and tier=light or full. " +
            "action=plan needs text with two or more questions separated by |. " +
            "action=source needs title, locator, quote, and claim. A reprint does not count. " +
            "action=tension is for full. action=draft writes the report once. " +
            "action=critic name is cite, independence, or gap. " +
            "action=patch replaces one exact span and cannot rewrite the draft. " +
            "action=cite needs id, sentence, and supports=yes or no. " +
            "action=status shows the next step. action=pass refuses while a step or a supported cite is missing. " +
            "A dissertation-length run is refused. A key is rejected."
    override val parameters = objectSchema(
        properties = mapOf(
            "action" to stringProp("open, plan, source, tension, draft, critic, patch, cite, pass, or status."),
            "question" to stringProp("The user's question, for action=open."),
            "tier" to stringProp("light or full."),
            "text" to stringProp("The plan, the draft, the tension, the critic note, or the patch replacement."),
            "title" to stringProp("The source title a tool printed."),
            "locator" to stringProp("A URL, DOI, arXiv id, or project path a tool printed."),
            "quote" to stringProp("Words copied from that tool result."),
            "claim" to stringProp("What this source is being used to support."),
            "name" to stringProp("cite, independence, or gap."),
            "id" to stringProp("The source id to cite."),
            "sentence" to stringProp("The draft sentence being checked."),
            "supports" to stringProp("yes or no."),
            "old" to stringProp("The exact draft span to replace."),
        ),
        required = listOf("action"),
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val fields = mapOf(
            "question" to args.stringArg("question").orEmpty(),
            "tier" to args.stringArg("tier").orEmpty(),
            "text" to args.stringArg("text").orEmpty(),
            "title" to args.stringArg("title").orEmpty(),
            "locator" to args.stringArg("locator").orEmpty(),
            "quote" to args.stringArg("quote").orEmpty(),
            "claim" to args.stringArg("claim").orEmpty(),
            "name" to args.stringArg("name").orEmpty(),
            "id" to args.stringArg("id").orEmpty(),
            "sentence" to args.stringArg("sentence").orEmpty(),
            "supports" to args.stringArg("supports").orEmpty(),
            "old" to args.stringArg("old").orEmpty(),
        )
        val note = ResearchRun.apply(workspace.root, args.stringArg("action").orEmpty(), fields)
        if (note.error) ToolResult.error(note.text) else ToolResult.ok(note.text)
    }
}
