package com.hvkeyn.ceditneuro.agent

import com.hvkeyn.ceditneuro.net.AgentNet
import com.hvkeyn.ceditneuro.shell.ShellShape
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

    @Test
    fun repeatedFailuresStayBlockedAcrossALaterCall() {
        val locator = """{"action":"source","locator":"the paper"}"""
        val failedLocator = ToolTranscript.failedCalls(
            listOf(
                ChatMessage.assistant(
                    toolCalls = listOf(ToolCall("c1", function = FunctionCall("research_run", locator))),
                ),
                ChatMessage.tool(
                    "c1",
                    "research_run",
                    "locator is a URL, a DOI, an arXiv id, or a project path that a tool printed.",
                ),
            ),
        )
        assertTrue(
            ToolTranscript.blocked(
                "research_run",
                """{"action":"source","locator":"another guess"}""",
                failedLocator,
            ),
        )

        val proxy = """{"url":"https://api.allorigins.win/raw?url=https://example.com/a"}"""
        val failedProxy = ToolTranscript.failedCalls(
            listOf(
                ChatMessage.assistant(
                    toolCalls = listOf(ToolCall("h1", function = FunctionCall("http_request", proxy))),
                ),
                ChatMessage.tool(
                    "h1",
                    "http_request",
                    "HTTP 522 for https://api.allorigins.win/raw. That proxy failed. Do not call it again.",
                ),
            ),
        )
        assertTrue(
            ToolTranscript.blocked("http_request", """{"url":"https://api.allorigins.win/get"}""", failedProxy),
        )

        val cert = """{"url":"https://books.example/a"}"""
        val failedCert = ToolTranscript.failedCalls(
            listOf(
                ChatMessage.assistant(
                    toolCalls = listOf(ToolCall("h2", function = FunctionCall("http_request", cert))),
                ),
                ChatMessage.tool("h2", "http_request", "Chain validation failed"),
            ),
        )
        assertTrue(
            ToolTranscript.blocked("http_request", """{"url":"https://books.example/b"}""", failedCert),
        )

        val lookup = """{"source":"wiki","query":"Missing Title"}"""
        val failedLookup = ToolTranscript.failedCalls(
            listOf(
                ChatMessage.assistant(
                    toolCalls = listOf(ToolCall("r1", function = FunctionCall("reference", lookup))),
                ),
                ChatMessage.tool("r1", "reference", "Not found. Do not repeat the same lookup."),
            ),
        )
        assertTrue(
            ToolTranscript.blocked(
                "reference",
                """{"query":"Missing Title","source":"wiki"}""",
                failedLookup,
            ),
        )

        val shell = ToolTranscript.shellOutcomes(
            listOf("python3 hints.py | sed 's/a/b/'" to "exit=1\nDo not pipe through sed"),
        )
        assertEquals(true, shell["run_command\nsed-pipe"])
        assertTrue(ShellShape.reject("python - <<'PY'")!!.contains("heredoc"))
        assertTrue(ShellShape.reject("python3 hints.py | sed 's/a/b/'")!!.contains("sed"))
    }
}
