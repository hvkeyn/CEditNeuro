package com.hvkeyn.ceditneuro.agent

import java.io.File

/**
 * One investigation folder in the open project.
 * The log, the report, and a figure stay on disk, so the next step does not resend them.
 */
object ResearchNotebook {
    const val MAX_LOG = 16_000
    const val MAX_REPORT = 24_000
    const val MAX_FIGURE = 24_000
    val kinds = setOf("question", "hypothesis", "evidence", "check", "build")

    fun directory(root: File): File = File(root, "research")

    fun log(root: File, kind: String, text: String): SkillNote {
        val cleanKind = kind.trim().lowercase()
        if (cleanKind !in kinds) return SkillNote("kind is question, hypothesis, evidence, check, or build.", error = true)
        val clean = text.trim()
        if (clean.isEmpty()) return SkillNote("text is required.", error = true)
        SecretText.reject(clean)?.let { return SkillNote(it, error = true) }
        val file = File(directory(root), "log.md")
        val prior = if (file.isFile) file.readText(Charsets.UTF_8).trim() else ""
        val entry = "## $cleanKind\n$clean"
        val next = if (prior.isBlank()) entry else "$prior\n\n$entry"
        val kept = if (next.length <= MAX_LOG) next else next.takeLast(MAX_LOG)
        write(file, kept)
        val count = kept.lineSequence().count { it.startsWith("## ") }
        return SkillNote("Logged $cleanKind. The research log has $count entries. Do not write this entry again.")
    }

    fun report(root: File, title: String, body: String): SkillNote {
        val heading = title.trim()
        val clean = body.trim()
        if (heading.isEmpty() || clean.isEmpty()) return SkillNote("title and body are required.", error = true)
        SecretText.reject(heading + "\n" + clean)?.let { return SkillNote(it, error = true) }
        if (clean.length > MAX_REPORT) return SkillNote("The report is too long. Shorten it below $MAX_REPORT characters.", error = true)
        val markdown = "# $heading\n\n$clean\n"
        val file = File(directory(root), "report.md")
        write(file, markdown)
        return SkillNote(markdown)
    }

    fun figure(root: File, name: String, svg: String): SkillNote {
        val safe = name.trim().lowercase().removeSuffix(".svg")
            .replace(Regex("[^a-z0-9-]+"), "-")
            .trim('-')
            .ifBlank { "figure" }
            .take(40)
        val clean = svg.trim()
        if (!clean.startsWith("<svg") || !clean.contains("</svg>")) {
            return SkillNote("svg must be one <svg> document.", error = true)
        }
        if (clean.contains("<script", ignoreCase = true) || clean.contains("javascript:", ignoreCase = true)) {
            return SkillNote("The figure cannot contain a script.", error = true)
        }
        if (clean.length > MAX_FIGURE) return SkillNote("The figure is too large.", error = true)
        val file = File(directory(root), "$safe.svg")
        write(file, clean)
        return SkillNote("Saved ${file.name}, ${clean.length} characters.")
    }

    private fun write(file: File, text: String) {
        file.parentFile?.mkdirs()
        file.writeText(text, Charsets.UTF_8)
    }
}
