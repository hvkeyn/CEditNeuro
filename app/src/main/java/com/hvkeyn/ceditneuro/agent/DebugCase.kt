package com.hvkeyn.ceditneuro.agent

import java.io.File

/**
 * One failure, investigated in order. A cause needs a test that supports it.
 * The case passes only when the same check that failed now passes.
 */
object DebugCase {
    private val secret = Regex("""sk-[A-Za-z0-9]{16,}|ghp_|github_pat_|AKIA""")

    fun file(root: File): File = File(root, ".ceditneuro/debug/case.md")

    fun apply(root: File, action: String, fields: Map<String, String>): SkillNote {
        val step = action.trim().lowercase()
        if (fields.values.any { secret.containsMatchIn(it) }) {
            return SkillNote("Do not store a key in the case.", error = true)
        }
        val case = read(root)
        val note = when (step) {
            "open" -> open(case, fields)
            "reproduce" -> reproduce(case, fields)
            "fact" -> fact(case, fields)
            "hypothesis" -> hypothesis(case, fields)
            "test" -> test(case, fields)
            "cause" -> cause(case, fields)
            "fix" -> fix(case, fields)
            "verify" -> verify(case, fields)
            "status" -> SkillNote(render(case))
            else -> SkillNote("action is one of: open, reproduce, fact, hypothesis, test, cause, fix, verify, status.", error = true)
        }
        if (!note.error && step != "status") write(root, case)
        return if (note.error) note else SkillNote(render(case) + "\n" + note.text)
    }

    private fun open(case: Case, fields: Map<String, String>): SkillNote {
        val symptom = clean(fields["symptom"].orEmpty(), 300)
        val steps = clean(fields["steps"].orEmpty(), 500)
        if (symptom.isBlank() || steps.isBlank()) {
            return SkillNote("open needs symptom and steps. Do not propose a fix yet.", error = true)
        }
        case.reset(symptom, steps)
        return SkillNote("Case open. Reproduce it with action=reproduce and the check that should fail. Do not propose a fix.")
    }

    private fun reproduce(case: Case, fields: Map<String, String>): SkillNote {
        if (case.symptom.isBlank()) return SkillNote("No case is open. action=open first.", error = true)
        if (case.cause.isNotBlank()) return SkillNote("The check is already set. Do not swap it for an easier one.", error = true)
        val check = clean(fields["check"].orEmpty(), 300)
        val result = fields["result"].orEmpty().trim().lowercase()
        if (check.isBlank() || result !in setOf("fail", "flaky", "pass")) {
            return SkillNote("reproduce needs check and result=fail, flaky, or pass.", error = true)
        }
        case.check = check
        case.seen = result
        return when (result) {
            "pass" -> SkillNote("The check passed. There is no failure to fix. Do not change the code.")
            "flaky" -> SkillNote("It did not fail this time. Do not guess. Find the condition that makes this same check fail, then reproduce again.")
            else -> SkillNote("The check failed. Add facts with action=fact before any hypothesis.")
        }
    }

    private fun fact(case: Case, fields: Map<String, String>): SkillNote {
        if (case.seen != "fail") return SkillNote("Reproduce a failure first. A fact about a passing check is not a cause.", error = true)
        val text = clean(fields["text"].orEmpty(), 400)
        val source = clean(fields["source"].orEmpty(), 160)
        if (text.isBlank() || source.isBlank()) return SkillNote("fact needs text and source (the tool and the path).", error = true)
        case.facts += source to text
        return SkillNote("Fact ${case.facts.size} recorded. When the lines you need are here, action=hypothesis.")
    }

    private fun hypothesis(case: Case, fields: Map<String, String>): SkillNote {
        if (case.facts.isEmpty()) return SkillNote("Add a fact from a tool result before a hypothesis.", error = true)
        val text = clean(fields["text"].orEmpty(), 400)
        if (text.isBlank()) return SkillNote("hypothesis needs text: one cause that could be wrong.", error = true)
        val id = case.hypotheses.size + 1
        case.hypotheses += Hyp(id, "open", text)
        return SkillNote("Hypothesis $id. Test only that idea: action=test id=$id result=supports or rules-out. Do not edit yet.")
    }

    private fun test(case: Case, fields: Map<String, String>): SkillNote {
        val id = fields["id"].orEmpty().trim().toIntOrNull()
            ?: return SkillNote("test needs id of an open hypothesis.", error = true)
        val hyp = case.hypotheses.firstOrNull { it.id == id }
            ?: return SkillNote("test needs id of an open hypothesis.", error = true)
        if (hyp.state != "open") return SkillNote("Hypothesis $id is already ${hyp.state}. Write a new hypothesis.", error = true)
        val check = clean(fields["check"].orEmpty(), 300)
        val result = fields["result"].orEmpty().trim().lowercase()
        if (check.isBlank() || result !in setOf("supports", "rules-out")) {
            return SkillNote("test needs check and result=supports or rules-out.", error = true)
        }
        if (case.tests.any { it.id == id && it.check == check }) {
            return SkillNote("That check was already run for hypothesis $id. Use a different check.", error = true)
        }
        case.tests += Trial(id, result, check)
        hyp.state = if (result == "supports") "supported" else "ruled-out"
        return if (result == "supports") {
            SkillNote("Hypothesis $id is supported. Name the cause with action=cause. Do not edit yet.")
        } else {
            SkillNote("Hypothesis $id is ruled out. Do not patch it. Write a new hypothesis.")
        }
    }

    private fun cause(case: Case, fields: Map<String, String>): SkillNote {
        if (case.hypotheses.none { it.state == "supported" }) {
            return SkillNote("No test supports a hypothesis. Do not name a cause from a guess.", error = true)
        }
        val text = clean(fields["text"].orEmpty(), 400)
        if (text.isBlank()) return SkillNote("cause needs one sentence: where the bad value starts.", error = true)
        case.cause = text
        return SkillNote("Cause recorded. Make the smallest edit of that cause, then action=fix. No extra cleanup.")
    }

    private fun fix(case: Case, fields: Map<String, String>): SkillNote {
        if (case.cause.isBlank()) return SkillNote("Name the cause before a fix. A patch without a cause hides the bug.", error = true)
        if (case.failedVerifies >= 3) {
            return SkillNote("Three fixes failed the same check. Stop. Question the design with the user. Do not try a fourth fix.", error = true)
        }
        val text = clean(fields["text"].orEmpty(), 400)
        if (text.isBlank()) return SkillNote("fix needs text: the one edit.", error = true)
        case.fixes += text
        case.pass = false
        return SkillNote("Fix recorded. Run the original check again and action=verify result=pass or fail. Check: ${case.check}")
    }

    private fun verify(case: Case, fields: Map<String, String>): SkillNote {
        if (case.fixes.isEmpty()) return SkillNote("Record the fix first. A passing check you did not change is not a fix.", error = true)
        val check = clean(fields["check"].orEmpty(), 300)
        val result = fields["result"].orEmpty().trim().lowercase()
        if (result !in setOf("pass", "fail")) return SkillNote("verify needs result=pass or fail.", error = true)
        if (check != case.check) {
            return SkillNote("That is a different check. Run the one that failed: ${case.check}", error = true)
        }
        return if (result == "fail") {
            case.failedVerifies += 1
            case.pass = false
            if (case.failedVerifies >= 3) {
                SkillNote("The same check still fails. Three fixes have failed it. Stop and question the design. Do not try a fourth fix.")
            } else {
                SkillNote("The same check still fails. This fix did not hold. Return to the facts. Failed fixes: ${case.failedVerifies}.")
            }
        } else {
            case.pass = true
            SkillNote("pass: yes. The check that failed now passes. Say what you did not run, then use_skill review and switch systematic-debugging off.")
        }
    }

    private fun clean(value: String, max: Int): String {
        return value.replace(" | ", " / ").replace('\n', ' ').replace('\r', ' ').trim().take(max)
    }

    private fun render(case: Case): String = buildString {
        if (case.symptom.isBlank()) {
            append("No case is open.")
            return@buildString
        }
        append("pass: ").append(if (case.pass) "yes" else "no").append('\n')
        append("symptom: ").append(case.symptom).append('\n')
        append("steps: ").append(case.steps).append('\n')
        append("check: ").append(case.check.ifBlank { "(not run)" }).append('\n')
        append("seen: ").append(case.seen.ifBlank { "(not run)" }).append('\n')
        append("facts: ").append(case.facts.size).append('\n')
        case.facts.forEachIndexed { index, fact -> append(index + 1).append(". [").append(fact.first).append("] ").append(fact.second).append('\n') }
        append("hypotheses: ").append(case.hypotheses.size).append('\n')
        case.hypotheses.forEach { append(it.id).append(". ").append(it.state).append(" ").append(it.text).append('\n') }
        append("tests: ").append(case.tests.size).append('\n')
        case.tests.forEach { append("hypothesis ").append(it.id).append(' ').append(it.result).append(" [").append(it.check).append("]\n") }
        append("cause: ").append(case.cause.ifBlank { "(none)" }).append('\n')
        append("fixes: ").append(case.fixes.size).append('\n')
        case.fixes.forEachIndexed { index, fix -> append(index + 1).append(". ").append(fix).append('\n') }
        append("failed fixes: ").append(case.failedVerifies)
    }

    private fun read(root: File): Case {
        val file = file(root)
        if (!file.isFile) return Case()
        val case = Case()
        var section = "header"
        file.readLines().forEach { line ->
            when {
                line == "## fact" -> section = "fact"
                line == "## hypothesis" -> section = "hypothesis"
                line == "## test" -> section = "test"
                line == "## fix" -> section = "fix"
                line.isBlank() -> Unit
                section == "header" && ": " in line -> {
                    val key = line.substringBefore(": ")
                    val value = line.substringAfter(": ")
                    when (key) {
                        "symptom" -> case.symptom = value
                        "steps" -> case.steps = value
                        "check" -> case.check = value
                        "seen" -> case.seen = value
                        "cause" -> case.cause = value
                        "failed" -> case.failedVerifies = value.toIntOrNull() ?: 0
                        "pass" -> case.pass = value == "yes"
                    }
                }
                section == "fact" && " | " in line -> case.facts += line.substringBefore(" | ") to line.substringAfter(" | ")
                section == "hypothesis" -> {
                    val parts = line.split(" | ", limit = 3)
                    if (parts.size == 3) case.hypotheses += Hyp(parts[0].toIntOrNull() ?: return@forEach, parts[1], parts[2])
                }
                section == "test" -> {
                    val parts = line.split(" | ", limit = 3)
                    if (parts.size == 3) case.tests += Trial(parts[0].toIntOrNull() ?: return@forEach, parts[1], parts[2])
                }
                section == "fix" -> case.fixes += line
            }
        }
        return case
    }

    private fun write(root: File, case: Case) {
        val file = file(root)
        file.parentFile?.mkdirs()
        file.writeText(buildString {
            append("pass: ").append(if (case.pass) "yes" else "no").append('\n')
            append("symptom: ").append(case.symptom).append('\n')
            append("steps: ").append(case.steps).append('\n')
            append("check: ").append(case.check).append('\n')
            append("seen: ").append(case.seen).append('\n')
            append("cause: ").append(case.cause).append('\n')
            append("failed: ").append(case.failedVerifies).append('\n')
            if (case.facts.isNotEmpty()) {
                append("## fact\n")
                case.facts.forEach { append(it.first).append(" | ").append(it.second).append('\n') }
            }
            if (case.hypotheses.isNotEmpty()) {
                append("## hypothesis\n")
                case.hypotheses.forEach { append(it.id).append(" | ").append(it.state).append(" | ").append(it.text).append('\n') }
            }
            if (case.tests.isNotEmpty()) {
                append("## test\n")
                case.tests.forEach { append(it.id).append(" | ").append(it.result).append(" | ").append(it.check).append('\n') }
            }
            if (case.fixes.isNotEmpty()) {
                append("## fix\n")
                case.fixes.forEach { append(it).append('\n') }
            }
        }, Charsets.UTF_8)
    }

    private class Case {
        var symptom: String = ""
        var steps: String = ""
        var check: String = ""
        var seen: String = ""
        var cause: String = ""
        var failedVerifies: Int = 0
        var pass: Boolean = false
        val facts = mutableListOf<Pair<String, String>>()
        val hypotheses = mutableListOf<Hyp>()
        val tests = mutableListOf<Trial>()
        val fixes = mutableListOf<String>()

        fun reset(symptom: String, steps: String) {
            this.symptom = symptom
            this.steps = steps
            check = ""
            seen = ""
            cause = ""
            failedVerifies = 0
            pass = false
            facts.clear()
            hypotheses.clear()
            tests.clear()
            fixes.clear()
        }
    }

    private class Hyp(val id: Int, var state: String, val text: String)
    private class Trial(val id: Int, val result: String, val check: String)
}
