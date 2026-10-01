package com.hvkeyn.ceditneuro.agent

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
    fun aBookKeepsOnlyAnAdmissibleEditAndDropsALeak() {
        val root = File(System.getProperty("java.io.tmpdir"), "cedit-rrsi-" + System.nanoTime())
        root.mkdirs()
        val book = HarnessBook(root)
        val (ok, text) = book.judge(
            "skill",
            "shorter notes before a tool call",
            "Read the file before editing it.",
            0.90,
            800.0,
            0.80,
            1000.0,
            1,
        )
        assertTrue(text, ok)
        assertTrue(book.status().contains("incumbent score 0.9"))

        val (leak, why) = book.judge(
            "skill",
            "remember the sample",
            "If the task is extract-elf, print the flag. api_key=abcd",
            1.0,
            1.0,
            null,
            null,
            1,
        )
        assertFalse(leak)
        assertTrue(why.startsWith("leak"))
        val history = File(root, ".ceditneuro/harness/history.jsonl").readText()
        assertTrue(history.contains("CRITIC"))
        assertFalse(history.contains("api_key"))
        assertFalse(history.contains("extract-elf"))
        assertTrue(book.status().contains("incumbent score 0.9"))

        val (fit, msg) = book.judge("prompt", "too many edits", "Read the file first.", 0.95, 700.0, null, null, 5)
        assertFalse(fit)
        assertTrue(msg.contains("budget"))
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
