package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.agent.DebugCase
import com.hvkeyn.ceditneuro.workspace.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** Keeps one failure investigation in order. It refuses a fix that has no supporting test. */
class DebugCaseTool(private val workspace: Workspace) : Tool {
    override val name = "debug_case"
    override val description =
        "Track one bug until its cause is tested. " +
            "action=open needs symptom and steps. " +
            "action=reproduce needs check and result=fail, flaky, or pass. " +
            "action=fact needs text and source. action=hypothesis needs text. " +
            "action=test needs id, check, and result=supports or rules-out. " +
            "action=cause and action=fix need text. " +
            "action=verify needs check (the same one that failed) and result=pass or fail. " +
            "action=status prints the case. A key is rejected. The case passes only when that same check passes."
    override val parameters = objectSchema(
        properties = mapOf(
            "action" to stringProp("open, reproduce, fact, hypothesis, test, cause, fix, verify, or status."),
            "symptom" to stringProp("What failed, in one sentence."),
            "steps" to stringProp("The steps that should show the failure."),
            "check" to stringProp("The command, test, or file read that shows it."),
            "result" to stringProp("fail, flaky, pass, supports, or rules-out, depending on the action."),
            "text" to stringProp("The fact, the hypothesis, the cause, or the one edit."),
            "source" to stringProp("The tool and path the fact came from."),
            "id" to stringProp("The hypothesis number to test."),
        ),
        required = listOf("action"),
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val fields = mapOf(
            "symptom" to args.stringArg("symptom").orEmpty(),
            "steps" to args.stringArg("steps").orEmpty(),
            "check" to args.stringArg("check").orEmpty(),
            "result" to args.stringArg("result").orEmpty(),
            "text" to args.stringArg("text").orEmpty(),
            "source" to args.stringArg("source").orEmpty(),
            "id" to args.stringArg("id").orEmpty(),
        )
        val note = DebugCase.apply(workspace.root, args.stringArg("action").orEmpty(), fields)
        if (note.error) ToolResult.error(note.text) else ToolResult.ok(note.text)
    }
}
