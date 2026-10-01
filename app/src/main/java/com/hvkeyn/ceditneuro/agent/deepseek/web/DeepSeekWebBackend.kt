package com.hvkeyn.ceditneuro.agent.deepseek.web

import android.content.Context
import com.hvkeyn.ceditneuro.agent.AgentBackend
import com.hvkeyn.ceditneuro.agent.BackendChunk
import com.hvkeyn.ceditneuro.agent.ChatMessage
import com.hvkeyn.ceditneuro.agent.ContextBudget
import com.hvkeyn.ceditneuro.agent.ToolTranscript
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
 * The free DeepSeek web chat, spoken from this phone with no computer and no API key.
 * Each request is one new web chat: the history is flattened into a prompt, tool calls are
 * read back from the text, a reply cut mid-call is continued, and the chat is then removed.
 */
class DeepSeekWebBackend(
    private val context: Context,
    private val settingsProvider: () -> AgentSettings,
    private val store: WebSessionStore = WebSessionStore(context),
    private val client: WebClient = WebClient(),
) : AgentBackend {

    override fun complete(messages: List<ChatMessage>, tools: List<Tool>): Flow<BackendChunk> = flow {
        val settings = settingsProvider()
        val model = settings.model
        val sent = tools.takeIf { model.supportsTools }.orEmpty()
        val toolChars = WebPrompt.toolInstructions(sent).length
        val fitted = ContextBudget.fit(
            ToolTranscript.seal(messages),
            contextTokens = model.maxContextTokens,
            reserveTokens = model.maxOutputTokens,
            fixedChars = toolChars,
        )
        val prompt = WebPrompt.prompt(fitted, sent)
        val names = sent.map { it.name }.toSet()
        val modelType = if (model.name.contains("expert")) "expert" else "default"

        val reply = LOCK.withLock {
            var session = store.load() ?: throw IOException(NOT_SIGNED_IN)
            try {
                ask(session, prompt, modelType, names)
            } catch (expired: WebLoginRequired) {
                session = WebLogin.refresh(context) ?: throw expired
                store.save(session)
                ask(session, prompt, modelType, names)
            }
        }
        if (reply.reasoning.isNotBlank()) emit(BackendChunk.Reasoning(reply.reasoning))
        val (content, calls) = if (names.isEmpty()) reply.text to null else WebPrompt.parse(reply.text, names)
        if (content.isNotBlank()) emit(BackendChunk.Text(content))
        if (!calls.isNullOrEmpty()) emit(BackendChunk.ToolCalls(calls))
        emit(BackendChunk.Finished(if (calls.isNullOrEmpty()) "stop" else "tool_calls"))
    }.flowOn(Dispatchers.IO)

    private fun ask(session: WebSession, prompt: String, modelType: String, names: Set<String>): WebClient.Reply {
        val chat = client.createSession(session)
        try {
            var reply = client.complete(session, prompt, chat, null, modelType, thinking = false) { }
            repeat(MAX_CONTINUATIONS) {
                if (names.isEmpty()) return reply
                if (WebPrompt.parse(reply.text, names).second != null || !WebPrompt.truncated(reply.text, names)) return reply
                val next = client.complete(session, WebPrompt.CONTINUE, chat, reply.messageId, null, thinking = false) { }
                reply = reply.copy(text = reply.text + next.text, messageId = next.messageId ?: reply.messageId)
            }
            return reply
        } finally {
            client.deleteSession(session, chat)
        }
    }

    companion object {
        private val LOCK = Mutex()
        private const val MAX_CONTINUATIONS = 3
        const val NOT_SIGNED_IN = "DeepSeek Web (on this phone) is not signed in. Open Settings > Models and tap Sign in."
    }
}
