package com.hvkeyn.ceditneuro.agent

import com.hvkeyn.ceditneuro.tools.ToolGroups
import com.hvkeyn.ceditneuro.tools.ToolSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SkillWiringTest {
    private val known = ToolGroups.core.toSet() + ToolGroups.groups.values.flatten()

    /** Words in snake case that are arguments or file names, not tools. */
    private val notTools = setOf("delay_seconds")

    @Test
    fun everyToolASkillNamesIsInAGroup() {
        val texts = StarterSkills.skills + ("prompt" to buildSystemPrompt())
        texts.forEach { (name, text) ->
            Regex("""\b[a-z]+(?:_[a-z]+)+\b""").findAll(text).map { it.value }.toSet()
                .filter { it !in notTools }
                .forEach { tool -> assertTrue("$name names $tool", tool in known) }
            Regex("""group=([a-z]+)""").findAll(text).forEach { match ->
                val group = match.groupValues[1]
                assertTrue("$name loads $group", group in ToolGroups.groups)
            }
        }
    }

    @Test
    fun everyStarterSkillFitsTheActiveCap() {
        StarterSkills.skills.forEach { (name, text) ->
            assertTrue("$name is ${text.length} chars", text.length <= ACTIVE_SKILL_CHARS)
        }
    }

    @Test
    fun aGroupWithoutBuiltToolsOffersNone() {
        val session = ToolSession(emptySet())
        session.present = setOf("list_dir")
        val text = session.load("design")
        assertTrue(text.contains("no tools"))
        assertFalse(text.contains("design_system"))
    }

    @Test
    fun aGroupListsOnlyBuiltTools() {
        val session = ToolSession(emptySet())
        session.present = setOf("capture_dump", "read_dump")
        val text = session.load("study")
        assertTrue(text.startsWith("Loaded study: capture_dump, read_dump."))
    }

    @Test
    fun oldPhoneSkillsGainTheirFixes() {
        val dir = Files.createTempDirectory("skills-wiring").toFile()
        java.io.File(dir, "frontend-design.md").writeText("# Design\n2. call design_system with that name.\n")
        java.io.File(dir, "net-map.md").writeText("# Map\nDo not record another device.\n")
        ResearchSkill.ensure(dir)
        assertTrue(java.io.File(dir, "frontend-design.md").readText().contains("group has no tools"))
        assertEquals(StarterSkills.skills.getValue("net-map"), java.io.File(dir, "net-map.md").readText())
    }
}
