package com.hvkeyn.ceditneuro.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.ceil
import kotlin.math.cos

/**
 * Regularized search over a harness. The model stays frozen.
 *
 * Adapted from google-research/rrsi (Apache-2.0), Copyright 2026 The rrsi Authors
 * and Google LLC: annealed edit budget, stall and prune summaries, noise floor,
 * and the token cost rule. https://www.apache.org/licenses/LICENSE-2.0
 * Paper: arXiv:2609.24972.
 */
object HarnessRrsi {
    val COMPONENTS = listOf(
        "prompt",
        "control_flow",
        "config",
        "output_plumbing",
        "context_mgmt",
        "client_tool",
        "skill",
        "memory",
        "subagent",
    )
    val STRUCTURAL = setOf("client_tool", "skill", "memory", "subagent")

    data class Weights(
        val beta0: Double = 0.10,
        val beta1: Double = 44.5,
        val wScore: Double = 0.0,
        val wCost: Double = 15.0,
        val wNovelty: Double = 0.5,
        val delta: Double = 0.017,
        val budgetMin: Int = 1,
        val budgetMax: Int = 4,
        val rounds: Int = 20,
        val stallWindow: Int = 3,
        val pruneWindow: Int = 4,
    )

    data class Verdict(
        val admissible: Boolean,
        val reason: String,
        val deltaScore: Double,
        val deltaCost: Double,
        val novelty: Int,
    )

    /** b_t = ceil(b_min + (b_max - b_min) * 1/2 * (1 + cos(pi * t / T))). */
    fun editBudget(round: Int, rounds: Int, budgetMin: Int, budgetMax: Int): Int {
        if (rounds <= 0) return budgetMax
        val t = round.coerceIn(0, rounds)
        val v = budgetMin + (budgetMax - budgetMin) * 0.5 * (1.0 + cos(Math.PI * t / rounds))
        val rounded = BigDecimal.valueOf(v).setScale(9, RoundingMode.HALF_UP).toDouble()
        return ceil(rounded).toInt()
    }

    fun relativeCost(next: Double, base: Double): Double {
        if (base == 0.0) return if (next == 0.0) 0.0 else Double.POSITIVE_INFINITY
        return (next - base) / base
    }

    fun novelty(components: List<String>, acceptedCounts: Map<String, Int>): Int =
        components.toSet().count { it in STRUCTURAL && acceptedCounts[it] ?: 0 == 0 }

    /** 1 when the incumbent has not beaten the noise band across the window. */
    fun stallFlag(trajectory: List<Double>, round: Int, window: Int, delta: Double): Int {
        if (window <= 0 || round < window || round >= trajectory.size || round - window < 0) return 0
        return if (trajectory[round] - trajectory[round - window] <= delta) 1 else 0
    }

    fun leak(text: String): String? {
        val body = text.lowercase()
        val phrases = listOf(
            "if the task" to "special-cases one task",
            "if the prompt" to "special-cases one prompt",
            "if the user says" to "special-cases one utterance",
            "hidden test" to "mentions a hidden test",
            "expected answer" to "hardcodes an expected answer",
            "expected output" to "hardcodes an expected output",
            "terminal-bench" to "names an evolve suite",
            "swe-bench" to "names a benchmark suite",
            "harvey lab" to "names a benchmark suite",
        )
        phrases.firstOrNull { it.first in body }?.let { return it.second }
        if (Regex("sk-[A-Za-z0-9]{8,}").containsMatchIn(text)) return "looks like a key"
        if (Regex("(?i)api[_-]?key\\s*[:=]").containsMatchIn(text)) return "looks like a key"
        if (Regex("(?i)bearer\\s+[A-Za-z0-9._\\-]{8,}").containsMatchIn(text)) return "looks like a token"
        return null
    }

    fun judge(
        score: Double,
        cost: Double,
        baseScore: Double,
        baseCost: Double,
        star: Double,
        components: List<String>,
        acceptedCounts: Map<String, Int>,
        weights: Weights = Weights(),
    ): Verdict {
        if (score.isNaN() || cost.isNaN() || baseScore.isNaN() || baseCost.isNaN()) {
            return Verdict(false, "score or cost is not a number", 0.0, 0.0, 0)
        }
        val deltaScore = score - baseScore
        val deltaCost = relativeCost(cost, baseCost)
        val fresh = novelty(components, acceptedCounts)
        val floor = star - weights.delta
        if (score < floor) {
            return Verdict(
                false,
                "below noise-adjusted floor: score $score < star $star - delta ${weights.delta}",
                deltaScore,
                deltaCost,
                fresh,
            )
        }
        if (deltaScore > weights.delta) {
            val budget = weights.beta0 + weights.beta1 * deltaScore
            val ok = deltaCost <= budget
            val reason = "gain $deltaScore > delta ${weights.delta}; cost change $deltaCost " +
                "${if (ok) "<=" else ">"} budget $budget"
            return Verdict(ok, if (ok) "admissible: $reason" else "cost rule failed: $reason", deltaScore, deltaCost, fresh)
        }
        val shaped = weights.wScore * deltaScore - weights.wCost * deltaCost + weights.wNovelty * fresh
        val ok = shaped > 0.0
        val reason = "gain $deltaScore within delta ${weights.delta}; shaped $shaped ${if (ok) ">" else "<="} 0"
        return Verdict(ok, if (ok) "admissible: $reason" else "cost rule failed: $reason", deltaScore, deltaCost, fresh)
    }
}

/** One project's harness search: frontier plus one JSONL row per edit. */
class HarnessBook(private val root: File, private val weights: HarnessRrsi.Weights = HarnessRrsi.Weights()) {
    private val json = Json { ignoreUnknownKeys = true }
    private val dir get() = File(root, ".ceditneuro/harness")
    private val frontierFile get() = File(dir, "frontier.json")
    private val historyFile get() = File(dir, "history.jsonl")
    private val doctorFile get() = File(dir, "doctor.json")

    data class Frontier(
        val round: Int,
        val score: Double,
        val cost: Double,
        val star: Double,
        val roundEdits: Int,
        val trajectory: List<Double>,
        val doctorStamp: String = "",
        val doctorSummary: String = "",
    )

    fun status(): String {
        val frontier = readFrontier()
        val records = readHistory()
        val budget = budgetFor(frontier)
        val tried = tried(records)
        val untried = HarnessRrsi.COMPONENTS.filter { it !in tried }
        val stall = HarnessRrsi.stallFlag(frontier.trajectory, frontier.round, weights.stallWindow, weights.delta)
        val prune = pruneSet(records, frontier.round)
        val recent = records.takeLast(6).joinToString("\n") { row ->
            val component = row.component ?: "?"
            val gain = row.deltaScore?.let { " dS=$it" } ?: " unmeasured"
            "- t=${row.round} $component ${row.outcome}$gain ${row.hypothesis.orEmpty().take(80)}"
        }
        return buildString {
            append("round ${frontier.round}/${weights.rounds} budget $budget used ${frontier.roundEdits}\n")
            append("incumbent score ${frontier.score} cost ${frontier.cost} star ${frontier.star}\n")
            append("stall $stall\n")
            append("untried: ${untried.joinToString(", ").ifBlank { "none" }}\n")
            append("prune: ${prune.ifEmpty { listOf("none") }.joinToString(", ")}\n")
            if (stall == 1 && untried.isNotEmpty()) {
                append("STALL: put the next edit on an untried component: ${untried.joinToString(", ")}\n")
            }
            if (recent.isNotBlank()) append("recent:\n$recent\n")
            val doctor = readDoctor()
            if (doctor == null) {
                append("doctor: none. Press Doctor. The score comes from that report.\n")
            } else if (!frontierFile.isFile || frontier.doctorStamp == doctor.stamp) {
                append("doctor: this report is the baseline. score ${doctor.score} cost ${doctor.cost}\n")
                append(doctor.summary.take(600)).append('\n')
            } else {
                append("doctor: new report. score ${doctor.score} (incumbent ${frontier.score}) cost ${doctor.cost}\n")
                append(doctor.summary.take(600)).append('\n')
            }
            append("A harness edit is kept only when action=judge says admissible. Pass no score. The model is not trained.")
        }
    }

    fun noteDoctor(measure: com.hvkeyn.ceditneuro.agent.AgentDoctor.Measure) {
        dir.mkdirs()
        doctorFile.writeText(
            buildJsonObject {
                put("score", measure.score)
                put("cost", measure.cost)
                put("stamp", measure.stamp)
                put("summary", measure.summary.take(2_000))
            }.toString(),
            Charsets.UTF_8,
        )
        if (!frontierFile.isFile) {
            writeFrontier(
                Frontier(0, measure.score, measure.cost, measure.score, 0, listOf(measure.score), measure.stamp, measure.summary),
            )
        }
    }

    fun screen(text: String): String {
        val why = HarnessRrsi.leak(text)
        return if (why == null) "clean" else "leak: $why"
    }

    fun judge(
        component: String,
        hypothesis: String,
        text: String,
        edits: Int,
    ): Pair<Boolean, String> {
        val name = component.trim().lowercase()
        if (name !in HarnessRrsi.COMPONENTS) {
            return false to "component must be one of: ${HarnessRrsi.COMPONENTS.joinToString(", ")}"
        }
        if (hypothesis.isBlank()) return false to "A hypothesis is required, so a failed idea is not tried again."
        if (edits < 1) return false to "edits must be at least 1."
        val doctor = readDoctor()
            ?: return false to "Press Doctor first. The score comes from that report, not from a number in the chat."
        var frontier = readFrontier()
        if (!frontierFile.isFile) {
            frontier = Frontier(0, doctor.score, doctor.cost, doctor.score, 0, listOf(doctor.score), doctor.stamp, doctor.summary)
        }
        if (doctor.stamp == frontier.doctorStamp) {
            return false to "This Doctor report is already the baseline. Change the harness, let a run finish, then press Doctor again."
        }
        if (!mentions(hypothesis + "\n" + text, frontier.doctorSummary)) {
            return false to "Name a failure from the Doctor report in the hypothesis. Do not invent a score."
        }
        val leak = HarnessRrsi.leak(text + "\n" + hypothesis)
        if (leak != null) {
            append(name, hypothesis, null, null, accepted = false, outcome = "CRITIC", edits)
            return false to "leak: $leak. This draft is not kept."
        }
        val budget = budgetFor(frontier)
        if (frontier.roundEdits + edits > budget) {
            return false to "bundle of $edits does not fit budget $budget with ${frontier.roundEdits} already used this round."
        }
        val counts = acceptedCounts(readHistory())
        val verdict = HarnessRrsi.judge(
            doctor.score, doctor.cost, frontier.score, frontier.cost, frontier.star, listOf(name), counts, weights,
        )
        append(
            name,
            hypothesis,
            verdict.deltaScore,
            verdict.deltaCost,
            verdict.admissible,
            if (verdict.admissible) "ACCEPTED" else "REJECTED",
            edits,
        )
        var nextRound = frontier.round
        var used = frontier.roundEdits + edits
        var trajectory = frontier.trajectory
        val keptScore = if (verdict.admissible) doctor.score else frontier.score
        val keptCost = if (verdict.admissible) doctor.cost else frontier.cost
        val star = if (verdict.admissible) maxOf(frontier.star, doctor.score) else frontier.star
        val stamp = if (verdict.admissible) doctor.stamp else frontier.doctorStamp
        val summary = if (verdict.admissible) doctor.summary else frontier.doctorSummary
        if (used >= budget) {
            nextRound += 1
            used = 0
            trajectory = trajectory + keptScore
        }
        writeFrontier(Frontier(nextRound, keptScore, keptCost, star, used, trajectory, stamp, summary))
        val line = if (verdict.admissible) {
            "admissible. ${verdict.reason}. The draft may be saved. Do not add a special case for one task."
        } else {
            "rejected. ${verdict.reason}. Do not file this hypothesis again."
        }
        return verdict.admissible to line
    }

    private fun budgetFor(frontier: Frontier): Int =
        HarnessRrsi.editBudget(frontier.round, weights.rounds, weights.budgetMin, weights.budgetMax)

    private data class Row(
        val round: Int,
        val component: String?,
        val hypothesis: String?,
        val deltaScore: Double?,
        val outcome: String,
    )

    private fun readHistory(): List<Row> {
        if (!historyFile.isFile) return emptyList()
        return historyFile.readLines(Charsets.UTF_8).mapNotNull { line ->
            val obj = runCatching { json.parseToJsonElement(line) as? JsonObject }.getOrNull() ?: return@mapNotNull null
            Row(
                round = (obj["t"] as? JsonPrimitive)?.intOrNull ?: 0,
                component = (obj["component"] as? JsonPrimitive)?.content,
                hypothesis = (obj["hypothesis"] as? JsonPrimitive)?.content,
                deltaScore = (obj["delta_S"] as? JsonPrimitive)?.doubleOrNull,
                outcome = (obj["outcome"] as? JsonPrimitive)?.content ?: "",
            )
        }
    }

    private fun tried(rows: List<Row>): Set<String> =
        rows.mapNotNull { row -> row.component?.takeIf { row.deltaScore != null && it in HarnessRrsi.COMPONENTS } }.toSet()

    private fun acceptedCounts(rows: List<Row>): Map<String, Int> {
        val counts = HarnessRrsi.COMPONENTS.associateWith { 0 }.toMutableMap()
        rows.filter { it.outcome == "ACCEPTED" && it.component in counts }.forEach { row ->
            counts[row.component!!] = counts.getValue(row.component) + 1
        }
        return counts
    }

    private fun pruneSet(rows: List<Row>, round: Int): List<String> {
        val measured = rows.filter { it.deltaScore != null && it.component in HarnessRrsi.COMPONENTS }
        val gain = measured.map { it.component!! }.distinct().associateWith { Double.NEGATIVE_INFINITY }.toMutableMap()
        measured.forEach { row ->
            if (round - row.round <= weights.pruneWindow) {
                gain[row.component!!] = maxOf(gain.getValue(row.component), row.deltaScore!!)
            }
        }
        return gain.filterValues { it <= 0.0 }.keys.sorted()
    }

    private fun append(
        component: String,
        hypothesis: String,
        deltaScore: Double?,
        deltaCost: Double?,
        accepted: Boolean,
        outcome: String,
        edits: Int,
    ) {
        dir.mkdirs()
        val line = buildJsonObject {
            put("t", readFrontier().round)
            put("component", component)
            put("hypothesis", hypothesis.take(200))
            if (deltaScore != null) put("delta_S", deltaScore)
            if (deltaCost != null && deltaCost.isFinite()) put("delta_C", deltaCost)
            put("accepted", accepted)
            put("outcome", outcome)
            put("bundle", edits)
        }.toString()
        historyFile.appendText(line + "\n", Charsets.UTF_8)
    }

    private fun readFrontier(): Frontier {
        if (!frontierFile.isFile) return Frontier(0, 0.0, 0.0, 0.0, 0, emptyList())
        val obj = runCatching { json.parseToJsonElement(frontierFile.readText(Charsets.UTF_8)) as JsonObject }.getOrNull()
            ?: return Frontier(0, 0.0, 0.0, 0.0, 0, emptyList())
        val trajectory = (obj["trajectory"] as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? JsonPrimitive)?.doubleOrNull }
            .orEmpty()
        return Frontier(
            round = (obj["t"] as? JsonPrimitive)?.intOrNull ?: 0,
            score = (obj["S"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
            cost = (obj["C"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
            star = (obj["S_star"] as? JsonPrimitive)?.doubleOrNull ?: 0.0,
            roundEdits = (obj["round_edits"] as? JsonPrimitive)?.intOrNull ?: 0,
            trajectory = trajectory,
            doctorStamp = (obj["doctor_stamp"] as? JsonPrimitive)?.content.orEmpty(),
            doctorSummary = (obj["doctor_summary"] as? JsonPrimitive)?.content.orEmpty(),
        )
    }

    private fun writeFrontier(frontier: Frontier) {
        dir.mkdirs()
        val line = buildJsonObject {
            put("t", frontier.round)
            put("S", frontier.score)
            put("C", frontier.cost)
            put("S_star", frontier.star)
            put("round_edits", frontier.roundEdits)
            put("doctor_stamp", frontier.doctorStamp)
            put("doctor_summary", frontier.doctorSummary.take(2_000))
            put("trajectory", kotlinx.serialization.json.buildJsonArray {
                frontier.trajectory.forEach { add(JsonPrimitive(it)) }
            })
        }.toString()
        frontierFile.writeText(line, Charsets.UTF_8)
    }

    private data class DoctorNote(val score: Double, val cost: Double, val stamp: String, val summary: String)

    private fun readDoctor(): DoctorNote? {
        if (!doctorFile.isFile) return null
        val obj = runCatching { json.parseToJsonElement(doctorFile.readText(Charsets.UTF_8)) as JsonObject }.getOrNull()
            ?: return null
        val stamp = (obj["stamp"] as? JsonPrimitive)?.content ?: return null
        return DoctorNote(
            score = (obj["score"] as? JsonPrimitive)?.doubleOrNull ?: return null,
            cost = (obj["cost"] as? JsonPrimitive)?.doubleOrNull ?: 1.0,
            stamp = stamp,
            summary = (obj["summary"] as? JsonPrimitive)?.content.orEmpty(),
        )
    }

    private fun mentions(text: String, summary: String): Boolean {
        val words = summary.lowercase().split(Regex("[^a-z0-9_]+")).filter { it.length >= 5 }.toSet()
        if (words.isEmpty()) return false
        val body = text.lowercase()
        return words.any { it in body }
    }
}
