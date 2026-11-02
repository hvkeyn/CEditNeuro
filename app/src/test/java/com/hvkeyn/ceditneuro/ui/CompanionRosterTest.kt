package com.hvkeyn.ceditneuro.ui

import com.hvkeyn.ceditneuro.data.AgentSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionRosterTest {
    @Test
    fun autoIsStableForOneClient() {
        val first = CompanionRoster.resolve(CompanionRoster.AUTO, "/storage/emulated/0/Vdo", AgentSettings.WORK_EDIT)
        val again = CompanionRoster.resolve(CompanionRoster.AUTO, "/storage/emulated/0/Vdo", AgentSettings.WORK_EDIT)
        assertEquals(first.id, again.id)
    }

    @Test
    fun autoCanDifferBetweenClients() {
        val ids = (0 until 12).map { index ->
            CompanionRoster.resolve(CompanionRoster.AUTO, "/storage/emulated/0/client-$index", AgentSettings.WORK_EDIT).id
        }.toSet()
        assertTrue(ids.size > 1)
    }

    @Test
    fun manualChoiceWinsAndFocusLimitsSuggestions() {
        assertEquals("momo", CompanionRoster.resolve("momo", "/x", AgentSettings.WORK_BUILD).id)
        val study = CompanionRoster.candidates(AgentSettings.WORK_STUDY).map { it.id }.toSet()
        assertEquals(setOf("momo", "yuki"), study)
        val build = CompanionRoster.candidates(AgentSettings.WORK_BUILD).map { it.id }.toSet()
        assertEquals(setOf("kira", "ren", "momo"), build)
        assertNotEquals(
            CompanionRoster.resolve("auto", "/x", AgentSettings.WORK_STUDY).id,
            "sora",
        )
    }
}
