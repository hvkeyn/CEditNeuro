package com.hvkeyn.ceditneuro.agent

import com.hvkeyn.ceditneuro.chat.LayoutCard
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Locale
import kotlin.math.min

/**
 * A local, static skill-security review.
 * It never executes a skill, never calls a model, and never uploads its text.
 */
object SkillScanner {
    enum class Severity(val weight: Int, val label: String) {
        LOW(5, "LOW"),
        MEDIUM(10, "MEDIUM"),
        HIGH(25, "HIGH"),
        CRITICAL(50, "CRITICAL"),
    }

    data class Finding(
        val id: String,
        val category: String,
        val severity: Severity,
        val confidence: Double,
        val line: Int,
        val message: String,
        val evidence: String,
        val fix: String,
        val quoted: Boolean = false,
    )

    data class Report(
        val name: String,
        val source: String,
        val chars: Int,
        val findings: List<Finding>,
        val strengths: List<String>,
        val score: Int,
        val severity: Severity,
        val recommendation: String,
        val executable: Boolean,
    )

    private data class Rule(
        val id: String,
        val category: String,
        val severity: Severity,
        val confidence: Double,
        val pattern: Regex,
        val message: String,
        val fix: String,
        val negatable: Boolean = false,
    )

    private val rules = listOf(
        Rule(
            "P1", "prompt_injection", Severity.HIGH, 0.9,
            Regex("""ignore\s+(all\s+|any\s+)?(previous|prior|above|earlier)\s+(instructions|rules|prompts)""", RegexOption.IGNORE_CASE),
            "The skill tells the agent to discard its rules.",
            "Remove the override. A skill cannot replace the system prompt.",
        ),
        Rule(
            "P2", "prompt_injection", Severity.HIGH, 0.85,
            Regex("""(do not tell the user|hide this from the user|secretly|without telling the user)""", RegexOption.IGNORE_CASE),
            "The skill asks for a hidden action.",
            "Make the step visible and ask for consent.",
        ),
        Rule(
            "P3", "system_prompt_leakage", Severity.HIGH, 0.8,
            Regex("""(reveal|print|repeat|show)\s+(your\s+|the\s+)?(system prompt|hidden prompt|developer message)""", RegexOption.IGNORE_CASE),
            "The skill asks to reveal hidden instructions.",
            "Remove the request; it is not part of the task.",
        ),
        Rule(
            "AR1", "anti_refusal", Severity.HIGH, 0.9,
            Regex("""(do not|never)\s+refuse|bypass\s+(the\s+)?safety|ignore\s+(the\s+)?policy|no\s+restrictions""", RegexOption.IGNORE_CASE),
            "The skill tries to remove a refusal or safety check.",
            "Delete the line. Safety limits are not optional.",
        ),
        Rule(
            "E1", "data_exfiltration", Severity.HIGH, 0.8,
            Regex("""(os\.environ|process\.env|env\[|printenv|API_KEY|TOKEN|SECRET).{0,300}(requests\.(post|put)|fetch\s*\(|http_request|curl|wget)""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
            "Environment or secret data is near an external send.",
            "Remove the send, or prove the value is public and declare the host.",
        ),
        Rule(
            "E2", "data_exfiltration", Severity.CRITICAL, 0.95,
            Regex("""curl[^\n]{0,160}\|\s*(ba)?sh""", RegexOption.IGNORE_CASE),
            "A downloaded script is piped into a shell.",
            "Download, inspect, and run a pinned local file only with consent.",
        ),
        Rule(
            "E3", "data_exfiltration", Severity.CRITICAL, 0.95,
            Regex("""wget[^\n]{0,160}\|\s*(ba)?sh""", RegexOption.IGNORE_CASE),
            "A downloaded script is piped into a shell.",
            "Download, inspect, and run a pinned local file only with consent.",
        ),
        Rule(
            "E4", "dangerous_code", Severity.CRITICAL, 0.9,
            Regex("""base64[^\n]{0,60}(-d|--decode)[^\n]{0,60}\|\s*(ba)?sh""", RegexOption.IGNORE_CASE),
            "An encoded payload is decoded into a shell.",
            "Remove the encoded execution path.",
        ),
        Rule(
            "E5", "privilege_escalation", Severity.CRITICAL, 0.95,
            Regex("""(authorized_keys|id_rsa|\.ssh/|/etc/shadow|/etc/passwd)""", RegexOption.IGNORE_CASE),
            "The skill touches SSH keys or local account files.",
            "Remove the access; a skill does not need host credentials.",
        ),
        Rule(
            "E6", "data_exfiltration", Severity.HIGH, 0.75,
            Regex("""(api[_-]?key|token|password|secret).{0,80}(https?://|send to|post to|upload)""", RegexOption.IGNORE_CASE),
            "A secret name is near an external destination.",
            "Remove the secret from the procedure and declare the host.",
        ),
        Rule(
            "PR1", "privilege_escalation", Severity.HIGH, 0.8,
            Regex("""\bsudo\b|\brunas\b|\bRunAs\b|\bchmod\s+777\b|\bsetuid\b"""),
            "The skill asks for elevated permissions.",
            "Use the app's own tools; do not request administrator rights.",
        ),
        Rule(
            "SC1", "supply_chain", Severity.MEDIUM, 0.7,
            Regex("""\bnpx\b|\bnpm\s+install\b|\bpip\s+install\b|\bapt(-get)?\b|\bbrew\b|\bpkg\s+install\b""", RegexOption.IGNORE_CASE),
            "The skill installs a package from an external registry.",
            "Use a tool this phone already has, or ask the user to install it.",
            negatable = true,
        ),
        Rule(
            "SC2", "supply_chain", Severity.HIGH, 0.8,
            Regex("""(curl|wget)[^\n]{0,160}(-o|--output)[^\n]{0,160}(chmod\s+\+x|\./|&&)""", RegexOption.IGNORE_CASE),
            "A downloaded file is made executable or run.",
            "Pin a checksum and ask before running a downloaded program.",
        ),
        Rule(
            "EA1", "excessive_agency", Severity.HIGH, 0.8,
            Regex("""(without asking|do not ask|don't ask|no need to ask|always run|run automatically|autonomous)""", RegexOption.IGNORE_CASE),
            "The skill removes the confirmation step.",
            "Ask before a destructive, paid, or external action.",
        ),
        Rule(
            "EA2", "excessive_agency", Severity.CRITICAL, 0.9,
            Regex("""(dangerously-skip-permissions|auto[_-]?approve|skip\s+permission|full\s+access|unrestricted\s+access)""", RegexOption.IGNORE_CASE),
            "The skill asks to bypass the permission model.",
            "Remove the bypass; permissions belong to the user.",
        ),
        Rule(
            "OH1", "output_handling", Severity.HIGH, 0.75,
            Regex("""\b(eval|exec)\s*\(|\bFunction\s*\(|new\s+Function\b"""),
            "The skill evaluates code or a string.",
            "Parse data with a normal parser; do not evaluate it.",
        ),
        Rule(
            "OH2", "output_handling", Severity.MEDIUM, 0.7,
            Regex("""(shell\s*=\s*True|os\.system\s*\(|child_process\.exec\s*\()"""),
            "A shell command is built from a variable.",
            "Use argv and a fixed command; never interpolate untrusted text.",
        ),
        Rule(
            "TM1", "tool_misuse", Severity.HIGH, 0.85,
            Regex("""\brm\s+-rf\s+/(?!\w)|\bmkfs\.|\bdd\s+if=|\bshutdown\b|\breboot\b""", RegexOption.IGNORE_CASE),
            "The skill contains a destructive system command.",
            "Remove it; scope any deletion to one project path.",
            negatable = true,
        ),
        Rule(
            "TM2", "tool_misuse", Severity.CRITICAL, 0.95,
            Regex(""":\(\)\s*\{\s*:\|:&"""),
            "A fork bomb is present.",
            "Remove the payload.",
        ),
        Rule(
            "RA1", "rogue_agent", Severity.HIGH, 0.7,
            Regex("""(self[- ]replicat|replicate\s+itself|spawn\s+unlimited|recruit\s+more\s+agents|ignore\s+the\s+orchestrator)""", RegexOption.IGNORE_CASE),
            "The skill tries to create or free an autonomous agent.",
            "Keep one agent and one bounded task.",
        ),
        Rule(
            "MP1", "memory_poisoning", Severity.HIGH, 0.75,
            Regex("""(remember|persist|store).{0,80}(instruction|rule|always|forever|next time)""", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)),
            "The skill writes a lasting rule into memory.",
            "Store facts, not instructions; ask before persisting.",
        ),
        Rule(
            "TA1", "trigger_abuse", Severity.MEDIUM, 0.6,
            Regex("""(on every message|every request|whenever the user|always active|from now on)""", RegexOption.IGNORE_CASE),
            "The skill claims a very broad trigger.",
            "Narrow the trigger to one task.",
        ),
        Rule(
            "MCP1", "mcp_least_privilege", Severity.HIGH, 0.8,
            Regex("""(allowed[-_ ]tools|permissions)\s*[:=]\s*(\*|all|unrestricted)|tools\s*:\s*\[\s*["']\*["']\s*]""", RegexOption.IGNORE_CASE),
            "The skill requests every tool or every permission.",
            "List only the tools the task needs.",
        ),
        Rule(
            "DC1", "dangerous_code", Severity.CRITICAL, 0.9,
            Regex("""(powershell[^\n]{0,50}-enc|invoke-expression|certutil[^\n]{0,40}-decode|\bmshta\b|\bregsvr32\b)""", RegexOption.IGNORE_CASE),
            "A known execution or download primitive is present.",
            "Remove the primitive; do not run an obfuscated command.",
        ),
        Rule(
            "DC2", "dangerous_code", Severity.CRITICAL, 0.9,
            Regex("""eval\s*\(\s*atob\s*\(""", RegexOption.IGNORE_CASE),
            "Base64 is decoded and evaluated.",
            "Parse the data instead of executing it.",
        ),
    )

    private val zeroWidth = Regex("[\\u200B\\u200C\\u200D\\u2060\\uFEFF\\u202A-\\u202E\\u2066-\\u2069]")
    private val codeFence = Regex("```(sh|bash|shell|python|py|javascript|js|powershell|ps1)\\b", RegexOption.IGNORE_CASE)
    private val url = Regex("""https?://""", RegexOption.IGNORE_CASE)

    fun scan(text: String, name: String = "skill", source: String = ""): Report {
        val findings = mutableListOf<Finding>()
        val lines = text.lines()

        if (zeroWidth.containsMatchIn(text)) {
            val line = lineOf(text, zeroWidth.find(text)?.range?.first ?: 0)
            findings += Finding(
                "MCP2", "mcp_tool_poisoning", Severity.HIGH, 0.9, line,
                "Hidden or bidirectional characters are present.",
                "[hidden characters removed]", "Remove invisible characters and re-save the file.",
            )
        }
        if (text.isBlank()) {
            findings += Finding("Q1", "quality", Severity.LOW, 1.0, 1, "The skill is empty.", "", "Write the procedure.")
        }
        if (text.length > 12_000) {
            findings += Finding(
                "Q2", "quality", Severity.MEDIUM, 1.0, 1,
                "The skill is longer than the 12,000-character cap.",
                "${text.length} characters", "Cut it to the procedure the agent needs.",
            )
        }
        if (text.isNotBlank() && !text.contains("Use this", ignoreCase = true)) {
            findings += Finding(
                "Q3", "quality", Severity.LOW, 0.8, 1,
                "There is no 'Use this' trigger line.",
                "", "Add one sentence that starts with 'Use this when'.",
            )
        }
        if (Regex("```").findAll(text).count() % 2 != 0) {
            findings += Finding("Q4", "quality", Severity.LOW, 1.0, 1, "A code fence is not closed.", "", "Close the fence.")
        }
        if (text.trimStart().startsWith("---") && text.indexOf("\n---", 3) < 0) {
            findings += Finding("Q5", "quality", Severity.LOW, 1.0, 1, "The front matter is not closed.", "", "Close the front matter.")
        }
        SecretText.reject(text)?.let {
            findings += Finding(
                "SEC1", "credential_exposure", Severity.HIGH, 0.9, 1,
                "A key or password pattern is present.",
                "[redacted]", "Remove the secret; keep only the name of the setting.",
            )
        }

        val seen = mutableSetOf<String>()
        for (rule in rules) {
            for (match in rule.pattern.findAll(text)) {
                val line = lineOf(text, match.range.first)
                val key = "${rule.id}:$line"
                if (!seen.add(key)) continue
                val lineText = lines.getOrElse(line - 1) { match.value }
                val quoted = quoteContext(lines, line) || (rule.negatable && isNegated(lineText))
                val severity = if (quoted) Severity.LOW else rule.severity
                val confidence = if (quoted) 0.2 else rule.confidence
                val message = if (quoted) {
                    "${rule.message} It is quoted as a bad pattern; verify the context."
                } else {
                    rule.message
                }
                findings += Finding(
                    rule.id, rule.category, severity, confidence, line, message,
                    evidence(lineText), rule.fix, quoted,
                )
            }
        }

        val executable = codeFence.containsMatchIn(text)
        var score = findings.sumOf { if (it.quoted) 0 else it.severity.weight }
        if (executable) score = (score * 1.3).toInt()
        score = min(100, score)
        val severity = when {
            score <= 20 -> Severity.LOW
            score <= 50 -> Severity.MEDIUM
            score <= 80 -> Severity.HIGH
            else -> Severity.CRITICAL
        }
        val recommendation = when (severity) {
            Severity.LOW -> "SAFE"
            Severity.MEDIUM -> "CAUTION"
            else -> "DO_NOT_INSTALL"
        }
        val strengths = buildList {
            if (text.contains("Use this", ignoreCase = true)) add("Has a clear 'Use this' trigger.")
            if (Regex("""\b(do not|don't|never|stop|ask|consent)\b""", RegexOption.IGNORE_CASE).containsMatchIn(text)) {
                add("Names a stop, a refusal, or a consent step.")
            }
            if (!url.containsMatchIn(text)) add("No external URL is present.")
            if (!executable) add("No executable code fence.")
            if (!zeroWidth.containsMatchIn(text)) add("No hidden characters.")
            if (SecretText.reject(text) == null) add("No key or password pattern.")
            if (text.length <= 12_000) add("Within the 12,000-character cap.")
            if (Regex("```").findAll(text).count() % 2 == 0) add("Code fences are balanced.")
            if (findings.none { it.severity >= Severity.HIGH }) add("No high or critical static finding.")
        }
        return Report(name, source, text.length, findings, strengths, score, severity, recommendation, executable)
    }

    fun card(report: Report): String {
        val color = when (report.severity) {
            Severity.LOW -> "#116329"
            Severity.MEDIUM -> "#9a6700"
            Severity.HIGH -> "#b42318"
            Severity.CRITICAL -> "#82071e"
        }
        val bg = when (report.severity) {
            Severity.LOW -> "#e6ffec"
            Severity.MEDIUM -> "#fff8c5"
            Severity.HIGH, Severity.CRITICAL -> "#ffebe9"
        }
        val body = buildString {
            append("<style>")
            append(".ss{font-family:-apple-system,system-ui,sans-serif;color:#1c1b1f;line-height:1.45}")
            append(".ss h2{font-size:22px;margin:0 0 2px}.ss .mut{color:#5f6368;font-size:13px}")
            append(".ss .score{display:flex;align-items:baseline;gap:10px;margin:14px 0 8px}")
            append(".ss .num{font-size:46px;font-weight:700;line-height:1}.ss .band{font:700 15px ui-monospace,monospace;padding:2px 8px;border-radius:5px}")
            append(".ss .bar{height:8px;background:#eef0f2;border-radius:99px;overflow:hidden;margin:8px 0 14px}.ss .fill{height:100%}")
            append(".ss .row{border-top:1px solid #d0d7de;padding:9px 0}.ss .tag{font:700 11px ui-monospace,monospace;padding:2px 6px;border-radius:4px;background:#eef2f6}")
            append(".ss .high{color:#b42318}.ss .med{color:#9a6700}.ss .low{color:#5f6368}")
            append(".ss .fix{color:#5f6368;font-size:13px}.ss .good{background:#f6f9fd;border-left:3px solid #0b6bcb;padding:8px 10px;margin-top:12px}")
            append("</style>")
            append("<div class=\"ss\"><h2>Skill check: ").append(esc(report.name)).append("</h2>")
            append("<div class=\"mut\">").append(esc(report.source.ifEmpty { "local skill" }))
                .append(" · ").append(report.chars).append(" chars · static scan, no execution, no upload</div>")
            append("<div class=\"score\"><span class=\"num\">").append(report.score)
                .append("</span><span class=\"band\" style=\"color:").append(color).append(";background:").append(bg).append("\">")
                .append(report.severity.label).append(" · ").append(report.recommendation).append("</span></div>")
            append("<div class=\"bar\"><div class=\"fill\" style=\"width:").append(report.score)
                .append("%;background:").append(color).append("\"></div></div>")
            if (report.findings.isEmpty()) {
                append("<div class=\"good\"><b>No static finding.</b> This is not a proof of safety; review the procedure and its source.</div>")
            } else {
                append("<div class=\"mut\">Top findings</div>")
                report.findings.sortedByDescending { it.severity }.take(6).forEach { f ->
                    val cls = when (f.severity) {
                        Severity.CRITICAL, Severity.HIGH -> "high"
                        Severity.MEDIUM -> "med"
                        else -> "low"
                    }
                    append("<div class=\"row\"><span class=\"tag ").append(cls).append("\">")
                        .append(f.severity.label).append("</span> <b>").append(esc(f.id)).append("</b> ")
                        .append(esc(f.category.replace('_', ' '))).append(" · line ").append(f.line)
                        .append(if (f.quoted) " · quoted" else "")
                        .append("<div>").append(esc(f.message)).append("</div>")
                        .append("<div class=\"fix\">").append(esc(f.fix)).append("</div></div>")
                }
            }
            if (report.strengths.isNotEmpty()) {
                append("<div class=\"good\"><b>What looks good</b><ul style=\"margin:6px 0 0 18px;padding:0\">")
                report.strengths.take(6).forEach { append("<li>").append(esc(it)).append("</li>") }
                append("</ul></div>")
            }
            append("<div class=\"mut\" style=\"margin-top:10px\">Local static scan. It checks text patterns only; a low score is not proof of safety.</div>")
            append("</div>")
        }
        val page = LayoutCard.page(body) ?: return ""
        return LayoutCard.fence(page)
    }

    fun markdown(report: Report): String = buildString {
        append("# Skill check: ").append(report.name).append("\n\n")
        append("**").append(report.score).append("/100 · ").append(report.severity.label)
            .append(" · ").append(report.recommendation).append("**\n\n")
        append(report.source.ifEmpty { "local skill" }).append(" · ").append(report.chars).append(" chars\n\n")
        if (report.findings.isEmpty()) append("No static finding.\n\n")
        else {
            append("## Findings\n\n")
            report.findings.sortedByDescending { it.severity }.forEach { f ->
                append("- **").append(f.severity.label).append(" ").append(f.id).append(" ").append(f.category)
                    .append(" line ").append(f.line).append(if (f.quoted) " · quoted context" else "")
                    .append("** — ").append(f.message).append("\n")
                    .append("  - ").append(f.fix).append("\n")
            }
            append("\n")
        }
        append("## Good\n\n")
        report.strengths.forEach { append("- ").append(it).append("\n") }
        append("\n_Static scan. No skill code was run and no text left the phone._\n")
    }

    fun json(report: Report): String = buildJsonObject {
        put("name", report.name)
        put("source", report.source)
        put("chars", report.chars)
        put("risk_score", report.score)
        put("severity", report.severity.label)
        put("recommendation", report.recommendation)
        put("executable", report.executable)
        put("findings", buildJsonArray {
            report.findings.forEach { f ->
                add(buildJsonObject {
                    put("id", f.id)
                    put("category", f.category)
                    put("severity", f.severity.label)
                    put("confidence", f.confidence)
                    put("line", f.line)
                    put("message", f.message)
                    put("quoted_context", f.quoted)
                    put("evidence", f.evidence)
                    put("fix", f.fix)
                })
            }
        })
        put("strengths", buildJsonArray { report.strengths.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
    }.toString()

    private fun lineOf(text: String, index: Int): Int =
        text.take(index.coerceAtLeast(0)).count { it == '\n' } + 1

    private fun quoteContext(lines: List<String>, line: Int): Boolean {
        val heading = (line - 1 downTo 0)
            .asSequence()
            .mapNotNull { lines.getOrNull(it)?.trim() }
            .firstOrNull { it.startsWith("#") }
            ?.lowercase(Locale.ROOT)
            .orEmpty()
        if (heading.contains("bad") || heading.contains("risk") || heading.contains("danger") ||
            heading.contains("threat") || heading.contains("attack") || heading.contains("malicious") ||
            heading.contains("unsafe") || heading.contains("warning") || heading.contains("prohibit") ||
            heading.contains("do not")
        ) {
            return true
        }
        return false
    }

    private fun evidence(line: String): String {
        val clean = line.trim().replace(Regex("\\s+"), " ")
        return if (clean.length > 180) clean.take(177) + "..." else clean
    }

    private fun isNegated(line: String): Boolean {
        val lower = line.lowercase(Locale.ROOT)
        return lower.contains("do not") || lower.contains("don't") || lower.contains("never ") ||
            lower.contains("must not") || lower.contains("avoid ")
    }

    private fun esc(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
}
