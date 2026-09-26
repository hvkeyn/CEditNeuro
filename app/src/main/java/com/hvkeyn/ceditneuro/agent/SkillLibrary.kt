package com.hvkeyn.ceditneuro.agent

import java.io.File

data class SkillEntry(
    val name: String,
    val scope: String,
    val summary: String,
)

data class SkillNote(
    val text: String,
    val error: Boolean = false,
)

/**
 * Markdown procedures the agent and the user can add and extend.
 * Project skills live in the open folder. Phone skills are available in every project.
 */
class SkillLibrary(
    private val projectRoot: File?,
    private val appDir: File,
) {
    fun list(): List<SkillEntry> {
        val found = mutableListOf<SkillEntry>()
        readDir(projectDir(), "project", found)
        readDir(appDir, "app", found)
        return found.sortedWith(compareBy({ it.scope }, { it.name }))
    }

    fun catalog(limit: Int = 1_600): String {
        val lines = list().map { entry ->
            "- ${entry.name} (${entry.scope}): ${entry.summary}"
        }
        if (lines.isEmpty()) return ""
        val joined = lines.joinToString("\n")
        return if (joined.length <= limit) joined else joined.take(limit).substringBeforeLast('\n')
    }

    fun read(name: String, scope: String): SkillNote {
        val file = fileFor(name, scope) ?: return SkillNote(scopeError(scope), error = true)
        if (!file.isFile) return SkillNote("No skill named ${file.nameWithoutExtension} in $scope. Call list_skills.", error = true)
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrElse {
            return SkillNote("Could not read ${file.name}.", error = true)
        }
        return SkillNote(text.take(MAX_CHARS))
    }

    fun save(name: String, body: String, scope: String): SkillNote =
        write(name, body, scope, append = false)

    fun append(name: String, extra: String, scope: String): SkillNote =
        write(name, extra, scope, append = true)

    fun delete(name: String, scope: String): SkillNote {
        val file = fileFor(name, scope) ?: return SkillNote(scopeError(scope), error = true)
        if (!file.isFile) return SkillNote("No skill named ${file.nameWithoutExtension} in $scope.", error = true)
        if (!file.delete()) return SkillNote("Could not delete ${file.name}.", error = true)
        return SkillNote("Deleted skill ${file.nameWithoutExtension} ($scope).")
    }

    private fun write(name: String, body: String, scope: String, append: Boolean): SkillNote {
        val clean = body.trim()
        if (clean.isEmpty()) return SkillNote("The skill text is empty.", error = true)
        SecretText.reject(clean)?.let { return SkillNote(it, error = true) }
        val file = fileFor(name, scope) ?: return SkillNote(scopeError(scope), error = true)
        val next = if (append && file.isFile) {
            val prior = file.readText(Charsets.UTF_8).trim()
            if (prior.isEmpty()) clean else "$prior\n\n$clean"
        } else {
            clean
        }
        if (next.length > MAX_CHARS) {
            return SkillNote("A skill can be at most $MAX_CHARS characters. Shorten it before saving.", error = true)
        }
        val parent = file.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            return SkillNote("Could not create ${parent.absolutePath}.", error = true)
        }
        file.writeText(next, Charsets.UTF_8)
        val verb = if (append) "Extended" else "Saved"
        return SkillNote("$verb skill ${file.nameWithoutExtension} ($scope), ${next.length} characters.")
    }

    private fun fileFor(name: String, scope: String): File? {
        val safe = normalizeName(name) ?: return null
        val dir = when (scope.trim().lowercase()) {
            "project" -> projectDir() ?: return null
            "app", "phone" -> appDir
            else -> return null
        }
        return File(dir, "$safe.md")
    }

    private fun projectDir(): File? = projectRoot?.let { File(it, ".ceditneuro/skills") }

    private fun scopeError(scope: String): String = when (scope.trim().lowercase()) {
        "project" -> if (projectRoot == null) "Open a project folder first." else "Skill name must be letters, digits, and hyphens."
        "app", "phone" -> "Skill name must be letters, digits, and hyphens."
        else -> "scope is project or app."
    }

    private fun readDir(dir: File?, scope: String, into: MutableList<SkillEntry>) {
        if (dir == null || !dir.isDirectory) return
        dir.listFiles()?.filter { it.isFile && it.extension.equals("md", ignoreCase = true) }?.forEach { file ->
            if (normalizeName(file.nameWithoutExtension) != file.nameWithoutExtension) return@forEach
            val summary = file.readText(Charsets.UTF_8)
                .lineSequence()
                .map { it.trim().removePrefix("#").trim() }
                .firstOrNull { it.isNotEmpty() }
                ?.take(100)
                ?: "(empty)"
            into += SkillEntry(file.nameWithoutExtension, scope, summary)
        }
    }

    companion object {
        const val MAX_CHARS = 12_000

        fun normalizeName(raw: String): String? {
            val cleaned = raw.trim().lowercase()
                .replace(Regex("[^a-z0-9-]+"), "-")
                .trim('-')
                .take(40)
            if (cleaned.length < 2 || !cleaned[0].isLetter()) return null
            return cleaned
        }
    }
}
