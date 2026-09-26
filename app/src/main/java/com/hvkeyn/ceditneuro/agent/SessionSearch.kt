package com.hvkeyn.ceditneuro.agent

import com.hvkeyn.ceditneuro.data.StoredSession

/** Finds short snippets in this project's own saved chat. */
object SessionSearch {
    fun query(session: StoredSession, rawQuery: String, limit: Int = 6): String {
        val needle = rawQuery.trim()
        if (needle.length < 2) return "Query must be at least 2 characters."
        val folded = needle.lowercase()
        val hits = LinkedHashSet<String>()
        session.chat.forEach { line ->
            addHit(hits, line.role, line.text, folded, limit)
        }
        session.conversation.forEach { message ->
            if (message.role == "system") return@forEach
            addHit(hits, message.role, message.content.orEmpty(), folded, limit)
        }
        session.shell.forEach { line ->
            addHit(hits, "shell", line.command + "\n" + line.output, folded, limit)
        }
        if (hits.isEmpty()) return "No earlier message in this project contains that text."
        return hits.joinToString("\n")
    }

    private fun addHit(hits: LinkedHashSet<String>, role: String, text: String, needle: String, limit: Int) {
        if (hits.size >= limit || text.isBlank()) return
        val window = if (text.length > 20_000) text.take(20_000) else text
        val at = window.lowercase().indexOf(needle)
        if (at < 0) return
        val start = (at - 80).coerceAtLeast(0)
        val end = (at + needle.length + 140).coerceAtMost(window.length)
        val snippet = SecretText.redact(window.substring(start, end)).replace('\n', ' ').trim()
        if (snippet.isNotEmpty()) hits += "$role: $snippet"
    }
}
