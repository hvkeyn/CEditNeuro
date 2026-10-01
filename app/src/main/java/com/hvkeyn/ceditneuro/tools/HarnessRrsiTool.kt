package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.agent.HarnessBook
import kotlinx.serialization.json.JsonObject
import java.io.File

/** Screens and accepts harness edits. The model weights are not changed. */
class HarnessRrsiTool(private val root: File) : Tool {
    override val name = "harness_rrsi"
    override val description =
        "Regularized self-improvement of this agent's harness: prompts, skills, tools, and memory. " +
            "action=status prints the annealed edit budget, the incumbent score, untried components, and what to prune. " +
            "action=screen rejects a draft that special-cases one task or carries a key. " +
            "action=judge accepts the latest Doctor report, which counts tool failures and repeated problems in the chats and texts, only when the gain clears the noise floor and pays for its tokens. " +
            "A score in the arguments is ignored. Save the draft only when judge says admissible. The model is not trained."
    override val parameters = objectSchema(
        properties = mapOf(
            "action" to stringProp("status, screen, or judge."),
            "component" to stringProp(
                "prompt, control_flow, config, output_plumbing, context_mgmt, client_tool, skill, memory, or subagent.",
            ),
            "hypothesis" to stringProp("One failure from the Doctor report (a tool, a chat, or a text), and what this edit changes. Do not quote one sentence as the rule."),
            "text" to stringProp("The draft harness text to screen or judge."),
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
                val (ok, text) = book.judge(
                    component = args.stringArg("component").orEmpty(),
                    hypothesis = args.stringArg("hypothesis").orEmpty(),
                    text = args.stringArg("text").orEmpty(),
                    edits = args.intArg("edits") ?: 1,
                )
                if (ok) ToolResult.ok(text) else ToolResult.error(text)
            }
            else -> ToolResult.error("action is one of: status, screen, judge.")
        }
    }
}
