package com.hvkeyn.ceditneuro.agent

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class MobileControlSkillMigrationTest {
    @Test
    fun anOldMobileControlSkillGainsAccessibilityGuidance() {
        val dir = File(System.getProperty("java.io.tmpdir"), "cedit-mobile-" + System.nanoTime())
        try {
            dir.mkdirs()
            File(dir, "mobile-control.md").writeText(
                "# Mobile control\n\n`ui_snapshot` and `ui_act` need Shizuku or root. " +
                    "If `device_status` says both are off, report the limit and stop; do not pretend the screen was checked.\n",
            )
            StarterSkills.ensure(dir)
            val text = File(dir, "mobile-control.md").readText()
            assertTrue(text.contains("user-enabled CEdiNeuro Accessibility service"))
            assertTrue(!text.contains("need Shizuku or root"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
