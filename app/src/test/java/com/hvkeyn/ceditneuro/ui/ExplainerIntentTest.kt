package com.hvkeyn.ceditneuro.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExplainerIntentTest {
    @Test
    fun architectureAndPlanExplanationsActivate() {
        assertTrue(wantsExplainerSkill("Explain the architecture of this app"))
        assertTrue(wantsExplainerSkill("Объясни план рефакторинга"))
        assertTrue(wantsExplainerSkill("Compare these two systems"))
    }

    @Test
    fun ordinaryWorkDoesNotActivate() {
        assertFalse(wantsExplainerSkill("Fix the plan parser"))
        assertFalse(wantsExplainerSkill("Read the file and run tests"))
        assertFalse(wantsExplainerSkill("Update README"))
    }
}
