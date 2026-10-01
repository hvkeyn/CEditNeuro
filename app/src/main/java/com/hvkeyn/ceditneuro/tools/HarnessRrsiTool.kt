package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.agent.HarnessBook
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.File

/** Screens and accepts harness edits. The model weights are not changed. */
class HarnessRrsiTool(private val root: File) : Tool {
    override val name = "harness_rrsi"
    override val description =
        "Regularized self-improvement of this agent's harness: prompts, skills, tools, and memory. " +
            "action=status prints the annealed edit budget, the incumbent score, untried components, and what to prune. " +
            "action=screen rejects a draft that special-cases one task or carries a key. " +
            "action=judge accepts a measured edit only when the gain clears the noise floor and pays for its tokens. " +
            "Save the draft only when judge says admissible. The model is not trained."
    override val parameters = objectSchema(
        properties = mapOf(
            "action" to stringProp("status, screen, or judge."),
            "component" to stringProp(
                "prompt, control_flow, config, output_plumbing, context_mgmt, client_tool, skill, memory, or subagent.",
            ),
            "hypothesis" to stringProp("What this edit is supposed to change, in one sentence."),
            "text" to stringProp("The draft harness text to screen or judge."),
            "score" to stringProp("Measured score after the edit, from 0 to 1."),
            "cost" to stringProp("Measured token or character cost after the edit."),
            "base_score" to stringProp("Score before any edit, used only to seed the incumbent."),
            "base_cost" to stringProp("Cost before any edit, used only to seed the incumbent."),
            "edits" to intProp("How many independent edits this candidate bundles. Default 1."),
        ),
        required = listOf("action"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val book = HarnessBook(root)
        val action = args.stringArg("action")?.trim()?.lowercase().orEmpty()
        return when (action) {
            "status" -> ToolResult.ok(book.status())
            "screen" -> {
                val text = args.stringArg("text").orEmpty()
                if (text.isBlank()) ToolResult.error("Missing text.")
                else {
                    val verdict = book.screen(text)
                    if (verdict.startsWith("leak")) ToolResult.error(verdict) else ToolResult.ok(verdict)
                }
            }
            "judge" -> {
                val score = args.real("score")
                val cost = args.real("cost")
                if (score == null || cost == null) return ToolResult.error("judge needs score and cost.")
                val (ok, text) = book.judge(
                    component = args.stringArg("component").orEmpty(),
                    hypothesis = args.stringArg("hypothesis").orEmpty(),
                    text = args.stringArg("text").orEmpty(),
                    score = score,
                    cost = cost,
                    baseScore = args.real("base_score"),
                    baseCost = args.real("base_cost"),
                    edits = args.intArg("edits") ?: 1,
                )
                if (ok) ToolResult.ok(text) else ToolResult.error(text)
            }
            else -> ToolResult.error("action is one of: status, screen, judge.")
        }
    }
}

private fun JsonObject.real(key: String): Double? =
    (this[key] as? JsonPrimitive)?.content?.trim()?.toDoubleOrNull()
