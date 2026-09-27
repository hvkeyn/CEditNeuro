package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.agent.SkillAudit
import com.hvkeyn.ceditneuro.agent.SkillCatalog
import com.hvkeyn.ceditneuro.agent.SkillLibrary
import com.hvkeyn.ceditneuro.net.AgentNet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.IOException

/** Searches the public skills directory. It does not download or save a skill. */
class FindSkillsTool(
    private val net: AgentNet,
    private val networkAllowed: () -> Boolean,
) : Tool {
    override val name = "find_skills"
    override val description =
        "Search the public skills directory with plain words for a task this phone cannot do yet. " +
            "Returns names, install counts, and the source. Does not install anything. " +
            "Call review_skill on one result before save_skill."
    override val parameters = objectSchema(
        properties = mapOf(
            "query" to stringProp("The task in plain words, for example make a changelog."),
        ),
        required = listOf("query"),
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        if (!networkAllowed()) return@withContext ToolResult.error("Agent network is off in Settings.")
        val query = args.stringArg("query")?.trim().orEmpty()
        val url = runCatching { SkillCatalog.searchUrl(query) }.getOrElse {
            return@withContext ToolResult.error(it.message ?: "query is required.")
        }
        runCatching {
            val response = net.exchange(url, "GET", mapOf("Accept" to "application/json"), null, MAX_BYTES)
            if (response.code !in 200..299) throw IOException("HTTP ${response.code}. Do not repeat this search.")
            SkillCatalog.formatHits(query, SkillCatalog.parseSearch(response.body.toString(Charsets.UTF_8)))
        }.fold(
            onSuccess = { ToolResult.ok(it) },
            onFailure = { ToolResult.error("${it.message ?: "Search failed."} Do not repeat the same query.") },
        )
    }

    private companion object {
        const val MAX_BYTES = 512L * 1024
    }
}

/**
 * Reads one remote or local skill and reports dangerous prompts, backdoors, malware, and keys.
 * A clean file comes back only as a short adapted procedure.
 */
class ReviewSkillTool(
    private val skills: SkillLibrary,
    private val net: AgentNet,
    private val networkAllowed: () -> Boolean,
) : Tool {
    override val name = "review_skill"
    override val description =
        "Check a skill for a dangerous prompt, a backdoor, malware, a broken file, and a key. " +
            "Pass source and skill from find_skills, or name and scope of a skill already on this phone. " +
            "A blocked file is not returned. save_skill may store only the adapted text this tool prints."
    override val parameters = objectSchema(
        properties = mapOf(
            "source" to stringProp("owner/repo from find_skills. Leave empty for a skill on this phone."),
            "skill" to stringProp("Skill id from find_skills."),
            "name" to stringProp("Name of a skill already saved on this phone."),
            "scope" to stringProp("project or app, when name is set."),
        ),
        required = emptyList(),
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val source = args.stringArg("source")?.trim().orEmpty()
        val skill = args.stringArg("skill")?.trim().orEmpty()
        val name = args.stringArg("name")?.trim().orEmpty()
        val scope = args.stringArg("scope")?.trim().orEmpty()
        when {
            source.isNotEmpty() || skill.isNotEmpty() -> reviewRemote(source, skill)
            name.isNotEmpty() -> reviewLocal(name, scope)
            else -> ToolResult.error("Pass source and skill from find_skills, or name and scope of a saved skill.")
        }
    }

    private fun reviewLocal(name: String, scope: String): ToolResult {
        val note = skills.read(name, scope)
        if (note.error) return ToolResult.error(note.text)
        val audit = SkillAudit.check(note.text, name, "the saved skill")
        if (audit.blocked) return ToolResult.error(SkillAudit.refusal(name, audit))
        if (audit.findings.isEmpty()) {
            return ToolResult.ok("No embedded error, backdoor, malware, or dangerous prompt in $name. Leave the skill as it is.")
        }
        return ToolResult.ok(report(name, audit))
    }

    private fun reviewRemote(source: String, skill: String): ToolResult {
        if (!networkAllowed()) return ToolResult.error("Agent network is off in Settings.")
        if (skill.isEmpty() || source.isEmpty()) {
            return ToolResult.error("source and skill are both required for a remote skill.")
        }
        val urls = SkillCatalog.rawUrls(source, skill)
        if (urls.isEmpty()) return ToolResult.error("source must look like owner/repo and skill is a single name.")
        val text = runCatching { download(urls, skill) }.getOrElse {
            return ToolResult.error("${it.message ?: "Download failed."} Do not invent its steps.")
        }
        val audit = SkillAudit.check(text, skill, source)
        if (audit.blocked) return ToolResult.error(SkillAudit.refusal(skill, audit))
        return ToolResult.ok(report("$source skill $skill", audit))
    }

    private fun report(label: String, audit: SkillAudit.Result): String = buildString {
        append("Checked ").append(label).append(". ")
        if (audit.findings.isEmpty()) {
            append("No embedded error, backdoor, malware, or dangerous prompt.\n\n")
        } else {
            audit.findings.forEach { append(it.kind).append(": ").append(it.detail).append('\n') }
            append('\n')
        }
        append("save_skill may store only this adapted text. Do not paste the remote file.\n\n")
        append(audit.adapted)
    }

    private fun download(urls: List<String>, skillId: String): String {
        var last = "not found"
        for (url in urls) {
            val response = net.exchange(url, "GET", mapOf("Accept" to "text/plain, text/markdown"), null, MAX_BYTES)
            if (response.code == 404) continue
            if (response.code !in 200..299) {
                last = "HTTP ${response.code}"
                continue
            }
            val text = response.body.toString(Charsets.UTF_8)
            if (text.length < 40) continue
            if (text.length > MAX_CHARS) throw IOException("The remote skill is too large. It was not saved.")
            val root = url.endsWith("/HEAD/SKILL.md")
            if (root && !text.contains(skillId, ignoreCase = true)) continue
            return text
        }
        throw IOException("Could not download that skill ($last).")
    }

    private companion object {
        const val MAX_BYTES = 96L * 1024
        const val MAX_CHARS = 48_000
    }
}
