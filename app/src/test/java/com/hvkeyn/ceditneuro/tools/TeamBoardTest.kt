package com.hvkeyn.ceditneuro.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class TeamBoardTest {
    @Test
    fun normalizesKnownRolesAndCapsTheTeam() {
        assertEquals(
            listOf("researcher", "reviewer", "tester"),
            TeamBoard.normalize("researcher, reviewer, tester, unknown, researcher"),
        )
        assertTrue(TeamBoard.normalize("nobody").isEmpty())
    }

    @Test
    fun writesBriefReportsAndBoard() {
        val dir = Files.createTempDirectory("team").toFile()
        try {
            val roles = TeamBoard.normalize("reviewer,researcher")
            TeamBoard.begin(dir, "Ship the parser", "Is the error path covered?", roles)
            TeamBoard.record(dir, TeamBoard.RoleReport("reviewer", "Missing a bad-input test.", false))
            assertTrue(TeamBoard.briefFile(dir).readText().contains("Main editor"))
            assertTrue(TeamBoard.reportFile(dir, "reviewer").isFile)
            assertTrue(TeamBoard.summary(dir).contains("Missing a bad-input test."))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun teamToolRunsHelpersAndLeavesTheBrief() = runBlocking {
        val dir = Files.createTempDirectory("team-tool").toFile()
        try {
            val updates = mutableListOf<TeamUpdate>()
            val tool = TeamTool(dir, { request ->
                val head = if (request.role == "researcher") "FINDING" else "VERDICT"
                "$head: checked. EVIDENCE: spinner.html L17 has the cycle. This report is deliberately long enough to satisfy the evidence gate."
            }, { true }, { updates += it })
            val args = Json.parseToJsonElement(
                """{"action":"help","task":"Ship the parser","question":"Is it safe?","roles":"reviewer,researcher"}""",
            ).jsonObject
            val result = tool.execute(args)
            assertTrue(result.content.contains("VERDICT: checked"))
            assertTrue(result.content.contains("FINDING: checked"))
            assertTrue(TeamBoard.briefFile(dir).isFile)
            assertTrue(updates.any { it.phase == "reports ready" })
            assertTrue(updates.flatMap { it.agents }.any { it.status == "done" })
            val second = tool.execute(args)
            assertTrue(second.content.contains("one team"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun dualRunsTwoIndependentSolvers() = runBlocking {
        val dir = Files.createTempDirectory("team-dual").toFile()
        try {
            val tool = TeamTool(dir, { request ->
                val n = if (request.role == "solver-a") "323" else "323"
                "ANSWER: $n\nEVIDENCE: 17*19 arithmetic\nCHECK: 323 / 17 = 19 and 19*17 = 323, so the two independent paths agree."
            })
            val args = Json.parseToJsonElement(
                """{"action":"dual","task":"Compute 17*19","question":"What is 17*19? Show a check."}""",
            ).jsonObject
            val result = tool.execute(args)
            assertTrue(result.content.contains("solver-a"))
            assertTrue(result.content.contains("solver-b"))
            assertTrue(TeamBoard.reportFile(dir, "solver-a").isFile)
            assertTrue(TeamBoard.reportFile(dir, "solver-b").isFile)
        } finally {
            dir.deleteRecursively()
        }
    }
}
