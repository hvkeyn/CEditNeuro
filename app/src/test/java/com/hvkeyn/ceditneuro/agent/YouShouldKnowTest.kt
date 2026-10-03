package com.hvkeyn.ceditneuro.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class YouShouldKnowTest {
    @Test
    fun aCleanReplySaysNothing() {
        val note = YouShouldKnow.look(
            listOf(
                line("user", "Add a button."),
                line("tool", "Wrote the file.", tool = "write_file"),
                line("assistant", "Done. The button is in the screen."),
            ),
        )
        assertNull(note)
    }

    @Test
    fun aFinishedClaimHidesAFailedTool() {
        val note = YouShouldKnow.look(
            listOf(
                line("user", "Install the build."),
                line("error", "INSTALL_FAILED", tool = "install_apk"),
                line("assistant", "Done. The build is on the phone."),
            ),
        )
        assertEquals(
            "You should know. The main agent called the work finished while a tool failed.",
            note,
        )
        assertFalse(note!!.contains("INSTALL_FAILED"))
    }

    @Test
    fun anHonestFailureIsNotRepeated() {
        val note = YouShouldKnow.look(
            listOf(
                line("user", "Install the build."),
                line("error", "INSTALL_FAILED", tool = "install_apk"),
                line("assistant", "The install failed. The phone still has the old build."),
            ),
        )
        assertNull(note)
    }

    @Test
    fun aBuriedFailureIsNamedWithoutTheOutput() {
        val secret = "project-alpha-secret-path"
        val note = YouShouldKnow.look(
            listOf(
                line("user", "Please use $secret and keep going."),
                line("error", "No such file $secret", tool = "read_file"),
                line("assistant", "I read the project and the next step is the layout."),
            ),
        )
        assertEquals(
            "You should know. The main agent got a failed tool result and the reply does not say so.",
            note,
        )
        assertFalse(note!!.contains(secret))
    }

    @Test
    fun theSameFailedToolComesBack() {
        val note = YouShouldKnow.look(
            listOf(
                line("user", "Fetch the page."),
                line("error", "timed out", tool = "http_request"),
                line("assistant", "The request failed."),
                line("user", "Try once more."),
                line("error", "timed out", tool = "http_request"),
                line("assistant", "Here is the page summary."),
            ),
        )
        assertEquals("You should know. The main agent hit the same failed tool again.", note)
    }

    @Test
    fun aLimitTheUserSetIsKeptInView() {
        val note = YouShouldKnow.look(
            listOf(
                line("user", "Поправь экран и не удаляй приложение."),
                line("tool", "Removed.", tool = "uninstall_apk"),
                line("assistant", "Done. The package is uninstalled."),
            ),
        )
        assertTrue(note!!.contains("You asked for the app to stay installed."))
        assertFalse(note.contains("Поправь экран"))
    }

    @Test
    fun anOpenEndingIsShown() {
        val note = YouShouldKnow.look(
            listOf(
                line("user", "Finish the page."),
                line("assistant", "Still open: the last section has no source."),
            ),
        )
        assertEquals("You should know. The main agent left part of the task open.", note)
    }

    @Test
    fun aStreamingReplyWaits() {
        val note = YouShouldKnow.look(
            listOf(
                line("user", "Install it."),
                line("error", "failed", tool = "install_apk"),
                line("assistant", "Done", streaming = true),
            ),
        )
        assertNull(note)
    }

    private fun line(role: String, text: String, tool: String = "", streaming: Boolean = false) =
        YouShouldKnow.Line(role, text, tool, streaming)
}
