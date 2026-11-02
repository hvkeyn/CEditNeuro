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
            val statuses = mutableListOf<String>()
            val tool = TeamTool(dir, { request ->
                val head = if (request.role == "researcher") "FINDING" else "VERDICT"
                "$head: checked. EVIDENCE: spinner.html L17."
            }, { statuses += it })
            val args = Json.parseToJsonElement(
                """{"action":"help","task":"Ship the parser","question":"Is it safe?","roles":"reviewer,researcher"}""",
            ).jsonObject
            val result = tool.execute(args)
            assertTrue(result.content.contains("VERDICT: checked"))
            assertTrue(result.content.contains("FINDING: checked"))
            assertTrue(TeamBoard.briefFile(dir).isFile)
            assertTrue(statuses.any { it.contains("started") })
            val second = tool.execute(args)
            assertTrue(second.content.contains("one team"))
        } finally {
            dir.deleteRecursively()
        }
    }
}
