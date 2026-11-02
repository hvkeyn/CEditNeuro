package com.hvkeyn.ceditneuro.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TeamIntentTest {
    @Test
    fun teamWordsActivateThePlaybook() {
        assertTrue(wantsTeamSkill("Use a team to audit this"))
        assertTrue(wantsTeamSkill("Проверь командой и двумя агентами"))
        assertTrue(wantsTeamSkill("Run a dual check"))
    }

    @Test
    fun ordinaryTasksDoNot() {
        assertFalse(wantsTeamSkill("Update the README"))
        assertFalse(wantsTeamSkill("Read one file"))
    }
}
