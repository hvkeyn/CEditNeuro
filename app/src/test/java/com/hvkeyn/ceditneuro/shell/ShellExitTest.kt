package com.hvkeyn.ceditneuro.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellExitTest {
    @Test
    fun grepWithNoMatchesIsAResult() {
        val command = "ls -la /storage/emulated/0/123; echo ---; logcat -d -t 500 2>/dev/null | grep -ci gorilla"
        val adjusted = ShellExit.adjust(command, 1)
        assertEquals(0, adjusted.exitCode)
        assertTrue(adjusted.note.contains("No matches"))
        assertTrue(adjusted.note.contains("Do not run logcat"))
    }

    @Test
    fun aRejectedLoginIsNotRetried() {
        val output = """exit=1
login(otp) -> 401 b'{"status":401,"error":{"type":"Unauthorized","title":"Incorrect user data"}}'"""
        val adjusted = ShellExit.adjust("python login.py", 1, output)
        assertEquals(0, adjusted.exitCode)
        assertTrue(adjusted.note.contains("Do not repeat this login"))
    }

    @Test
    fun aRealFailureStaysAFailure() {
        val adjusted = ShellExit.adjust("ls /missing", 1)
        assertEquals(1, adjusted.exitCode)
        assertEquals("", adjusted.note)
    }
}
