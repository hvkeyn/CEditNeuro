package com.hvkeyn.ceditneuro.agent

import com.hvkeyn.ceditneuro.tools.ConceptLoom
import com.hvkeyn.ceditneuro.tools.ToolGroups
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class ConceptLoomTest {
    @Test
    fun theAnswerStaysHiddenUntilTheAttempt() {
        val loom = ConceptLoom()
        val framed = loom.frame(
            prompt = "Which one follows?",
            rationale = "SECRET because the first point already secured it",
            format = "choice",
            choices = listOf(
                ConceptLoom.Choice("keep", "It follows from the secured point"),
                ConceptLoom.Choice("drop", "It needs a fact nobody has"),
            ),
            expected = listOf("keep"),
            criteria = emptyList(),
            multiple = false,
            mix = false,
        )
        assertEquals(listOf("keep", "drop"), framed.choices.map { it.key })
        assertFalse(framed.prompt.contains("SECRET"))
        val wrong = loom.assess(framed.token, listOf("drop"), "", "", 90)
        assertEquals("needs-repair", wrong.outcome)
        assertEquals("overconfident", wrong.calibration)
        assertTrue(wrong.rationale.contains("SECRET"))
        assertEquals(listOf("keep"), wrong.expected)
    }

    @Test
    fun aTokenIsSpentOnceAndAGapIsNotAFailure() {
        val loom = ConceptLoom()
        val framed = loom.frame(
            prompt = "Say it back",
            rationale = "The link is the definition",
            format = "free-recall",
            choices = emptyList(),
            expected = emptyList(),
            criteria = listOf("Names the earlier point"),
            multiple = false,
            mix = false,
        )
        val gap = loom.assess(framed.token, emptyList(), "__gap__", "", null)
        assertEquals("knowledge-gap", gap.outcome)
        val again = runCatching { loom.assess(framed.token, emptyList(), "again", "accurate", null) }
        assertTrue(again.isFailure)
    }

    @Test
    fun choicesCanBeShuffled() {
        val loom = ConceptLoom()
        val framed = loom.frame(
            prompt = "Order",
            rationale = "Because",
            format = "choice",
            choices = listOf(ConceptLoom.Choice("a", "A"), ConceptLoom.Choice("b", "B"), ConceptLoom.Choice("c", "C")),
            expected = listOf("a"),
            criteria = emptyList(),
            multiple = false,
            mix = true,
            pick = { 0 },
        )
        assertEquals(listOf("b", "c", "a"), framed.choices.map { it.key })
    }

    @Test
    fun aLessonResumesAndAReviewComesDue() {
        val root = Files.createTempDirectory("loom").toFile()
        val loom = ConceptLoom()
        loom.save(root, lesson("docker", "build", "Explain a port"))
        val (loaded, _) = loom.load(root, "docker")
        assertEquals("Explain a port", loaded?.get("nextStep")?.toString()?.trim('"'))
        val framed = loom.frame(
            prompt = "What does the port do?",
            rationale = "It publishes the container port",
            format = "choice",
            choices = listOf(ConceptLoom.Choice("pub", "Publishes it"), ConceptLoom.Choice("no", "Hides it")),
            expected = listOf("pub"),
            criteria = emptyList(),
            multiple = false,
            mix = false,
        )
        val grade = loom.assess(framed.token, listOf("pub"), "", "", 80)
        loom.record(root, "docker", "ports", grade, "ports", "Try a conflict")
        val due = loom.due(root, java.time.Instant.now().plus(java.time.Duration.ofDays(2)))
        assertTrue(due.any { it.contains("ports") })
        val note = loom.addNote(root, "learn/notes.md", "insight", "A port publishes a container port.", "Ports")
        assertTrue(note.readText().contains("[!insight] Ports"))
        assertTrue(note.readText().contains("A port publishes a container port."))
        val escaped = runCatching { loom.addNote(root, "../outside.md", "guide", "no", "") }
        assertTrue(escaped.isFailure)
        root.deleteRecursively()
    }

    @Test
    fun theSkillHidesTheAnswer() {
        val skill = StarterSkills.skills.getValue("learn")
        assertTrue(skill.contains("Zproger/ConceptLoom (MIT)"))
        assertTrue(skill.contains("before the learner answers"))
        assertTrue(skill.contains("action=frame"))
        assertTrue(skill.contains("action=assess"))
        assertTrue(skill.contains("action=due"))
        assertFalse(SkillAudit.check(skill, "learn").blocked)
        assertTrue(buildSystemPrompt().contains("use_skill learn"))
        assertTrue("learn" in ToolGroups.groups.getValue("learn"))
    }

    private fun lesson(id: String, phase: String, next: String) = buildJsonObject {
        put("sessionId", id)
        put("topic", "Containers")
        put("goal", "Diagnose a failed start")
        put("phase", phase)
        put("mode", "guided")
        put("route", "Image\nPort")
        put("nextStep", next)
    }
}
