package com.hvkeyn.ceditneuro.agent

import com.hvkeyn.ceditneuro.agent.deepseek.ModelStall
import com.hvkeyn.ceditneuro.agent.deepseek.web.WebPrompt
import com.hvkeyn.ceditneuro.agent.qwen.web.QwenClient
import com.hvkeyn.ceditneuro.agent.qwen.web.QwenSession
import com.hvkeyn.ceditneuro.agent.qwen.web.QwenStream
import com.hvkeyn.ceditneuro.data.ModelCatalog
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class QwenPhoneTest {

    @Test
    fun thePhoneProviderNeedsNoKeyOrComputer() {
        val phone = ModelCatalog.qwenPhone()
        assertTrue(phone.ready)
        assertEquals("https://chat.qwen.ai", phone.apiUrl)
        assertEquals(listOf("qwen3.8-max", "qwen3.7-plus", "qwen3-coder-plus"), phone.models.map { it.name })
        assertTrue(phone.models.all { it.maxContextTokens == 64_000 && it.supportsTools })
    }

    @Test
    fun theChatBodyMatchesTheLivePageEnvelope() {
        val body = QwenClient.chatBody("chat-1", "qwen3.8-max", "Hello")
        assertEquals("2.1", body["version"]!!.jsonPrimitive.content)
        assertEquals("chat-1", body["chat_id"]!!.jsonPrimitive.content)
        val message = body["messages"]!!.jsonArray.single().jsonObject
        assertEquals("Hello", message["content"]!!.jsonPrimitive.content)
        assertEquals("t2t", message["chat_type"]!!.jsonPrimitive.content)
        assertEquals("phase", message["feature_config"]!!.jsonObject["output_schema"]!!.jsonPrimitive.content)
        assertTrue(body["timestamp"]!!.jsonPrimitive.content.toLong() < 10_000_000_000L)
        assertEquals("qwen3.8-max", QwenClient.chatBody("chat-1", "qwen3-coder-plus", "Hi")["model"]!!.jsonPrimitive.content)
        val session = QwenSession(token = "eyJabc", cookies = "ssxmod_itna=1", userAgent = "ua", capturedAt = 0)
        assertTrue(QwenClient.cookieHeader(session).contains("token=eyJabc"))
        assertTrue(QwenClient.cookieHeader(session).contains("ssxmod_itna=1"))
        val replaced = QwenClient.cookieHeader(session.copy(cookies = "token=stale; ssxmod_itna=1"))
        assertTrue(replaced.startsWith("token=eyJabc"))
        assertFalse(replaced.contains("token=stale"))
        assertTrue(QwenClient.looksLikeHumanCheck("<html>aliyun_waf"))
        assertFalse(QwenClient.looksLikeHumanCheck("{\"success\":true}"))
        assertTrue(QwenClient.sessionExpired("unauthorized Token has expired, please log in again."))
        assertFalse(QwenClient.sessionExpired("{\"success\":true}"))
    }

    @Test
    fun bothToolDialectsAreRead() {
        val names = setOf("read_file", "list_dir")
        val xml = WebPrompt.parse(
            "<tool_call>\n{\"name\": \"read_file\", \"arguments\": {\"path\": \"a.kt\"}}\n</tool_call>",
            names,
        )
        assertEquals("read_file", xml.second!!.single().function.name)
        assertTrue(xml.first.isBlank())

        val two = WebPrompt.parse(
            "<tool_call>\n{\"name\":\"list_dir\",\"arguments\":{}}\n</tool_call>\n" +
                "<tool_call>\n{\"name\":\"read_file\",\"arguments\":{\"path\":\"b.kt\"}}\n</tool_call>",
            names,
        )
        assertEquals(listOf("list_dir", "read_file"), two.second!!.map { it.function.name })

        val fence = WebPrompt.parse(
            "```tool_calls\n[{\"name\": \"list_dir\", \"arguments\": {}}]\n```",
            names,
        )
        assertEquals("list_dir", fence.second!!.single().function.name)
    }

    @Test
    fun theXmlPromptShowsTheToolResultAsABlock() {
        val prompt = WebPrompt.prompt(
            listOf(
                ChatMessage.user("List the folder"),
                ChatMessage(role = "tool", content = "a.kt", name = "list_dir"),
            ),
            emptyList(),
            xml = true,
        )
        assertTrue(prompt.contains("<tool_response name=\"list_dir\">"))
        assertTrue(prompt.contains(WebPrompt.AFTER_TOOLS_XML))
        assertTrue(prompt.endsWith("Assistant:"))
    }

    @Test
    fun theStreamKeepsTheAnswerApartFromThinking() {
        val stream = QwenStream()
        val parts = listOf(
            """data: {"choices":[{"delta":{"phase":"think","content":"hmm"}}]}""",
            """data: {"choices":[{"delta":{"phase":"answer","content":"Hel"}}]}""",
            """data: {"choices":[{"delta":{"phase":"answer","content":"lo","status":"finished"}}]}""",
            "data: [DONE]",
        ).mapNotNull { stream.feed(it) }
        assertEquals("Hello", parts.joinToString("") { it.text })
        assertEquals("hmm", parts.joinToString("") { it.reasoning })
        assertTrue(stream.finished)
        assertNull(stream.error)

        val limited = QwenStream()
        limited.feed("""data: {"success":false,"data":{"code":"RateLimited"}}""")
        assertEquals(QwenClient.RATE_LIMIT, limited.error)

        val captcha = QwenStream()
        captcha.feed("""data: {"ret":["RGV587_ERROR"],"data":{"url":"https://example"}}""")
        assertEquals(QwenClient.HUMAN_CHECK, captcha.error)

        val nested = QwenStream()
        val hidden = nested.feed("""{"data":{"choices":[{"delta":{"phase":"answer","content":"pong"}}]}}""")
        assertEquals("pong", hidden!!.text)

        val detailed = QwenStream()
        detailed.feed("""data: {"error":{"code":"InvalidParam","details":"bad timestamp"}}""")
        assertEquals("Qwen web error: InvalidParam bad timestamp", detailed.error)
    }

    @Test
    fun aQwenStallKeepsItsOwnWording() {
        val error = ModelStall.explain(IOException(QwenClient.RATE_LIMIT))
        assertEquals(QwenClient.RATE_LIMIT, error.message)
        assertTrue(ModelStall.isStall(error))
    }
}
