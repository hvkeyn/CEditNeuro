package com.hvkeyn.ceditneuro.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ResearchRunTest {
    @Test
    fun aLightRunPassesOnlyWhenCitesUseTheQuotes() {
        val root = Files.createTempDirectory("research-run").toFile()
        assertTrue(ResearchRun.apply(root, "source", source(1)).error)
        assertTrue(ResearchRun.apply(root, "open", mapOf("question" to "How is glucose measured?", "tier" to "dissertation")).error)
        assertFalse(ResearchRun.apply(root, "open", mapOf("question" to "How is glucose measured?", "tier" to "light")).error)
        assertTrue(ResearchRun.apply(root, "plan", mapOf("text" to "only one question")).error)
        assertFalse(ResearchRun.apply(root, "plan", mapOf("text" to "Which test is used? | What number does it print?")).error)
        assertFalse(ResearchRun.apply(root, "source", source(1)).error)
        val reprint = ResearchRun.apply(root, "source", source(1))
        assertTrue(reprint.text.contains("does not count"))
        assertFalse(ResearchRun.apply(root, "source", source(2)).error)
        assertFalse(ResearchRun.apply(root, "source", source(3)).error)
        val draft = "The trial measured fasting glucose in adults. The second sheet measured fasting glucose too. " +
            "It was not the same test."
        assertTrue(ResearchRun.apply(root, "draft", mapOf("text" to "too short")).error)
        assertFalse(ResearchRun.apply(root, "draft", mapOf("text" to draft)).error)
        assertTrue(ResearchRun.apply(root, "cite", mapOf("id" to "1", "sentence" to "The trial measured fasting glucose in adults.", "supports" to "yes")).error)
        assertFalse(ResearchRun.apply(root, "critic", mapOf("name" to "cite", "text" to "The sentences match the quotes.")).error)
        assertTrue(
            ResearchRun.apply(
                root,
                "cite",
                mapOf("id" to "1", "sentence" to "The trial measured fasting glucose in adults.", "supports" to "yes"),
            ).text.contains("Cite saved"),
        )
        val unbound = ResearchRun.apply(
            root,
            "cite",
            mapOf("id" to "3", "sentence" to "It was not the same test.", "supports" to "yes"),
        )
        assertTrue(unbound.error)
        assertFalse(
            ResearchRun.apply(
                root,
                "cite",
                mapOf("id" to "3", "sentence" to "The second sheet measured fasting glucose too.", "supports" to "yes"),
            ).error,
        )
        val passed = ResearchRun.apply(root, "pass", emptyMap())
        assertFalse(passed.error)
        assertTrue(passed.text.contains("next: done"))
        assertTrue(ResearchRun.apply(root, "status", emptyMap()).text.contains("reprints: 1"))
        assertTrue(java.io.File(root, "research/sources.md").readText().contains("fasting glucose"))
        assertTrue(ResearchRun.apply(root, "source", source(4)).error)
    }

    @Test
    fun fullTierStopsAtTheCapAndRejectsAKey() {
        val root = Files.createTempDirectory("research-full").toFile()
        ResearchRun.apply(root, "open", mapOf("question" to "Does the claim hold?", "tier" to "full"))
        ResearchRun.apply(root, "plan", mapOf("text" to "What was measured? | Who disagrees?"))
        assertTrue(ResearchRun.apply(root, "draft", mapOf("text" to "x".repeat(100))).error)
        repeat(8) { index ->
            assertFalse(ResearchRun.apply(root, "source", source(index + 1)).error)
        }
        assertTrue(ResearchRun.apply(root, "draft", mapOf("text" to "x".repeat(100))).error)
        val key = "sk-" + "abcdef1234567890"
        assertTrue(ResearchRun.apply(root, "source", source(9) + mapOf("quote" to key)).error)
    }

    @Test
    fun lightStopsAfterEightIndependentSources() {
        val root = Files.createTempDirectory("research-cap").toFile()
        ResearchRun.apply(root, "open", mapOf("question" to "List the measures.", "tier" to "light"))
        ResearchRun.apply(root, "plan", mapOf("text" to "Which measures? | Where are they printed?"))
        repeat(8) { index -> assertFalse(ResearchRun.apply(root, "source", source(index + 1)).error) }
        assertTrue(ResearchRun.apply(root, "source", source(9)).text.contains("cap"))
    }

    @Test
    fun anOldResearchSkillGainsTheDeepPath() {
        val dir = Files.createTempDirectory("research-skill").toFile()
        val file = java.io.File(dir, "research.md")
        file.writeText(
            "# Research\nStay on this one agent. Do not start another agent and do not fetch a pile of papers.\n" +
                "1. Restate the question in the user's language. research_log kind=question.\n",
        )
        ResearchSkill.ensure(dir)
        val text = file.readText()
        assertTrue(text.contains("research_run"))
        assertFalse(text.contains("fetch a pile of papers"))
        assertTrue(text.contains("does not count"))
    }

    private fun source(n: Int): Map<String, String> = mapOf(
        "title" to "Glucose sheet $n results",
        "locator" to "https://example.com/glucose-$n",
        "quote" to when (n) {
            1 -> "The trial measured fasting glucose in adults"
            2 -> "The second sheet measured fasting glucose too"
            else -> "Page $n measured fasting glucose in the same adults"
        },
        "claim" to "The sheet prints a glucose result",
    )
}
