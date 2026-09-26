package com.hvkeyn.ceditneuro.ui

import com.hvkeyn.ceditneuro.agent.buildSetupPrompt
import com.hvkeyn.ceditneuro.ui.editor.previewKind
import com.hvkeyn.ceditneuro.ui.editor.previewPage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RunAndPreviewTest {
    @Test
    fun pythonAndShellFilesRunFromTheirFolder() {
        assertEquals(
            "cd '/w/a b' && python3 -u '/w/a b/x.py'",
            runCommandFor("a b/x.py", "/w/a b/x.py"),
        )
        assertEquals("cd '/w' && sh '/w/run.SH'", runCommandFor("run.SH", "/w/run.SH"))
        assertNull(runCommandFor("notes.md", "/w/notes.md"))
        assertTrue(isRunnable("tool.py"))
        assertFalse(isRunnable("README"))
    }

    @Test
    fun quotesInPathAreEscaped() {
        val command = runCommandFor("it's.py", "/w/it's.py")!!
        assertTrue(command.contains("'/w/it'\\''s.py'"))
    }

    @Test
    fun previewKindsCoverImagesSchemesAndPages() {
        assertEquals("image", previewKind("plot.PNG"))
        assertEquals("svg", previewKind("research/scheme.svg"))
        assertEquals("html", previewKind("index.htm"))
        assertNull(previewKind("main.kt"))
        val page = previewPage("svg", "<svg></svg>")
        assertTrue(page.contains("<svg></svg>"))
        assertTrue(page.contains("max-width:100%"))
    }

    @Test
    fun activeSkillsGoIntoTheSetupPrompt() {
        val prompt = buildSetupPrompt(
            projectRoot = "/p",
            toolchainBin = "/t",
            remoteSummary = "",
            workFocus = "edit",
            accessLine = "",
            storageLine = "",
            loadedGroups = emptySet(),
            activeSkills = listOf("research" to "Always cite sources.", "huge" to "x".repeat(10_000)),
        )
        assertTrue(prompt.contains("### Skill: research"))
        assertTrue(prompt.contains("Always cite sources."))
        assertFalse(prompt.contains("x".repeat(5_000)))
        val plain = buildSetupPrompt("/p", "/t", "", "edit", "", "", emptySet())
        assertFalse(plain.contains("### Skill:"))
    }
}
