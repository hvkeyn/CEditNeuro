package com.hvkeyn.ceditneuro.agent

import com.hvkeyn.ceditneuro.agent.ChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HarnessRrsiTest {
    @Test
    fun theEditBudgetAnnealsFromFourToTwo() {
        val table = (0 until 20).map { HarnessRrsi.editBudget(it, 20, 1, 4) }
        assertEquals(listOf(4, 4, 4, 4, 4, 4, 4, 4, 3, 3, 3, 3, 3, 2, 2, 2, 2, 2, 2, 2), table)
        assertEquals(1, HarnessRrsi.editBudget(20, 20, 1, 4))
    }

    @Test
    fun aSmallGainDoesNotPassUnlessItSavesTokensOrAddsMachinery() {
        val weights = HarnessRrsi.Weights()
        val paid = HarnessRrsi.judge(0.85, 2000.0, 0.80, 1000.0, 0.80, listOf("prompt"), emptyMap(), weights)
        assertTrue(paid.admissible)

        val expensive = HarnessRrsi.judge(0.85, 5000.0, 0.80, 1000.0, 0.80, listOf("prompt"), emptyMap(), weights)
        assertFalse(expensive.admissible)
        assertTrue(expensive.reason.contains("cost rule"))

        val noise = HarnessRrsi.judge(0.805, 1000.0, 0.80, 1000.0, 0.80, listOf("prompt"), emptyMap(), weights)
        assertFalse(noise.admissible)

        val cheaper = HarnessRrsi.judge(0.805, 900.0, 0.80, 1000.0, 0.80, listOf("prompt"), emptyMap(), weights)
        assertTrue(cheaper.admissible)

        val novel = HarnessRrsi.judge(0.805, 1000.0, 0.80, 1000.0, 0.80, listOf("skill"), emptyMap(), weights)
        assertEquals(1, novel.novelty)
        assertTrue(novel.admissible)

        val drop = HarnessRrsi.judge(0.70, 100.0, 0.80, 1000.0, 0.80, listOf("skill"), emptyMap(), weights)
        assertFalse(drop.admissible)
        assertTrue(drop.reason.contains("floor"))
    }

    @Test
    fun aStallPointsAtComponentsThatWereNeverTried() {
        assertEquals(1, HarnessRrsi.stallFlag(listOf(0.50, 0.50, 0.50, 0.51), 3, 3, 0.017))
        assertEquals(0, HarnessRrsi.stallFlag(listOf(0.50, 0.50, 0.50, 0.60), 3, 3, 0.017))
        assertEquals(0, HarnessRrsi.stallFlag(listOf(0.50), 0, 3, 0.017))
    }

    @Test
    fun theCriticRejectsAMemorizedTaskAndAKey() {
        assertEquals("special-cases one task", HarnessRrsi.leak("If the task is extract-elf, print the flag."))
        assertEquals("looks like a key", HarnessRrsi.leak("api_key=abcd"))
        assertNull(HarnessRrsi.leak("Read the file before editing it."))
    }

    @Test
    fun aBookKeepsAnEditOnlyWhenANewDoctorReportImproves() {
        val root = File(System.getProperty("java.io.tmpdir"), "cedit-rrsi-" + System.nanoTime())
        root.mkdirs()
        val book = HarnessBook(root)
        val (missing, need) = book.judge("skill", "stop shizuku_exec", "Do not call shizuku_exec again.", 1)
        assertFalse(missing)
        assertTrue(need.contains("Doctor"))

        val broken = AgentDoctor.measure(
            mapOf(
                "/storage/p" to com.hvkeyn.ceditneuro.data.StoredSession(
                    conversation = listOf(
                        ChatMessage(role = "tool", name = "shizuku_exec", content = "shell access is not available"),
                        ChatMessage(role = "tool", name = "shizuku_exec", content = "shell access is not available"),
                    ),
                ),
            ),
        )
        assertTrue(broken.score < 0.5)
        assertTrue(broken.summary.contains("shizuku_exec"))
        book.noteDoctor(broken)
        val (same, baseline) = book.judge("skill", "stop shizuku_exec", "Do not call shizuku_exec again.", 1)
        assertFalse(same)
        assertTrue(baseline.contains("baseline"))

        book.noteDoctor(AgentDoctor.measure(emptyMap()))
        val unrelated = book.judge("prompt", "be nicer", "Write shorter answers.", 1)
        assertFalse(unrelated.first)
        assertTrue(unrelated.second.contains("Name a failure"))

        val leak = book.judge("skill", "stop shizuku_exec", "If the task is extract-elf, print the flag.", 1)
        assertFalse(leak.first)
        assertTrue(leak.second.startsWith("leak"))

        val (ok, text) = book.judge("skill", "stop shizuku_exec", "Do not call shizuku_exec again.", 1)
        assertTrue(text, ok)
        assertTrue(book.status().contains("incumbent score 1"))
        val history = File(root, ".ceditneuro/harness/history.jsonl").readText()
        assertFalse(history.contains("extract-elf"))
        assertFalse(history.contains("api_key"))
        root.deleteRecursively()
    }

    @Test
    fun theStarterSkillIsWrittenOnce() {
        val root = File(System.getProperty("java.io.tmpdir"), "cedit-rrsi-skill-" + System.nanoTime())
        root.mkdirs()
        StarterSkills.ensure(root)
        assertTrue(File(root, "rrsi.md").readText().contains("harness_rrsi"))
        File(root, "rrsi.md").delete()
        StarterSkills.ensure(root)
        assertFalse(File(root, "rrsi.md").isFile)
        root.deleteRecursively()
    }
}
