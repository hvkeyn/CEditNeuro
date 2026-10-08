package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.workspace.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** One lesson: a hidden check, a score, a notebook, and the place to resume. */
class LearnTool(private val workspace: Workspace) : Tool {
    private val loom = ConceptLoom()

    override val name = "learn"
    override val description =
        "Teach a subject on this phone. Adapted from Concept Loom. " +
            "action=frame hides the answer and returns the prompt. " +
            "action=assess scores the answer and, with a session id, saves the next step. " +
            "action=save stores the route. action=load resumes it. action=due lists concepts to review. " +
            "action=notebook starts the notes. action=note adds one. action=diagram or action=map returns a block to show. " +
            "Do not show the rationale before the learner answers."
    override val parameters = objectSchema(
        properties = mapOf(
            "action" to stringProp("frame, assess, save, load, due, notebook, note, diagram, or map."),
            "prompt" to stringProp("The question, for frame."),
            "rationale" to stringProp("Why the answer is right. Hidden until assess."),
            "format" to stringProp("choice, free-recall, prediction, debugging, or transfer."),
            "choices" to stringProp("For choice: one 'key: label' per line. At least two."),
            "expected" to stringProp("The right choice key, or keys separated by commas."),
            "criteria" to stringProp("For an open check: one criterion per line."),
            "multiple" to boolProp("True when every fitting choice must be selected."),
            "mix" to boolProp("Shuffle choices. Defaults to true."),
            "token" to stringProp("The token frame returned. Required for assess."),
            "selected" to stringProp("The chosen key, or keys separated by commas. __gap__ admits a gap."),
            "response" to stringProp("The learner's own words, for an open check."),
            "verdict" to stringProp("accurate, needs-repair, or knowledge-gap. Required for an open check."),
            "confidence" to intProp("Optional, 0 to 100."),
            "session" to stringProp("Short lesson id: letters, numbers, underscore, hyphen."),
            "topic" to stringProp("What the lesson is about."),
            "goal" to stringProp("What the learner should be able to do."),
            "phase" to stringProp("locate, weave, build, transfer, review, or complete."),
            "mode" to stringProp("guided, practice, review, or challenge."),
            "route" to stringProp("The approved route, one step per line."),
            "secured" to stringProp("Secured ideas, one per line."),
            "gaps" to stringProp("Open gaps, one per line."),
            "step" to stringProp("The idea being taught now."),
            "next" to stringProp("The exact next action. Required for save and assess."),
            "concept" to stringProp("Stable name of the idea this check covers."),
            "path" to stringProp("Notebook path inside the project. Defaults to learn/notes.md."),
            "title" to stringProp("Notebook title, or a note heading."),
            "kind" to stringProp("learner, guide, prompt, response, insight, or diagram."),
            "body" to stringProp("The note text."),
            "source" to stringProp("Diagram text. No fence."),
            "label" to stringProp("Short diagram title."),
        ),
        required = listOf("action"),
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        runCatching { run(args) }.fold(
            onSuccess = { ToolResult.ok(it) },
            onFailure = { ToolResult.error(it.message ?: "The lesson could not be saved.") },
        )
    }

    private fun run(args: JsonObject): String = when (args.stringArg("action")?.trim()?.lowercase()) {
        "frame" -> frame(args)
        "assess" -> assess(args)
        "save" -> show(loom.save(workspace.root, state(args)))
        "load" -> load(args)
        "due" -> {
            val lines = loom.due(workspace.root)
            if (lines.isEmpty()) "Nothing is due." else lines.joinToString("\n")
        }
        "notebook" -> "Notebook: " + loom.beginNotebook(workspace.root, args.stringArg("path").orEmpty(), args.stringArg("title").orEmpty()).relative()
        "note" -> "Noted in " + loom.addNote(
            workspace.root,
            args.stringArg("path").orEmpty(),
            args.stringArg("kind").orEmpty(),
            args.stringArg("body").orEmpty(),
            args.stringArg("title").orEmpty(),
        ).relative()
        "diagram" -> loom.diagram(args.stringArg("source").orEmpty(), mermaid = false, args.stringArg("label").orEmpty())
        "map" -> loom.diagram(args.stringArg("source").orEmpty(), mermaid = true, args.stringArg("label").orEmpty())
        else -> error("action is frame, assess, save, load, due, notebook, note, diagram, or map.")
    }

    private fun frame(args: JsonObject): String {
        val framed = loom.frame(
            prompt = args.stringArg("prompt").orEmpty(),
            rationale = args.stringArg("rationale").orEmpty(),
            format = args.stringArg("format").orEmpty(),
            choices = parseChoices(args.stringArg("choices").orEmpty()),
            expected = split(args.stringArg("expected").orEmpty()),
            criteria = args.stringArg("criteria").orEmpty().lines().map { it.trim() }.filter { it.isNotEmpty() },
            multiple = args.boolArg("multiple") == true,
            mix = args.boolArg("mix") != false,
        )
        return buildString {
            append("Show this check. Do not add the answer.\n")
            append("token: ").append(framed.token).append('\n')
            append("format: ").append(framed.format).append('\n')
            append(framed.prompt).append('\n')
            framed.choices.forEach { append(it.key).append(": ").append(it.label).append('\n') }
            if (framed.multiple) append("Select every fitting key.\n")
            append("__gap__: I do not know yet")
        }
    }

    private fun assess(args: JsonObject): String {
        val grade = loom.assess(
            token = args.stringArg("token").orEmpty(),
            selected = split(args.stringArg("selected").orEmpty()),
            response = args.stringArg("response").orEmpty(),
            verdict = args.stringArg("verdict").orEmpty(),
            confidence = args.intArg("confidence"),
        )
        val session = args.stringArg("session").orEmpty().trim()
        val saved = if (session.isNotEmpty()) {
            loom.record(
                workspace.root,
                session,
                args.stringArg("concept").orEmpty(),
                grade,
                args.stringArg("step").orEmpty(),
                args.stringArg("next").orEmpty(),
            )
            " Saved. Next: " + args.stringArg("next").orEmpty().trim()
        } else {
            ""
        }
        return buildString {
            append("outcome: ").append(grade.outcome).append('\n')
            if (grade.calibration != null) append("calibration: ").append(grade.calibration).append('\n')
            if (grade.expected.isNotEmpty()) append("expected: ").append(grade.expected.joinToString(", ")).append('\n')
            if (grade.criteria.isNotEmpty()) append("criteria: ").append(grade.criteria.joinToString(" | ")).append('\n')
            append("rationale: ").append(grade.rationale)
            append(saved)
        }
    }

    private fun load(args: JsonObject): String {
        val (state, sessions) = loom.load(workspace.root, args.stringArg("session").orEmpty())
        if (state == null) return "No lesson is saved."
        val list = if (args.stringArg("session").isNullOrBlank() && sessions.size > 1) {
            "\nlessons:\n" + sessions.joinToString("\n") { show(it) }
        } else {
            ""
        }
        return "Resume from this next step. Do not repeat what is already secured.\n" + show(state) + list
    }

    private fun state(args: JsonObject): JsonObject = buildJsonObject {
        put("sessionId", args.stringArg("session").orEmpty())
        put("topic", args.stringArg("topic").orEmpty())
        put("goal", args.stringArg("goal").orEmpty())
        put("phase", args.stringArg("phase").orEmpty())
        put("mode", args.stringArg("mode").orEmpty())
        put("notebookPath", args.stringArg("path").orEmpty())
        put("route", args.stringArg("route").orEmpty())
        put("secured", args.stringArg("secured").orEmpty())
        put("gaps", args.stringArg("gaps").orEmpty())
        put("currentStep", args.stringArg("step").orEmpty())
        put("nextStep", args.stringArg("next").orEmpty())
    }

    private fun show(state: JsonObject): String = state.entries.joinToString("\n") { (key, value) ->
        key + ": " + value.toString().trim('"')
    }

    private fun parseChoices(raw: String): List<ConceptLoom.Choice> =
        raw.lines().map { it.trim() }.filter { it.isNotEmpty() }.map { line ->
            val key = line.substringBefore(":").trim()
            val label = line.substringAfter(":", "").trim()
            ConceptLoom.Choice(key, label)
        }

    private fun split(raw: String): List<String> =
        raw.split(",", "\n").map { it.trim() }.filter { it.isNotEmpty() }

    private fun java.io.File.relative(): String = workspace.relativize(this)
}
