package com.hvkeyn.ceditneuro.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ReverseSkillTest {
    @Test
    fun reaIsLeanAndExplicitlyOptional() {
        val text = StarterSkills.skills.getValue("rea")
        assertTrue(text.contains("LeanREA"))
        assertTrue(text.contains("optional desktop MCP"))
        assertTrue(text.contains("38% faster"))
        assertTrue(text.contains("2–4 calls"))
        assertTrue(text.contains("**seen**"))
        assertTrue(text.contains("**unknown**"))
        assertFalse(text.contains("Node 23 and 25 are not supported"))
        assertTrue(text.length <= 12_000)
    }

    @Test
    fun oldReaSkillsAreMigrated() {
        val dir = Files.createTempDirectory("rea-migration").toFile()
        java.io.File(dir, "rea.md").writeText("# REA\nREA 6.8.0 is the reference toolkit.\n")
        java.io.File(dir, "reverse-engineering.md").writeText("# Reverse engineering a program\nParanoia is required.\n")
        ResearchSkill.ensure(dir)
        val rea = java.io.File(dir, "rea.md").readText()
        val reverse = java.io.File(dir, "reverse-engineering.md").readText()
        assertTrue(rea.contains("LeanREA"))
        assertTrue(rea.contains("optional desktop MCP"))
        assertTrue(reverse.contains("Fast path (LeanREA pattern)"))
        assertTrue(reverse.contains("rg -uu"))
        assertFalse(reverse.contains("Paranoia"))
    }

    @Test
    fun reverseEngineeringHasABatchedEvidenceFirstPath() {
        val text = StarterSkills.skills.getValue("reverse-engineering")
        assertTrue(text.contains("Fast path (LeanREA pattern)"))
        assertTrue(text.contains("rg -uu"))
        assertTrue(text.contains("**seen**"))
        assertTrue(text.contains("**unknown**"))
        assertTrue(text.contains("Budget **2–4 tool calls**"))
        assertFalse(text.contains("Paranoia"))
        assertTrue(text.length <= 12_000)
    }
}
