package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.agent.buildSystemPrompt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HarnessEfficiencyTest {
    @Test
    fun numbersTheFirstLineAndEveryTenth() {
        val lines = (1..25).map { "line $it" }
        val text = formatReadLines(lines, startLine = 1)
        val numbered = text.lines().filter { it.isNotEmpty() && it[0].isDigit() }
        assertEquals(listOf("1", "10", "20"), numbered.map { it.substringBefore('\t') })
        assertTrue(text.lines().any { it.startsWith("\tline 2") })
    }

    @Test
    fun editFocusHidesInstallersUntilLoaded() {
        val edit = ToolGroups.visibleNames(ToolGroups.forFocus("edit"))
        assertTrue("read_file" in edit)
        assertFalse("install_jdk" in edit)
        val session = ToolSession(emptySet())
        assertTrue(session.load("build").contains("install_jdk"))
        assertTrue(session.holdUntilLoaded("fetch_system_layout")!!.contains("device"))
        assertEquals(null, session.holdUntilLoaded("fetch_system_layout"))
    }

    @Test
    fun standingPromptDoesNotCarryTheProjectPath() {
        val prompt = buildSystemPrompt()
        assertFalse(prompt.contains("/storage"))
        assertFalse(prompt.contains("project rooted"))
        assertTrue(prompt.contains("load_tools"))
    }
}
