package com.hvkeyn.ceditneuro.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UnbrowseSkillTest {
    @Test
    fun skillCoversAuthorizationVerificationAndSecrets() {
        val text = StarterSkills.skills.getValue("unbrowse")
        assertTrue(text.startsWith("# Unbrowse"))
        assertTrue(text.contains("https://unbrowse.ai"))
        assertTrue(text.contains("npx unbrowse whoami"))
        assertTrue(text.contains("exit 3"))
        assertTrue(text.contains("verified"))
        assertTrue(text.contains("input_required"))
        assertTrue(text.contains("outcome_unknown"))
        assertTrue(text.contains("Never ask the user to paste an API key"))
        assertTrue(text.contains("CAPTCHA"))
        assertTrue(text.contains("run_command"))
        assertFalse(text.contains("ub_live_"))
        assertFalse(text.contains("Bearer ey"))
    }

    @Test
    fun starterCatalogStillFitsTheContract() {
        assertTrue(StarterSkills.skills.size >= 41)
        StarterSkills.skills.forEach { (name, text) ->
            assertTrue(name.matches(Regex("[a-z][a-z0-9-]{1,39}")))
            assertTrue(text.length <= 12_000)
        }
    }
}
