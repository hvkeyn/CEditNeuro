package com.hvkeyn.ceditneuro.agent.qwen.web

import android.content.Context
import com.hvkeyn.ceditneuro.agent.AgentBackend
import com.hvkeyn.ceditneuro.agent.BackendChunk
import com.hvkeyn.ceditneuro.agent.ChatMessage
import com.hvkeyn.ceditneuro.agent.ContextBudget
import com.hvkeyn.ceditneuro.agent.ToolTranscript
import com.hvkeyn.ceditneuro.agent.deepseek.web.WebPrompt
import com.hvkeyn.ceditneuro.data.AgentSettings
import com.hvkeyn.ceditneuro.tools.Tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.IOException

/**
 * The free Qwen web chat, spoken from this phone with no computer and no API key.
 * Each request is one new web chat: the history is flattened into a prompt, tool calls are
 * read back from `<tool_call>` blocks (a fenced tool_calls block is accepted too), and the chat is then removed.
 */
class QwenWebBackend(
    private val context: Context,
    private val settingsProvider: () -> AgentSettings,
    private val store: QwenSessionStore = QwenSessionStore(context),
    private val client: QwenClient = QwenClient(),
) : AgentBackend {

    override fun complete(messages: List<ChatMessage>, tools: List<Tool>): Flow<BackendChunk> = flow {
        val settings = settingsProvider()
        val model = settings.model
        val sent = tools.takeIf { model.supportsTools }.orEmpty()
        val toolChars = WebPrompt.toolInstructions(sent, xml = true).length
        val fitted = ContextBudget.fit(
            ToolTranscript.seal(messages),
            contextTokens = model.maxContextTokens,
            reserveTokens = model.maxOutputTokens,
            fixedChars = toolChars,
        )
        val prompt = WebPrompt.prompt(fitted, sent, xml = true)
        val names = sent.map { it.name }.toSet()

        val reply = LOCK.withLock {
            var session = store.load() ?: throw IOException(NOT_SIGNED_IN)
            try {
                ask(session, prompt, model.name, names)
            } catch (expired: QwenLoginRequired) {
                session = QwenLogin.refresh(context) ?: throw expired
                store.save(session)
                ask(session, prompt, model.name, names)
            }
        }
        if (reply.reasoning.isNotBlank()) emit(BackendChunk.Reasoning(reply.reasoning))
        val (content, calls) = if (names.isEmpty()) reply.text to null else WebPrompt.parse(reply.text, names)
        if (content.isNotBlank()) emit(BackendChunk.Text(content))
        if (!calls.isNullOrEmpty()) emit(BackendChunk.ToolCalls(calls))
        emit(BackendChunk.Finished(if (calls.isNullOrEmpty()) "stop" else "tool_calls"))
    }.flowOn(Dispatchers.IO)

    private fun ask(session: QwenSession, prompt: String, model: String, names: Set<String>): QwenClient.Reply {
        val chat = client.createChat(session, QwenClient.canonicalModel(model))
        try {
            var reply = client.complete(session, prompt, chat, model)
            repeat(MAX_CONTINUATIONS) {
                if (names.isEmpty()) return reply
                if (WebPrompt.parse(reply.text, names).second != null || !WebPrompt.truncated(reply.text, names)) return reply
                val next = client.complete(
                    session,
                    WebPrompt.CONTINUE + "\n\nThe cut reply so far ends with:\n" + reply.text.takeLast(2000),
                    chat,
                    model,
                )
                reply = reply.copy(text = reply.text + next.text)
            }
            return reply
        } finally {
            client.deleteChat(session, chat)
        }
    }

    companion object {
        private val LOCK = Mutex()
        private const val MAX_CONTINUATIONS = 3
        const val NOT_SIGNED_IN = "Qwen Web (on this phone) is not signed in. Open Settings > Models and tap Sign in."
    }
}
