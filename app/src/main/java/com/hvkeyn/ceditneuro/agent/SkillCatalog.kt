package com.hvkeyn.ceditneuro.agent

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URLEncoder

/** Search hits from the public skills directory. The phone does not install them with npx. */
object SkillCatalog {
    data class Hit(
        val name: String,
        val skillId: String,
        val source: String,
        val installs: Int,
        val page: String,
    )

    private val sourcePattern = Regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$")
    private val idPattern = Regex("^[A-Za-z0-9_.-]+$")
    private val json = Json { ignoreUnknownKeys = true }

    fun searchUrl(query: String): String {
        val clean = query.trim()
        require(clean.length in 2..80) { "Describe the task in 2 to 80 characters." }
        require(!clean.contains("://")) { "Describe the task in plain words." }
        return "https://skills.sh/api/search?q=${URLEncoder.encode(clean, "UTF-8")}&limit=8"
    }

    fun parseSearch(body: String): List<Hit> {
        val root = json.parseToJsonElement(body).jsonObject
        val skills = root["skills"]?.jsonArray ?: return emptyList()
        return skills.mapNotNull { element ->
            val row = element.jsonObject
            val source = row.str("source")
            val skillId = row.str("skillId").ifEmpty { row.str("name") }
            val name = row.str("name").ifEmpty { skillId }
            val id = row.str("id")
            if (!sourcePattern.matches(source) || !idPattern.matches(skillId)) return@mapNotNull null
            if (id.isNotEmpty() && (id.contains("..") || !id.matches(Regex("^[A-Za-z0-9_./-]+$")))) return@mapNotNull null
            val installs = row["installs"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
            val pageId = id.ifEmpty { "$source/$skillId" }
            Hit(name, skillId, source, installs, "https://skills.sh/$pageId")
        }.sortedByDescending { it.installs }.take(8)
    }

    /** Candidate raw files. Only GitHub raw URLs built from a checked owner/repo and skill id. */
    fun rawUrls(source: String, skillId: String): List<String> {
        if (!sourcePattern.matches(source) || !idPattern.matches(skillId)) return emptyList()
        val base = "https://raw.githubusercontent.com/$source/HEAD"
        return listOf(
            "$base/skills/$skillId/SKILL.md",
            "$base/$skillId/SKILL.md",
            "$base/.claude/skills/$skillId/SKILL.md",
            "$base/.agents/skills/$skillId/SKILL.md",
            "$base/SKILL.md",
        )
    }

    fun formatHits(query: String, hits: List<Hit>): String {
        if (hits.isEmpty()) return "No skill matched those words. Describe the task in other plain words."
        return buildString {
            append("Matches for \"").append(query.trim()).append("\". Call review_skill before saving anything.\n")
            hits.forEach { hit ->
                append("- ").append(hit.name)
                append(" (").append(hit.installs).append(" installs) ")
                append(hit.source).append(" skill=").append(hit.skillId).append('\n')
                append("  ").append(hit.page).append('\n')
            }
        }.trim()
    }

    private fun kotlinx.serialization.json.JsonObject.str(key: String): String =
        (this[key] as? JsonPrimitive)?.content?.trim().orEmpty()
}
