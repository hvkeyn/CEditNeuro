package com.hvkeyn.ceditneuro.agent

import com.hvkeyn.ceditneuro.agent.deepseek.web.DeepSeekPow
import com.hvkeyn.ceditneuro.agent.deepseek.web.WebPrompt
import com.hvkeyn.ceditneuro.agent.deepseek.web.WebStream
import com.hvkeyn.ceditneuro.data.ModelCatalog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebPhoneTest {

    @Test
    fun theHashIsDeepSeeksSha3WithoutTheFirstRound() {
        assertEquals("e594808bc5b7151ac160c6d39a02e0a8e261ed588578403099e3561dc40c26b3", DeepSeekPow.hash(ByteArray(0)))
        assertEquals(
            "3141d73fe21247aaac411432ca8dcded9a27884a784e958bae250459812eacc0",
            DeepSeekPow.hash("a1b2c3d4_1790823061_98765".toByteArray()),
        )
    }

    @Test
    fun theProofOfWorkFindsTheNonceAndBuildsTheHeader() {
        assertEquals(7L, DeepSeekPow.solve("2aa17662b059b7faa428ffdecac642b86982c6d83fd2ff9fb630d031baf57f99", "XyZsalt_1700000000_", 1000))
        assertNull(DeepSeekPow.solve("00".repeat(32), "XyZsalt_1700000000_", 50))
        val challenge = buildJsonObject {
            put("algorithm", "DeepSeekHashV1")
            put("challenge", "2aa17662b059b7faa428ffdecac642b86982c6d83fd2ff9fb630d031baf57f99")
            put("salt", "XyZsalt")
            put("expire_at", 1700000000L)
            put("difficulty", 1000)
            put("signature", "sig")
            put("target_path", "/api/v0/chat/completion")
        }
        val decoded = String(java.util.Base64.getDecoder().decode(DeepSeekPow.header(challenge)))
        val obj = Json.parseToJsonElement(decoded) as JsonObject
        assertEquals("7", obj["answer"]!!.jsonPrimitive.content)
        assertEquals("sig", obj["signature"]!!.jsonPrimitive.content)
    }

    @Test
    fun toolCallsAreReadFromTheReplyText() {
        val names = setOf("read_file", "edit_file")
        val (text, calls) = WebPrompt.parse(
            "Reading it.\n```tool_calls\n[{\"name\": \"read_file\", \"arguments\": {\"path\": \"a.kt\"}}]\n```",
            names,
        )
        assertEquals("Reading it.", text)
        assertEquals("read_file", calls!!.single().function.name)
        assertTrue(calls.single().function.arguments.contains("a.kt"))

        val xml = WebPrompt.parse("<tool_call><function name=\"edit_file\"><parameter name=\"path\">b.kt</parameter></function></tool_call>", names)
        assertEquals("edit_file", xml.second!!.single().function.name)

        val plain = WebPrompt.parse("Just an answer with no tools.", names)
        assertNull(plain.second)
        assertNull(WebPrompt.parse("```tool_calls\n[{\"name\": \"rm_rf\", \"arguments\": {}}]\n```", names).second)
    }

    @Test
    fun aReplyCutInsideAToolCallIsContinued() {
        val names = setOf("read_file")
        assertTrue(WebPrompt.truncated("```tool_calls\n[{\"name\": \"read_file\", \"arguments\": {\"path\": ", names))
        assertFalse(WebPrompt.truncated("Done, the file is fixed.", names))
    }

    @Test
    fun thePromptCarriesTheHistoryAndEndsOnTheAssistant() {
        val prompt = WebPrompt.prompt(
            listOf(ChatMessage.system("Be brief."), ChatMessage.user("Hi"), ChatMessage(role = "tool", content = "42", name = "calc")),
            emptyList(),
        )
        assertTrue(prompt.startsWith("System: Be brief."))
        assertTrue(prompt.contains("User: Hi"))
        assertTrue(prompt.contains("Tool result (calc): 42"))
        assertTrue(prompt.contains(WebPrompt.AFTER_TOOLS))
        assertTrue(prompt.endsWith("Assistant:"))
        assertEquals("Hello", WebPrompt.prompt(listOf(ChatMessage.user("Hello")), emptyList()))
    }

    @Test
    fun theStreamKeepsTheAnswerApartFromTheThinking() {
        val stream = WebStream()
        val parts = listOf(
            """data: {"v":{"response":{"message_id":2,"fragments":[{"type":"THINK","content":"hmm"}]}}}""",
            """data: {"p":"response/fragments","o":"APPEND","v":[{"type":"RESPONSE","content":"Hel"}]}""",
            """data: {"p":"response/fragments/-1/content","o":"APPEND","v":"lo"}""",
            """data: {"v":" world"}""",
            """data: {"p":"response/status","v":"FINISHED"}""",
            "data: [DONE]",
        ).mapNotNull { stream.feed(it) }
        assertEquals("Hello world", parts.joinToString("") { it.text })
        assertEquals("hmm", parts.joinToString("") { it.reasoning })
        assertEquals(2L, stream.messageId)
        assertNull(stream.error)

        val failed = WebStream()
        failed.feed("""data: {"code":40003,"msg":"Authorization Failed"}""")
        assertNotNull(failed.error)

        val busy = WebStream()
        busy.feed("""data: {"p":"response/status","v":"FAILED"}""")
        assertNotNull(busy.error)
    }

    @Test
    fun thePhoneProviderNeedsNoKeyOrComputer() {
        val phone = ModelCatalog.webPhone()
        assertTrue(phone.ready)
        assertTrue(phone.apiUrl.startsWith("https://chat.deepseek.com"))
        assertEquals(listOf("deepseek-chat", "deepseek-expert"), phone.models.map { it.name })
        assertTrue(phone.models.all { it.maxContextTokens == 64_000 })
        assertEquals(
            "Mozilla/5.0 (Linux; Android 16; SM-S942B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36",
            com.hvkeyn.ceditneuro.agent.deepseek.web.WebLogin.browserAgent(
                "Mozilla/5.0 (Linux; Android 16; SM-S942B; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/140.0.0.0 Mobile Safari/537.36",
            ),
        )
    }
}
