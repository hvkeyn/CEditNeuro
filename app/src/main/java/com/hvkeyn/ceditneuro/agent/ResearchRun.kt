package com.hvkeyn.ceditneuro.agent

import java.io.File

/**
 * One deep study, kept in the project so a later chat continues at the printed step.
 * A reprint of the same title or locator does not count as another source.
 * A cited sentence has to share the source quote's words, or a number the quote printed.
 */
object ResearchRun {
    private val critics = setOf("cite", "independence", "gap")

    fun apply(root: File, action: String, fields: Map<String, String>): SkillNote {
        val joined = fields.values.joinToString("\n")
        SecretText.reject(joined)?.let { return SkillNote(it, error = true) }
        val step = action.trim().lowercase()
        val case = read(root)
        val note = when (step) {
            "open" -> open(case, fields)
            "plan" -> plan(case, fields)
            "source" -> source(case, fields)
            "tension" -> tension(case, fields)
            "draft" -> draft(case, fields)
            "critic" -> critic(case, fields)
            "patch" -> patch(case, fields)
            "cite" -> cite(case, fields)
            "pass" -> pass(case)
            "status" -> SkillNote(render(case))
            else -> SkillNote(
                "action is one of: open, plan, source, tension, draft, critic, patch, cite, pass, status.",
                error = true,
            )
        }
        if (!note.error && step != "status") write(root, case)
        return if (note.error) note else SkillNote(render(case) + "\n" + note.text)
    }

    private fun open(case: Case, fields: Map<String, String>): SkillNote {
        val question = clean(fields["question"].orEmpty(), 400)
        val tier = fields["tier"].orEmpty().trim().lowercase()
        if (question.isBlank()) return SkillNote("open needs the question in the user's words.", error = true)
        if (tier == "dissertation") {
            return SkillNote(
                "This phone does not run a dissertation-length study. Open tier=full. One report, then stop. A later chat continues from action=status.",
                error = true,
            )
        }
        if (tier != "light" && tier != "full") return SkillNote("tier is light or full.", error = true)
        case.reset(question, tier)
        val floor = if (tier == "light") "3 to 8" else "8 to 24"
        return SkillNote("Run open. Next is action=plan. Collect $floor independent sources, then stop.")
    }

    private fun plan(case: Case, fields: Map<String, String>): SkillNote {
        if (case.question.isBlank()) return SkillNote("No run is open. action=open first.", error = true)
        if (case.passed) return closed()
        val text = clean(fields["text"].orEmpty(), 800)
        val items = text.split("|").map { it.trim() }.filter { it.isNotEmpty() }
        if (items.size < 2) return SkillNote("plan needs at least two questions separated by |.", error = true)
        case.plan = items.joinToString(" | ")
        return SkillNote("Plan saved. Next is action=source for each independent source.")
    }

    private fun source(case: Case, fields: Map<String, String>): SkillNote {
        if (case.plan.isBlank()) return SkillNote("Write the plan first. action=plan.", error = true)
        if (case.passed) return closed()
        val title = clean(fields["title"].orEmpty(), 200)
        val locator = clean(fields["locator"].orEmpty(), 300)
        val quote = clean(fields["quote"].orEmpty(), 500)
        val claim = clean(fields["claim"].orEmpty(), 300)
        if (title.isBlank() || quote.isBlank() || claim.isBlank()) {
            return SkillNote("source needs title, quote, and claim. The quote is words a tool printed.", error = true)
        }
        if (!locatorOk(locator)) {
            return SkillNote(
                "locator is a URL, a DOI, an arXiv id, or a project path that a tool printed. " +
                    "Do not guess another locator. Call reference or http_request, then copy the locator it printed.",
                error = true,
            )
        }
        val key = titleKey(title)
        val loc = locatorKey(locator)
        val prior = case.sources.firstOrNull { source ->
            (key.length >= 8 && titleKey(source.title) == key) || locatorKey(source.locator) == loc
        }
        val independent = prior == null
        if (independent && case.sources.count { it.independent } >= cap(case.tier)) {
            return SkillNote(
                "The ${case.tier} cap is ${cap(case.tier)} independent sources. Stop fetching. Write the draft.",
                error = true,
            )
        }
        val id = case.sources.size + 1
        case.sources += Source(id, title, locator, quote, claim, independent, prior?.id ?: 0)
        return if (independent) {
            SkillNote("Source $id counts. Independent sources: ${case.sources.count { it.independent }}.")
        } else {
            SkillNote("Source $id repeats source ${prior?.id}. It does not count as independent. Do not cite it as a second witness.")
        }
    }

    private fun tension(case: Case, fields: Map<String, String>): SkillNote {
        if (case.tier != "full") return SkillNote("tension is for tier=full.", error = true)
        if (case.sources.count { it.independent } < 2) return SkillNote("Compare two sources before a tension.", error = true)
        if (case.passed) return closed()
        val text = clean(fields["text"].orEmpty(), 400)
        if (text.isBlank()) return SkillNote("tension needs text, or text=none when two sources agree.", error = true)
        case.tension = text
        return SkillNote("Tension saved. Next is action=draft when the source count is met.")
    }

    private fun draft(case: Case, fields: Map<String, String>): SkillNote {
        if (case.passed) return closed()
        val have = case.sources.count { it.independent }
        val need = floor(case.tier)
        if (have < need) return SkillNote("Need $need independent sources before the draft. Have $have.", error = true)
        if (case.tier == "full" && case.tension.isBlank()) {
            return SkillNote("Name a disagreement with action=tension, or text=none after comparing two sources.", error = true)
        }
        val text = fields["text"].orEmpty().trim().replace(Regex("(?m)^## "), "-- ")
        if (text.length < 80) return SkillNote("The draft is too short. Write the report, then patch it.", error = true)
        if (text.length > 12_000) return SkillNote("The draft is too long for one report on this phone. Shorten it.", error = true)
        case.draft = text
        case.cites.clear()
        return SkillNote("Draft saved. Do not write it over. Next is action=critic. Later edits are action=patch.")
    }

    private fun critic(case: Case, fields: Map<String, String>): SkillNote {
        if (case.draft.isBlank()) return SkillNote("Write the draft before a critic.", error = true)
        if (case.passed) return closed()
        val name = fields["name"].orEmpty().trim().lowercase()
        val text = clean(fields["text"].orEmpty(), 500)
        if (name !in critics) return SkillNote("critic name is cite, independence, or gap.", error = true)
        if (text.isBlank()) return SkillNote("critic needs text: the finding, including when nothing is wrong.", error = true)
        case.critics[name] = text
        return SkillNote("Critic $name saved. Still needed: ${missingCritics(case).ifEmpty { "none" }}.")
    }

    private fun patch(case: Case, fields: Map<String, String>): SkillNote {
        if (case.draft.isBlank()) return SkillNote("There is no draft to patch.", error = true)
        if (!case.critics.containsKey("cite")) return SkillNote("The cite critic runs before a patch.", error = true)
        if (case.passed) return closed()
        val old = fields["old"].orEmpty()
        val newText = fields["text"].orEmpty()
        if (old.isBlank() || !case.draft.contains(old)) return SkillNote("patch old must be an exact span of the draft.", error = true)
        if (case.draft.indexOf(old) != case.draft.lastIndexOf(old)) {
            return SkillNote("That span appears more than once. Quote a longer span.", error = true)
        }
        if (newText.length > old.length + 80) return SkillNote("Patch a span. Do not rewrite the draft.", error = true)
        case.draft = case.draft.replaceFirst(old, newText)
        case.cites.clear()
        return SkillNote("Span replaced. Check the cites again. Do not write a new draft.")
    }

    private fun cite(case: Case, fields: Map<String, String>): SkillNote {
        if (case.draft.isBlank()) return SkillNote("Write the draft before a cite.", error = true)
        if ("cite" !in case.critics) return SkillNote("The cite critic runs before a cite.", error = true)
        if (case.passed) return closed()
        val id = fields["id"].orEmpty().trim().toIntOrNull()
        val source = case.sources.firstOrNull { it.id == id }
            ?: return SkillNote("cite id must be a source in this run.", error = true)
        if (!source.independent) return SkillNote("Source ${source.id} is a reprint. Cite ${source.same} instead.", error = true)
        val sentence = clean(fields["sentence"].orEmpty(), 400)
        val supports = fields["supports"].orEmpty().trim().lowercase()
        if (sentence.isBlank() || (supports != "yes" && supports != "no")) {
            return SkillNote("cite needs sentence and supports=yes or no.", error = true)
        }
        if (!squash(case.draft).contains(squash(sentence))) {
            return SkillNote("That sentence is not in the draft.", error = true)
        }
        if (supports == "yes" && !binds(sentence, source.quote)) {
            return SkillNote(
                "That sentence does not use the source quote. Quote the words that support it, or set supports=no.",
                error = true,
            )
        }
        case.cites.removeAll { squash(it.sentence) == squash(sentence) }
        case.cites += Cite(source.id, sentence, supports == "yes")
        return SkillNote("Cite saved. Supported cites: ${case.cites.count { it.supports }}.")
    }

    private fun pass(case: Case): SkillNote {
        if (case.question.isBlank()) return SkillNote("No run is open.", error = true)
        val gap = blocker(case)
        if (gap != null) return SkillNote(gap, error = true)
        case.passed = true
        return SkillNote("The run passes. Do not fetch more sources. use_skill name=research scope=app on=false.")
    }

    private fun blocker(case: Case): String? {
        if (case.plan.isBlank()) return "The plan is missing. action=plan."
        val have = case.sources.count { it.independent }
        val need = floor(case.tier)
        if (have < need) return "Need $need independent sources. Have $have."
        if (case.tier == "full" && case.tension.isBlank()) return "The tension step is missing."
        if (case.draft.isBlank()) return "The draft is missing."
        val criticsLeft = missingCritics(case)
        if (criticsLeft.isNotEmpty()) return "Critics still needed: ${criticsLeft.joinToString(", ")}."
        if (case.cites.any { !it.supports }) return "A cited sentence is not supported. Patch it out, or cite a source that supports it."
        val citesNeed = if (case.tier == "full") 4 else 2
        val yes = case.cites.count { it.supports }
        if (yes < citesNeed) return "Need $citesNeed supported cites. Have $yes."
        return null
    }

    private fun missingCritics(case: Case): List<String> {
        val need = if (case.tier == "full") critics.toList() else listOf("cite")
        return need.filter { it !in case.critics }
    }

    private fun closed() = SkillNote("The run already passed. action=open starts another question.", error = true)

    private fun floor(tier: String) = if (tier == "full") 8 else 3

    private fun cap(tier: String) = if (tier == "full") 24 else 8

    internal fun acceptableLocator(value: String): Boolean = locatorOk(value)

    private fun locatorOk(value: String): Boolean {
        val v = value.trim()
        if (v.startsWith("https://") || v.startsWith("http://")) return v.length > 12
        if (v.startsWith("doi:") || v.startsWith("10.")) return v.count { it == '/' || it == '.' } >= 1 && v.length > 6
        if (v.startsWith("arxiv:")) return v.length > 8
        return v.contains('/') && !v.contains("..")
    }

    private fun titleKey(title: String): String =
        title.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length > 2 || it.all(Char::isDigit) }.take(8).joinToString(" ")

    private fun locatorKey(locator: String): String =
        locator.lowercase().removePrefix("https://").removePrefix("http://").removePrefix("www.").substringBefore('?').trimEnd('/')

    private fun binds(sentence: String, quote: String): Boolean {
        val sentenceNumbers = numbers(sentence)
        if (sentenceNumbers.any { it in numbers(quote) }) return true
        return words(sentence).intersect(words(quote)).size >= 3
    }

    private fun numbers(text: String): Set<String> =
        Regex("""\d[\d.,]*""").findAll(text).map { it.value.filter { char -> char.isDigit() } }.filter { it.length >= 2 }.toSet()

    private fun words(text: String): Set<String> =
        text.lowercase().split(Regex("[^a-z0-9]+")).filter { it.length >= 4 }.toSet()

    private fun squash(text: String): String = text.lowercase().replace(Regex("\\s+"), " ").trim()

    private fun clean(text: String, max: Int): String =
        text.replace("\n", " ").trim().take(max)

    private fun render(case: Case): String {
        if (case.question.isBlank()) return "No research run is open. action=open with question and tier."
        val have = case.sources.count { it.independent }
        val reprints = case.sources.count { !it.independent }
        val next = if (case.passed) "done" else when {
            case.plan.isBlank() -> "plan"
            have < floor(case.tier) -> "source"
            case.tier == "full" && case.tension.isBlank() -> "tension"
            case.draft.isBlank() -> "draft"
            missingCritics(case).isNotEmpty() -> "critic"
            case.cites.any { !it.supports } || case.cites.count { it.supports } < (if (case.tier == "full") 4 else 2) -> "cite"
            else -> "pass"
        }
        return buildString {
            append("question: ").append(case.question).append('\n')
            append("tier: ").append(case.tier).append('\n')
            append("independent: ").append(have).append(" of ").append(floor(case.tier)).append('\n')
            append("reprints: ").append(reprints).append('\n')
            append("plan: ").append(if (case.plan.isBlank()) "no" else "yes").append('\n')
            append("tension: ").append(if (case.tension.isBlank()) "no" else "yes").append('\n')
            append("draft: ").append(if (case.draft.isBlank()) "no" else "yes").append('\n')
            append("critics: ").append(case.critics.keys.joinToString(", ").ifBlank { "none" }).append('\n')
            append("cites: ").append(case.cites.count { it.supports }).append(" supported\n")
            append("next: ").append(next)
        }
    }

    private fun read(root: File): Case {
        val file = File(root, "research/run.md")
        if (!file.isFile) return Case()
        val text = file.readText(Charsets.UTF_8)
        val case = Case()
        val head = text.substringBefore("\n## ").lineSequence()
        head.forEach { line ->
            val value = line.substringAfter(": ", "")
            when (line.substringBefore(": ")) {
                "question" -> case.question = value
                "tier" -> case.tier = value
                "passed" -> case.passed = value == "yes"
            }
        }
        Regex("(?m)^## ").split(text).drop(1).forEach { block ->
            val kind = block.substringBefore('\n').trim()
            val body = block.substringAfter('\n', "")
            when (kind) {
                "plan" -> case.plan = body.trim()
                "tension" -> case.tension = body.trim()
                "draft" -> case.draft = body.trim()
                "source" -> parseSource(body)?.let { case.sources += it }
                "critic" -> {
                    val name = field(body, "name")
                    val note = field(body, "text")
                    if (name in critics && note.isNotBlank()) case.critics[name] = note
                }
                "cite" -> {
                    val id = field(body, "source").toIntOrNull() ?: return@forEach
                    val sentence = field(body, "sentence")
                    if (sentence.isNotBlank()) case.cites += Cite(id, sentence, field(body, "supports") == "yes")
                }
            }
        }
        return case
    }

    private fun parseSource(body: String): Source? {
        val id = field(body, "id").toIntOrNull() ?: return null
        val title = field(body, "title")
        if (title.isBlank()) return null
        return Source(
            id,
            title,
            field(body, "locator"),
            field(body, "quote"),
            field(body, "claim"),
            field(body, "independent") != "no",
            field(body, "same").toIntOrNull() ?: 0,
        )
    }

    private fun field(body: String, name: String): String =
        body.lineSequence().firstOrNull { it.startsWith("$name: ") }?.substringAfter(": ").orEmpty()

    private fun write(root: File, case: Case) {
        val file = File(root, "research/run.md")
        file.parentFile?.mkdirs()
        val text = buildString {
            append("question: ").append(case.question).append('\n')
            append("tier: ").append(case.tier).append('\n')
            append("passed: ").append(if (case.passed) "yes" else "no").append('\n')
            if (case.plan.isNotBlank()) append("\n## plan\n").append(case.plan).append('\n')
            if (case.tension.isNotBlank()) append("\n## tension\n").append(case.tension).append('\n')
            if (case.draft.isNotBlank()) append("\n## draft\n").append(case.draft).append('\n')
            case.sources.forEach { source ->
                append("\n## source\n")
                append("id: ").append(source.id).append('\n')
                append("title: ").append(source.title).append('\n')
                append("locator: ").append(source.locator).append('\n')
                append("quote: ").append(source.quote).append('\n')
                append("claim: ").append(source.claim).append('\n')
                append("independent: ").append(if (source.independent) "yes" else "no").append('\n')
                append("same: ").append(source.same).append('\n')
            }
            case.critics.forEach { (name, note) ->
                append("\n## critic\nname: ").append(name).append("\ntext: ").append(note).append('\n')
            }
            case.cites.forEach { cite ->
                append("\n## cite\nsource: ").append(cite.source).append('\n')
                append("sentence: ").append(cite.sentence).append('\n')
                append("supports: ").append(if (cite.supports) "yes" else "no").append('\n')
            }
        }
        file.writeText(text, Charsets.UTF_8)
        val index = buildString {
            append("# Sources\n")
            case.sources.forEach { source ->
                append("\n## ").append(source.id).append(". ").append(source.title).append('\n')
                append("independent: ").append(if (source.independent) "yes" else "no").append('\n')
                append("locator: ").append(source.locator).append('\n')
                append(source.quote).append('\n')
            }
        }
        File(root, "research/sources.md").writeText(index, Charsets.UTF_8)
    }

    private class Case {
        var question: String = ""
        var tier: String = ""
        var plan: String = ""
        var tension: String = ""
        var draft: String = ""
        var passed: Boolean = false
        val sources: MutableList<Source> = mutableListOf()
        val critics: MutableMap<String, String> = linkedMapOf()
        val cites: MutableList<Cite> = mutableListOf()

        fun reset(question: String, tier: String) {
            this.question = question
            this.tier = tier
            plan = ""
            tension = ""
            draft = ""
            passed = false
            sources.clear()
            critics.clear()
            cites.clear()
        }
    }

    private data class Source(
        val id: Int,
        val title: String,
        val locator: String,
        val quote: String,
        val claim: String,
        val independent: Boolean,
        val same: Int,
    )

    private data class Cite(val source: Int, val sentence: String, val supports: Boolean)
}
