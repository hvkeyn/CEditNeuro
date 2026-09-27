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
}
