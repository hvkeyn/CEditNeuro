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
    fun androidSkillsStayOnThisPhone() {
        val app = StarterSkills.skills.getValue("android-app")
        assertTrue(app.contains("https://github.com/android/skills"))
        assertTrue(app.contains("BUILD SUCCESSFUL"))
        assertTrue(app.contains("use_skill android-debug"))
        assertTrue(app.contains("use_skill android-apk"))
        val debug = StarterSkills.skills.getValue("android-debug")
        assertTrue(debug.contains("fetch_system_layout"))
        assertTrue(debug.contains("screen was not checked"))
        assertTrue(app.contains("install_android_sdk"))
        val compose = StarterSkills.skills.getValue("android-compose")
        assertTrue(compose.contains("enableEdgeToEdge"))
        assertTrue(compose.contains("Navigation 3"))
        assertTrue(compose.contains("navigation-3/SKILL.md"))
        val ui = StarterSkills.skills.getValue("android-ui")
        assertTrue(ui.contains("frontend-design"))
        assertTrue(ui.contains("48dp"))
        assertFalse(ui.contains("decompile"))
    }

    @Test
    fun theDreamSkillReadsTheWayJungDid() {
        val dreams = StarterSkills.skills.getValue("dreams")
        listOf("Carl Jung", "subject level", "object level", "Amplify", "Shadow", "compensation", "emotional background", "examples", "not a diagnosis", "crisis line")
            .forEach { assertTrue("dreams lacks $it", dreams.contains(it, ignoreCase = true)) }
        assertTrue(buildSystemPrompt().contains("use_skill dreams"))
        val dir = Files.createTempDirectory("skills-dreams").toFile()
        java.io.File(dir, ".starter-written").writeText("health\n")
        StarterSkills.ensure(dir)
        assertEquals(dreams, java.io.File(dir, "dreams.md").readText())
    }

    @Test
    fun oldPhoneSkillsGainTheirFixes() {
        val dir = Files.createTempDirectory("skills-wiring").toFile()
        java.io.File(dir, "frontend-design.md").writeText("# Design\n2. call design_system with that name.\n")
        java.io.File(dir, "net-map.md").writeText("# Map\nDo not record another device.\n")
        java.io.File(dir, "debug.md").writeText("# Find one fault\nThe fix is the smallest edit that makes the check pass.\n")
        java.io.File(dir, "android-app.md").writeText("# Android app structure\n11. install_android_sdk only when the SDK is missing.\n")
        java.io.File(dir, "android-debug.md").writeText("# Android debug\n2. shizuku_exec runs logcat.\n")
        java.io.File(dir, "systematic-debugging.md").writeText("# Find the cause\n1. debug_case action=open.\n")
        java.io.File(dir, "android-apk.md").writeText("# Android APK check\n1. sha256sum APP.apk.\n")
        ResearchSkill.ensure(dir)
        assertTrue(java.io.File(dir, "frontend-design.md").readText().contains("group has no tools"))
        assertEquals(StarterSkills.skills.getValue("net-map"), java.io.File(dir, "net-map.md").readText())
        assertTrue(java.io.File(dir, "debug.md").readText().contains("systematic-debugging"))
        assertTrue(java.io.File(dir, "systematic-debugging.md").readText().contains("debug_case"))
        assertTrue(java.io.File(dir, "android-app.md").readText().contains("BUILD SUCCESSFUL"))
        assertTrue(java.io.File(dir, "android-debug.md").readText().contains("fetch_system_layout"))
        assertTrue(java.io.File(dir, "systematic-debugging.md").readText().contains("android-debug"))
        assertTrue(java.io.File(dir, "android-apk.md").readText().contains("Reproducing a behavior is allowed"))
    }
}
