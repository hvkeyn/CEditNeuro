package com.hvkeyn.ceditneuro.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionStateTest {
    @Test
    fun offlineBeatsIdle() {
        val status = companionStatus(false, false, false, null, null, null)
        assertEquals(CompanionMood.OFFLINE, status.mood)
    }

    @Test
    fun activeTeamShowsHelpersAndLoad() {
        val team = TeamUi(
            task = "audit",
            agents = listOf(
                TeamAgentUi("solver-a", "working"),
                TeamAgentUi("solver-b", "working"),
            ),
            active = true,
            phase = "working",
            startedAt = 1L,
        )
        val status = companionStatus(true, true, false, team, AgentActivity("Thinking", "", "", 1), null)
        assertEquals(CompanionMood.TEAM, status.mood)
        assertEquals(2, status.load)
        assertTrue(status.message.contains("2 of 2"))
    }

    @Test
    fun thinkingAndToolWorkAreDifferent() {
        val thinking = companionStatus(
            true, true, false, null,
            AgentActivity("Thinking", "", "", 1), null,
        )
        assertEquals(CompanionMood.THINKING, thinking.mood)
        val working = companionStatus(
            true, true, false, null,
            AgentActivity("Reading a file", "", "", 1), null,
        )
        assertEquals(CompanionMood.WORKING, working.mood)
    }

    @Test
    fun idleSleepsAndErrorsWorry() {
        assertEquals(
            CompanionMood.SLEEPING,
            companionStatus(true, false, true, null, null, ChatEntry(1, ChatRole.Assistant, "done")).mood,
        )
        assertEquals(
            CompanionMood.WORRIED,
            companionStatus(true, false, false, null, null, ChatEntry(2, ChatRole.Error, "boom")).mood,
        )
        assertEquals(
            CompanionMood.HAPPY,
            companionStatus(true, false, false, null, null, ChatEntry(3, ChatRole.Assistant, "done")).mood,
        )
    }

    @Test
    fun idleGesturesAreBounded() {
        val actions = (0L..10_000L step 1000L).map { idleAction(it) }.toSet()
        assertTrue(actions.isNotEmpty())
    }
}
