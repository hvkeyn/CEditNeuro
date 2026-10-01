package com.hvkeyn.ceditneuro.agent

import com.hvkeyn.ceditneuro.agent.deepseek.DeepSeekBackend
import com.hvkeyn.ceditneuro.data.AgentSettings
import com.hvkeyn.ceditneuro.data.ModelCatalog
import com.hvkeyn.ceditneuro.net.ProviderBalance
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class WebBridgeTest {

    @Test
    fun theBridgeNeedsNoKeyAndHasTheTwoWebModels() {
        val bridge = ModelCatalog.webBridge()
        assertTrue(bridge.ready)
        assertEquals("", bridge.apiKey)
        assertEquals(listOf("deepseek-chat", "deepseek-expert"), bridge.models.map { it.name })
        assertTrue(bridge.models.all { it.supportsTools && !it.supportsReasoning && !it.seesImages() })
        val settings = AgentSettings(
            providers = ModelCatalog.builtins() + bridge,
            activeProviderId = ModelCatalog.WEB_BRIDGE_ID,
            activeModel = "deepseek-expert",
        ).normalized()
        assertTrue(settings.ready)
        assertEquals(64_000, settings.model.maxContextTokens)
        assertFalse(AgentSettings().copy(activeProviderId = ModelCatalog.DEEPSEEK_ID).ready)
    }

    @Test
    fun anErrorInsideTheStreamIsReadWithItsType() {
        val frame = Json.parseToJsonElement(
            """{"error":{"message":"Not signed in","type":"login_required"}}""",
        ) as JsonObject
        assertEquals("login_required" to "Not signed in", DeepSeekBackend.streamError(frame))
        val normal = Json.parseToJsonElement("""{"choices":[{"delta":{"content":"hi"}}]}""") as JsonObject
        assertNull(DeepSeekBackend.streamError(normal))
        assertTrue(DeepSeekBackend.bridgeHint(503, "").contains("deepseek.auth"))
        assertTrue(DeepSeekBackend.bridgeHint(429, "").contains("RATE_LIMIT_PER_MINUTE"))
    }

    @Test
    fun theModelListOfTheBridgeIsRead() {
        val body = """{"object":"list","data":[{"id":"deepseek-chat","object":"model"},{"id":"deepseek-expert","object":"model"}]}"""
        assertEquals(listOf("deepseek-chat", "deepseek-expert"), ProviderBalance.bridgeModels(body))
        assertNull(ProviderBalance.bridgeModels("<html>"))
    }

    @Test
    fun aSmallRequestIsSentAsItIs() {
        val messages = listOf(ChatMessage.system("rules"), ChatMessage.user("hello"))
        assertSame(messages, ContextBudget.fit(messages, 64_000, 8_192, 10_000))
    }

    @Test
    fun oldToolResultsAreCutBeforeAnyTurnIsDropped() {
        val messages = mutableListOf(ChatMessage.system("rules"), ChatMessage.user("read the files"))
        repeat(4) { index ->
            val call = ToolCall(id = "c$index", function = FunctionCall("read_file", """{"path":"f$index"}"""))
            messages += ChatMessage.assistant(toolCalls = listOf(call))
            messages += ChatMessage.tool("c$index", "read_file", "x".repeat(20_000))
        }
        messages += ChatMessage.user("now fix it")
        val fitted = ContextBudget.fit(messages, contextTokens = 20_000, reserveTokens = 2_000, fixedChars = 0)
        assertEquals(messages.size, fitted.size)
        assertTrue(fitted.filter { it.role == "tool" }.all { it.content!!.contains("[cut") })
        assertEquals("now fix it", fitted.last().content)
        assertTrue(fitted.sumOf { ContextBudget.chars(it) } <= 18_000 * ContextBudget.CHARS_PER_TOKEN)
    }

    @Test
    fun theResultsOfTheCurrentTaskStayWholeWhileOlderOnesFit() {
        val messages = mutableListOf(ChatMessage.system("rules"), ChatMessage.user("old task"))
        repeat(3) { index ->
            val call = ToolCall(id = "o$index", function = FunctionCall("read_file", """{"path":"o$index"}"""))
            messages += ChatMessage.assistant(toolCalls = listOf(call))
            messages += ChatMessage.tool("o$index", "read_file", "o".repeat(20_000))
        }
        messages += ChatMessage.user("new task")
        repeat(3) { index ->
            val call = ToolCall(id = "n$index", function = FunctionCall("read_file", """{"path":"n$index"}"""))
            messages += ChatMessage.assistant(toolCalls = listOf(call))
            messages += ChatMessage.tool("n$index", "read_file", "n".repeat(10_000))
        }
        val fitted = ContextBudget.fit(messages, contextTokens = 20_000, reserveTokens = 2_000, fixedChars = 0)
        val fresh = fitted.filter { it.role == "tool" && it.toolCallId!!.startsWith("n") }
        assertEquals(3, fresh.size)
        assertTrue(fresh.all { it.content!!.length == 10_000 })
        assertTrue(fitted.filter { it.toolCallId?.startsWith("o") == true }.all { it.content!!.contains("[cut") })
    }

    @Test
    fun aLongChatKeepsTheRulesAndTheNewestTurn() {
        val messages = mutableListOf(ChatMessage.system("rules"))
        repeat(200) { index ->
            messages += ChatMessage.user("question $index " + "q".repeat(2_000))
            messages += ChatMessage.assistant("answer $index " + "a".repeat(2_000))
        }
        messages += ChatMessage.user("the last question")
        val fitted = ContextBudget.fit(messages, contextTokens = 64_000, reserveTokens = 8_192, fixedChars = 30_000)
        assertEquals("rules", fitted[0].content)
        assertEquals(ContextBudget.DROPPED_NOTE, fitted[1].content)
        assertEquals("the last question", fitted.last().content)
        assertTrue(fitted.size < messages.size)
        assertTrue(fitted.sumOf { ContextBudget.chars(it).toLong() } <= (64_000L - 8_192) * 3 - 30_000)
    }

    @Test
    fun oneHugeFreshResultIsShortenedToFit() {
        val call = ToolCall(id = "c1", function = FunctionCall("read_file", """{"path":"big"}"""))
        val messages = listOf(
            ChatMessage.system("rules"),
            ChatMessage.user("read big"),
            ChatMessage.assistant(toolCalls = listOf(call)),
            ChatMessage.tool("c1", "read_file", "y".repeat(400_000)),
        )
        val fitted = ContextBudget.fit(messages, contextTokens = 64_000, reserveTokens = 8_192, fixedChars = 0)
        val tool = fitted.last { it.role == "tool" }
        assertEquals("c1", tool.toolCallId)
        assertTrue(tool.content!!.length < 200_000)
        assertEquals("assistant", fitted[fitted.indexOf(tool) - 1].role)
    }
}
