package com.hvkeyn.ceditneuro.tools

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.File
import java.security.SecureRandom
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Base64

/**
 * Lesson checks, notebook, and resume point. Adapted from Zproger/ConceptLoom (MIT).
 * The answer stays hidden until the learner has answered. The model still teaches.
 */
class ConceptLoom {
    data class Choice(val key: String, val label: String)

    data class Framed(
        val token: String,
        val prompt: String,
        val format: String,
        val multiple: Boolean,
        val choices: List<Choice>,
    )

    data class Grade(
        val format: String,
        val outcome: String,
        val rationale: String,
        val confidence: Int?,
        val calibration: String?,
        val expected: List<String>,
        val criteria: List<String>,
    )

    private data class Card(
        val createdAt: Long,
        val prompt: String,
        val rationale: String,
        val format: String,
        val choices: List<Choice>,
        val expected: Set<String>,
        val multiple: Boolean,
        val criteria: List<String>,
    )

    private val cards = HashMap<String, Card>()
    private val random = SecureRandom()
    private val json = Json { prettyPrint = true }

    fun frame(
        prompt: String,
        rationale: String,
        format: String,
        choices: List<Choice>,
        expected: List<String>,
        criteria: List<String>,
        multiple: Boolean,
        mix: Boolean,
        pick: (Int) -> Int = { bound -> random.nextInt(bound) },
        now: Long = System.currentTimeMillis(),
    ): Framed {
        discard(now)
        val cleanPrompt = required(prompt, "prompt")
        val cleanRationale = required(rationale, "rationale")
        val cleanFormat = format.ifBlank { "choice" }
        if (cleanFormat !in FORMATS) error("format is choice, free-recall, prediction, debugging, or transfer.")
        val token = token()
        if (cleanFormat != "choice") {
            if (criteria.isEmpty()) error("An open check needs at least one criterion.")
            cards[token] = Card(now, cleanPrompt, cleanRationale, cleanFormat, emptyList(), emptySet(), false, criteria.map { required(it, "criterion") })
            return Framed(token, cleanPrompt, cleanFormat, false, emptyList())
        }
        if (choices.size < 2) error("A choice check needs at least two choices.")
        val seen = HashSet<String>()
        choices.forEach { choice ->
            required(choice.key, "choice key")
            required(choice.label, "choice label")
            if (!seen.add(choice.key)) error("Choice key '${choice.key}' is repeated.")
        }
        if (expected.isEmpty()) error("expected names at least one choice key.")
        val expectedSet = expected.map { required(it, "expected key") }.toSet()
        expectedSet.forEach { key ->
            if (key !in seen) error("Expected key '$key' is not a choice.")
        }
        if (!multiple && expectedSet.size != 1) error("A single choice has exactly one expected key.")
        val arranged = if (mix) shuffle(choices, pick) else choices
        cards[token] = Card(now, cleanPrompt, cleanRationale, cleanFormat, arranged, expectedSet, multiple, emptyList())
        return Framed(token, cleanPrompt, cleanFormat, multiple, arranged)
    }

    fun assess(
        token: String,
        selected: List<String>,
        response: String,
        verdict: String,
        confidence: Int?,
        now: Long = System.currentTimeMillis(),
    ): Grade {
        discard(now)
        val card = cards.remove(required(token, "token")) ?: error("That check is unknown or expired. Ask a new one.")
        if (confidence != null && confidence !in 0..100) error("Confidence is an integer from 0 to 100.")
        if (card.format != "choice") {
            val text = required(response, "response")
            val outcome = if (text == GAP) "knowledge-gap" else required(verdict, "verdict")
            if (outcome !in OUTCOMES) error("verdict is accurate, needs-repair, or knowledge-gap.")
            return Grade(card.format, outcome, card.rationale, confidence, calibration(outcome == "accurate", confidence), emptyList(), card.criteria)
        }
        val keys = selected.map { it.trim() }.filter { it.isNotEmpty() }
        val gap = GAP in keys
        if (gap && keys.size != 1) error("__gap__ stands alone.")
        if (!gap && keys.isEmpty()) error("selected is an answer or __gap__.")
        if (!card.multiple && keys.size != 1) error("This check accepts one choice.")
        val known = card.choices.map { it.key }.toSet()
        keys.forEach { key ->
            if (key != GAP && key !in known) error("Selected key '$key' is not a choice.")
        }
        val picked = keys.toSet()
        val accurate = !gap && picked == card.expected
        val outcome = when {
            gap -> "knowledge-gap"
            accurate -> "accurate"
            else -> "needs-repair"
        }
        return Grade(card.format, outcome, card.rationale, confidence, calibration(accurate, confidence), card.expected.toList(), emptyList())
    }

    fun save(root: File, state: JsonObject): JsonObject {
        val id = sessionId(state.string("sessionId"))
        val file = sessionFile(root, id)
        val previous = readState(file)
        val phase = required(state.string("phase"), "phase").lowercase()
        if (phase !in PHASES) error("phase is locate, weave, build, transfer, review, or complete.")
        val mode = state.string("mode").ifBlank { previous?.string("mode").orEmpty() }.ifBlank { "guided" }.lowercase()
        if (mode !in MODES) error("mode is guided, practice, review, or challenge.")
        val now = Instant.now().toString()
        val written = buildJsonObject {
            put("schemaVersion", 2)
            put("sessionId", id)
            put("topic", required(state.string("topic").ifBlank { previous?.string("topic").orEmpty() }, "topic"))
            put("goal", required(state.string("goal").ifBlank { previous?.string("goal").orEmpty() }, "goal"))
            put("phase", phase)
            put("mode", mode)
            put("notebookPath", state.string("notebookPath").ifBlank { previous?.string("notebookPath").orEmpty() })
            put("route", state.string("route").ifBlank { previous?.string("route").orEmpty() })
            put("secured", state.string("secured").ifBlank { previous?.string("secured").orEmpty() })
            put("gaps", state.string("gaps").ifBlank { previous?.string("gaps").orEmpty() })
            put("evidence", state.string("evidence").ifBlank { previous?.string("evidence").orEmpty() })
            put("currentStep", state.string("currentStep").ifBlank { previous?.string("currentStep").orEmpty() })
            put("nextStep", required(state.string("nextStep"), "nextStep"))
            put("createdAt", previous?.string("createdAt").orEmpty().ifBlank { now })
            put("updatedAt", now)
            put("revision", (previous?.string("revision")?.toIntOrNull() ?: 0) + 1)
        }
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(json.encodeToString(JsonObject.serializer(), written) + "\n", Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText(Charsets.UTF_8), Charsets.UTF_8)
            temp.delete()
        }
        return written
    }

    fun load(root: File, session: String): Pair<JsonObject?, List<JsonObject>> {
        if (session.isNotBlank()) {
            val state = readState(sessionFile(root, sessionId(session)))
                ?: error("No lesson named '$session'.")
            return state to emptyList()
        }
        val dir = sessionsDir(root)
        val states = dir.listFiles { file -> file.isFile && file.extension == "json" }.orEmpty()
            .mapNotNull { readState(it) }
            .sortedByDescending { it.string("updatedAt") }
        return states.firstOrNull() to states
    }

    fun due(root: File, now: Instant = Instant.now()): List<String> {
        val (latest, sessions) = load(root, "")
        val all = if (sessions.isEmpty()) listOfNotNull(latest) else sessions
        val lines = ArrayList<String>()
        all.forEach { state ->
            evidenceMap(state.string("evidence")).forEach { (concept, fields) ->
                val after = fields["reviewAfter"]?.let { runCatching { Instant.parse(it) }.getOrNull() } ?: return@forEach
                if (!after.isAfter(now)) {
                    lines += state.string("sessionId") + "\t" + concept + "\t" + after + "\t" + fields["lastFormat"].orEmpty()
                }
            }
        }
        return lines.sorted()
    }

    fun record(root: File, session: String, concept: String, grade: Grade, currentStep: String, nextStep: String): JsonObject {
        val previous = readState(sessionFile(root, sessionId(session))) ?: error("Save the lesson before scoring it.")
        val name = concept.ifBlank { currentStep }.ifBlank { previous.string("currentStep") }
        val secured = lines(previous.string("secured")).toMutableList()
        val gaps = lines(previous.string("gaps")).toMutableList()
        if (grade.outcome == "accurate" && name.isNotBlank() && name !in secured) secured += name
        if (grade.outcome == "knowledge-gap" && name.isNotBlank() && name !in gaps) gaps += name
        val evidence = evidenceMap(previous.string("evidence")).toMutableMap()
        if (name.isNotBlank()) {
            val earlier = evidence[name].orEmpty()
            val successes = earlier["consecutiveSuccesses"]?.toIntOrNull() ?: 0
            val streak = if (grade.outcome == "accurate") successes + 1 else 0
            val dimension = DIMENSIONS[grade.format] ?: "recognition"
            val shown = if (grade.outcome == "accurate") "demonstrated" else "needs-work"
            evidence[name] = earlier + mapOf(
                "attempts" to ((earlier["attempts"]?.toIntOrNull() ?: 0) + 1).toString(),
                "successes" to ((earlier["successes"]?.toIntOrNull() ?: 0) + if (grade.outcome == "accurate") 1 else 0).toString(),
                "consecutiveSuccesses" to streak.toString(),
                "lastFormat" to grade.format,
                "lastOutcome" to grade.outcome,
                "dimension" to dimension,
                "dimensionResult" to shown,
                "reviewAfter" to reviewAfter(streak, grade.outcome),
            )
        }
        val encoded = evidence.entries.joinToString("\n") { (conceptName, fields) ->
            conceptName + "=" + fields.entries.joinToString(",") { it.key + ":" + it.value }
        }
        return save(root, buildJsonObject {
            put("sessionId", previous.string("sessionId"))
            put("topic", previous.string("topic"))
            put("goal", previous.string("goal"))
            put("phase", previous.string("phase").ifBlank { "build" })
            put("mode", previous.string("mode"))
            put("route", previous.string("route"))
            put("secured", secured.joinToString("\n"))
            put("gaps", gaps.joinToString("\n"))
            put("evidence", encoded)
            put("currentStep", currentStep.ifBlank { previous.string("currentStep") })
            put("nextStep", nextStep)
        })
    }

    fun beginNotebook(root: File, path: String, title: String): File {
        val file = inside(root, path.ifBlank { "learn/notes.md" })
        file.parentFile?.mkdirs()
        if (!file.isFile || file.length() == 0L) {
            val heading = title.ifBlank { "Learning session" }
            file.writeText("# $heading\n\n_Lesson notes. The chat itself is not copied._\n", Charsets.UTF_8)
        }
        return file
    }

    fun addNote(root: File, path: String, kind: String, body: String, title: String): File {
        if (kind !in KINDS) error("kind is learner, guide, prompt, response, insight, or diagram.")
        val file = beginNotebook(root, path, "")
        val heading = title.ifBlank {
            when (kind) {
                "learner" -> "LEARNER"
                "guide" -> "GUIDE"
                "prompt" -> "CHECKPOINT"
                "response" -> "CHECKPOINT RESULT"
                "insight" -> "KEY CONNECTION"
                else -> "DIAGRAM"
            }
        }
        val quote = required(body, "body").lines().joinToString("\n") { "> $it" }
        file.appendText("\n> [!$kind] $heading\n>\n$quote\n", Charsets.UTF_8)
        return file
    }

    fun diagram(source: String, mermaid: Boolean, label: String): String {
        val clean = required(source, "source")
        if (clean.contains("```")) error("A diagram does not contain a fence.")
        val fence = if (mermaid) "mermaid" else "text"
        val title = label.ifBlank { if (mermaid) "Relation" else "Diagram" }
        return "$title\n```$fence\n$clean\n```\nShow this block in the reply."
    }

    private fun reviewAfter(streak: Int, outcome: String): String {
        val days = if (outcome == "accurate") INTERVALS[streak.coerceIn(1, INTERVALS.size) - 1] else 1
        return Instant.now().plus(days.toLong(), ChronoUnit.DAYS).toString()
    }

    private fun evidenceMap(raw: String): Map<String, Map<String, String>> {
        if (raw.isBlank()) return emptyMap()
        return raw.lines().mapNotNull { line ->
            val name = line.substringBefore("=", "").trim()
            if (name.isEmpty()) return@mapNotNull null
            val fields = line.substringAfter("=", "").split(",").mapNotNull { field ->
                val key = field.substringBefore(":", "").trim()
                val value = field.substringAfter(":", "").trim()
                if (key.isEmpty()) null else key to value
            }.toMap()
            name to fields
        }.toMap()
    }

    private fun lines(raw: String): List<String> = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }

    private fun token(): String {
        val bytes = ByteArray(18)
        random.nextBytes(bytes)
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    private fun discard(now: Long) {
        cards.entries.removeAll { now - it.value.createdAt > TTL }
    }

    private fun <T> shuffle(items: List<T>, pick: (Int) -> Int): List<T> {
        val copy = items.toMutableList()
        for (cursor in copy.lastIndex downTo 1) {
            val swap = pick(cursor + 1).coerceIn(0, cursor)
            val held = copy[cursor]
            copy[cursor] = copy[swap]
            copy[swap] = held
        }
        return copy
    }

    private fun calibration(accurate: Boolean, confidence: Int?): String? = when {
        confidence == null -> null
        !accurate && confidence >= 75 -> "overconfident"
        accurate && confidence <= 40 -> "underconfident"
        else -> "well-calibrated"
    }

    private fun sessionsDir(root: File): File = File(root, ".ceditneuro/learn/sessions")

    private fun sessionFile(root: File, id: String): File = File(sessionsDir(root), "$id.json")

    private fun readState(file: File): JsonObject? {
        if (!file.isFile) return null
        return json.parseToJsonElement(file.readText(Charsets.UTF_8)).jsonObject
    }

    private fun inside(root: File, requested: String): File {
        val dest = File(root, requested).canonicalFile
        val base = root.canonicalFile
        if (dest != base && !dest.path.startsWith(base.path + File.separator)) {
            error("The notebook stays inside the project.")
        }
        return dest
    }

    private fun sessionId(value: String): String {
        val id = required(value, "sessionId")
        if (!SESSION.matches(id)) error("sessionId is letters, numbers, underscores, or hyphens.")
        return id
    }

    private fun required(value: String, label: String): String {
        val clean = value.trim()
        if (clean.isEmpty()) error("$label is required.")
        return clean
    }

    private fun JsonObject.string(key: String): String =
        (this[key]?.jsonPrimitive?.content).orEmpty()

    private companion object {
        const val GAP = "__gap__"
        const val TTL = 6L * 60L * 60L * 1000L
        val FORMATS = setOf("choice", "free-recall", "prediction", "debugging", "transfer")
        val OUTCOMES = setOf("accurate", "needs-repair", "knowledge-gap")
        val PHASES = setOf("locate", "weave", "build", "transfer", "review", "complete")
        val MODES = setOf("guided", "practice", "review", "challenge")
        val KINDS = setOf("learner", "guide", "prompt", "response", "insight", "diagram")
        val INTERVALS = intArrayOf(1, 3, 7, 14, 30)
        val DIMENSIONS = mapOf(
            "choice" to "recognition",
            "free-recall" to "recall",
            "prediction" to "application",
            "debugging" to "application",
            "transfer" to "transfer",
        )
        val SESSION = Regex("^[A-Za-z0-9][A-Za-z0-9_-]{0,63}$")
    }
}
