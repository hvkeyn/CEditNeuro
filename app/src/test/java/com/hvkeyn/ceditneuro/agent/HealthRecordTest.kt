package com.hvkeyn.ceditneuro.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthRecordTest {
    @Test
    fun panelMarksOnlyThePrintedRange() {
        val root = kotlin.io.path.createTempDirectory("health").toFile()
        try {
            HealthRecord.add(root, "Мама", "hemoglobin", "108", "2024-03-01", "g/L", "120", "160", "labs.txt")
            HealthRecord.add(root, "Мама", "glucose", "5,1", "2024-03-01", "mmol/L", "3.9", "6.1", "labs.txt")
            HealthRecord.add(root, "Мама", "note", "see text", "2024-03-01", source = "labs.txt")
            val panel = HealthRecord.panel(root, "Мама")
            assertFalse(panel.error)
            assertTrue(panel.text.contains("hemoglobin 108 g/L range 120–160 below"))
            assertTrue(panel.text.contains("glucose 5,1 mmol/L range 3.9–6.1 inside"))
            assertTrue(panel.text.contains("no range on the report"))
            assertTrue(panel.text.contains("They are not a diagnosis."))
            assertFalse(panel.text.contains("cancer"))
            assertFalse(panel.text.contains("anemia"))
            assertEquals("No readings saved for Папа. Log the numbers from the file first.", HealthRecord.panel(root, "Папа").text)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun trendReportsDirectionAndIndexListsTheLatest() {
        val root = kotlin.io.path.createTempDirectory("health-trend").toFile()
        try {
            HealthRecord.add(root, "Мама", "glucose", "5.0", "2024-01-01", "mmol/L", "3.9", "6.1", "a.txt", "blood")
            HealthRecord.add(root, "Мама", "glucose", "5.4", "2024-06-01", "mmol/L", "3.9", "6.1", "b.txt", "blood")
            HealthRecord.add(root, "Мама", "glucose", "6.2", "2024-12-01", "mmol/L", "3.9", "6.1", "c.txt", "blood")
            val trend = HealthRecord.trend(root, "Мама", "glucose")
            assertFalse(trend.error)
            assertTrue(trend.text.contains("min 5."))
            assertTrue(trend.text.contains("max 6.2."))
            assertTrue(trend.text.contains("mean 5.5333."))
            assertTrue(trend.text.contains("The last number is higher than the first."))
            assertTrue(trend.text.contains("6.2 mmol/L above"))
            assertFalse(trend.text.contains("diabetes"))
            val outside = HealthRecord.trend(root, "Мама", "glucose", outsideOnly = true)
            assertTrue(outside.text.contains("1 reading"))
            assertTrue(outside.text.contains("6.2"))
            assertFalse(outside.text.contains("5.0"))
            val index = HealthRecord.index(root, "Мама", "blood")
            assertTrue(index.text.contains("glucose: 3, last 2024-12-01 6.2 above, topic blood"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun oldHealthSkillGainsTrendStepsAndADeletedFileStaysGone() {
        val dir = kotlin.io.path.createTempDirectory("health-skill").toFile()
        try {
            java.io.File(dir, "health.md").writeText("old health skill\n")
            java.io.File(dir, ".starter-written").writeText("health\n")
            StarterSkills.ensure(dir)
            val text = java.io.File(dir, "health.md").readText()
            assertTrue(text.startsWith("old health skill"))
            assertTrue(text.contains("health_trend"))
            StarterSkills.ensure(dir)
            assertEquals(text, java.io.File(dir, "health.md").readText())
            java.io.File(dir, "health.md").delete()
            StarterSkills.ensure(dir)
            assertFalse(java.io.File(dir, "health.md").exists())
        } finally {
            dir.deleteRecursively()
        }
    }
}
