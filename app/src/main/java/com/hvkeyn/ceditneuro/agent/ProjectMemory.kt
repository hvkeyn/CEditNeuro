package com.hvkeyn.ceditneuro.agent

import java.io.File

/** A short note about the open project, loaded into the next run. */
object ProjectMemory {
    const val MAX_CHARS = 4_000

    fun file(root: File): File = File(root, ".ceditneuro/memory.md")

    fun read(root: File): String {
        val file = file(root)
        if (!file.isFile) return ""
        return runCatching { file.readText(Charsets.UTF_8).trim().take(MAX_CHARS) }.getOrDefault("")
    }

    fun store(root: File, text: String, append: Boolean): SkillNote {
        val clean = text.trim()
        if (clean.isEmpty()) return SkillNote("text is required.", error = true)
        SecretText.reject(clean)?.let { return SkillNote(it, error = true) }
        val next = if (append) {
            val prior = read(root)
            if (prior.isBlank()) clean else "$prior\n$clean"
        } else {
            clean
        }
        val kept = if (next.length <= MAX_CHARS) next else next.takeLast(MAX_CHARS)
        val file = file(root)
        val parent = file.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return SkillNote("Could not create ${parent.absolutePath}.", error = true)
        }
        file.writeText(kept, Charsets.UTF_8)
        return SkillNote("Saved ${kept.length} characters of project memory.")
    }
}
