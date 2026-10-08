package com.hvkeyn.ceditneuro.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class CrewBoardTest {
    @Test
    fun theEarlierClaimKeepsTheTask() {
        val board = CrewBoard()
        assertTrue(board.add("Read the log").startsWith("Added t1"))
        assertEquals("Claimed t1.", board.claim("t1", "Pixel", 2_000))
        assertTrue(board.claim("t1", "Nova", 3_000).contains("held by Pixel"))
        assertEquals("Claimed t1.", board.apply("CLAIM t1 Nova ${CrewBoard.stamp(1_000, "Nova")}"))
        assertEquals(listOf("t1"), board.heldBy("Nova"))
        assertTrue(board.heldBy("Pixel").isEmpty())
    }

    @Test
    fun onlyTheOwnerFinishesOrReleases() {
        val board = CrewBoard()
        board.add("Fix the parser")
        board.claim("t1", "Pixel", 5)
        assertTrue(board.done("t1", "Nova").contains("held by Pixel"))
        assertEquals("Released t1.", board.release("t1", "Pixel"))
        assertTrue(board.hasOpen())
        board.claim("t1", "Nova", 9)
        assertEquals("Finished t1.", board.done("t1", "Nova"))
        assertFalse(board.hasOpen())
    }

    @Test
    fun theSameOpenTextIsNotQueuedTwice() {
        val board = CrewBoard()
        board.add("Map the ports")
        val again = board.add("Map the ports")
        assertTrue(again.contains("Already queued t1"))
        assertTrue(board.render().lines().count { it.startsWith("T ") } == 1)
    }

    @Test
    fun notesStayShortAndADuplicateIsIgnored() {
        val board = CrewBoard()
        assertEquals("Shared FACT.", board.gist("fact", "Pixel", "The port is 8791."))
        assertEquals("That note is already shared.", board.gist("FACT", "Pixel", "The port is 8791."))
        repeat(40) { board.gist("FAIL", "Nova", "Attempt $it missed.") }
        assertTrue(board.render().lines().count { it.startsWith("G ") } <= CrewBoard.GIST_CAP)
    }

    @Test
    fun aLateSnapKeepsTheEarlierClaim() {
        val left = CrewBoard()
        left.add("Draw the chart")
        left.claim("t1", "Pixel", 100)
        left.gist("FACT", "Pixel", "The chart uses the saved series.")
        val right = CrewBoard()
        right.apply("ADD t1 Draw the chart")
        right.claim("t1", "Nova", 400)
        right.merge(left)
        assertEquals(listOf("t1"), right.heldBy("Pixel"))
        assertTrue(right.render().contains("The chart uses the saved series."))
        val encoded = java.util.Base64.getEncoder().withoutPadding()
            .encodeToString(left.render().toByteArray(Charsets.UTF_8))
        val third = CrewBoard()
        assertEquals("Merged the queue.", third.apply("SNAP $encoded"))
        assertEquals(listOf("t1"), third.heldBy("Pixel"))
    }

    @Test
    fun theBoardRoundTripsThroughItsFile() {
        val dir = Files.createTempDirectory("crew").toFile()
        try {
            val file = CrewBoard.file(dir)
            val (_, note) = CrewBoard.update(file) { board ->
                board.add("Check the build")
                board.claim("t1", "Pixel", 7)
                board.gist("DONE", "Pixel", "The build passed.")
            }
            assertEquals("Shared DONE.", note)
            val loaded = CrewBoard.load(file)
            assertEquals(listOf("t1"), loaded.heldBy("Pixel"))
            assertTrue(loaded.show().contains("The build passed."))
        } finally {
            dir.deleteRecursively()
        }
    }
}
