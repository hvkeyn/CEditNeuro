package com.hvkeyn.ceditneuro.agent

import com.hvkeyn.ceditneuro.data.StoredSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentDoctorFalsePositiveTest {
    private fun measure(vararg messages: ChatMessage) =
        AgentDoctor.measure(mapOf("/storage/p" to StoredSession(conversation = messages.toList())))

    @Test
    fun successfulSearchAndEditTextIsNotAFailure() {
        val measure = measure(
            ChatMessage(
                role = "tool",
                name = "web_search",
                content = "DuckDuckGo HTML, 5 results. A result is a page to open, not a file to save.",
            ),
            ChatMessage(
                role = "tool",
                name = "edit_file",
                content = "Applied 1 replacement(s) in research/log.csv.\nTimeout values are in the next column.",
            ),
            ChatMessage(role = "tool", name = "use_skill", content = "Switched on hyperframes (app)."),
        )
        assertEquals(0, measure.repeated)
        assertEquals(0, measure.once)
        assertEquals(1.0, measure.score, 0.0)
    }

    @Test
    fun aRealMissingFileStillCounts() {
        val measure = measure(
            ChatMessage(role = "tool", name = "read_file", content = "Not a file: /storage/p/missing.txt"),
        )
        assertTrue(measure.summary.contains("missing file"))
    }
}
