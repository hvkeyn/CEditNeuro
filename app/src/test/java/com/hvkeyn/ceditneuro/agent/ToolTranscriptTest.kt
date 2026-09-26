package com.hvkeyn.ceditneuro.agent

import com.hvkeyn.ceditneuro.net.AgentNet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolTranscriptTest {
    @Test
    fun reasoningOnlyAssistantGetsContent() {
        val sealed = ToolTranscript.seal(
            listOf(ChatMessage.assistant(reasoning = "Checking the page.\nUse a direct URL.")),
        )
        assertEquals("Use a direct URL.", sealed.single().content)
        assertEquals("Checking the page.\nUse a direct URL.", sealed.single().reasoningContent)
        assertNull(sealed.single().toolCalls)
    }

    @Test
    fun blankAssistantIsDropped() {
        val sealed = ToolTranscript.seal(
            listOf(
                ChatMessage.user("go"),
                ChatMessage(role = "assistant", content = "  "),
            ),
        )
        assertEquals(listOf("user"), sealed.map { it.role })
    }

    @Test
    fun missingToolResultIsFilled() {
        val call = ToolCall("c1", function = FunctionCall("read_file", """{"path":"a"}"""))
        val sealed = ToolTranscript.seal(listOf(ChatMessage.assistant(toolCalls = listOf(call))))
        assertEquals(listOf("assistant", "tool"), sealed.map { it.role })
        assertEquals("c1", sealed[1].toolCallId)
        assertTrue(sealed[1].content!!.contains("Interrupted"))
    }

    @Test
    fun aFailedCommandStaysBlockedUntilItSucceeds() {
        val args = """{"command":"python a.py","timeout_seconds":120}"""
        val call = ToolCall("c1", function = FunctionCall("run_command", args))
        val failed = ToolTranscript.failedCalls(
            listOf(
                ChatMessage.assistant(toolCalls = listOf(call)),
                ChatMessage.tool("c1", "run_command", "exit=1\n--- stderr ---\nmissing"),
            ),
        )
        assertTrue("run_command\npython a.py" in failed)

        val longer = """{"command":"python a.py","timeout_seconds":300}"""
        assertTrue(ToolTranscript.callKeys("run_command", longer).any { it in failed })

        val later = ToolTranscript.failedCalls(
            listOf(
                ChatMessage.assistant(toolCalls = listOf(call)),
                ChatMessage.tool("c1", "run_command", "exit=0\nok"),
            ),
        )
        assertFalse("run_command\npython a.py" in later)
    }

    @Test
    fun aTimedOutUrlAndARejectedLoginAreRemembered() {
        val call = ToolCall(
            "h1",
            function = FunctionCall("http_request", """{"url":"https://example.com/a?x=1"}"""),
        )
        val failed = ToolTranscript.failedCalls(
            listOf(
                ChatMessage.assistant(toolCalls = listOf(call)),
                ChatMessage.tool("h1", "http_request", "timeout"),
            ),
        )
        assertTrue("http_request\nhttps://example.com/a" in failed)

        val shell = ToolTranscript.shellOutcomes(
            listOf(
                "python login.py" to "exit=0\nThe server rejected the user data. Do not repeat this login.",
                "python login.py" to "exit=0\nok",
                "python other.py" to "exit=2\nnope",
            ),
        )
        assertEquals(false, shell["run_command\npython login.py"])
        assertEquals(true, shell["run_command\npython other.py"])
    }

    @Test
    fun certificateAndTimeoutAreDifferentBlocks() {
        assertEquals("certificate", AgentNet.blockReason("javax.net.ssl.SSLHandshakeException: Chain validation failed"))
        assertEquals(
            "certificate",
            AgentNet.blockReason("read failed. Caused by: CertPathValidatorException: Trust anchor not found"),
        )
        assertEquals("timeout", AgentNet.blockReason("timeout"))
        assertEquals("timeout", AgentNet.blockReason("java.net.SocketTimeoutException: timeout"))
        assertNull(AgentNet.blockReason("HTTP 404 for https://example.com"))
    }
}
