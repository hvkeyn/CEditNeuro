package com.hvkeyn.ceditneuro.agent.deepseek

import java.io.IOException
import java.net.SocketTimeoutException

/**
 * DeepSeek sometimes accepts a request and then stays silent, or answers that it is busy.
 * Those are not a dead radio: retrying them just keeps the spinner going.
 */
object ModelStall {
    const val MESSAGE = "DeepSeek did not answer. It may be overloaded. Try again in a moment."

    fun isStall(error: Throwable): Boolean {
        val chain = generateSequence(error) { it.cause }.toList()
        val text = chain.joinToString(" ") { it.message.orEmpty() }.lowercase()
        if ("login_required" in text || "sign in" in text) return false
        if ("connect timed out" in text || "failed to connect" in text) return false
        if (chain.any { it is SocketTimeoutException }) return true
        return listOf(
            "busy",
            "overload",
            "too many requests",
            "rate limit",
            "http 429",
            "http 529",
            "try again later",
            "did not answer",
        ).any { it in text }
    }

    fun explain(error: IOException): IOException {
        if (!isStall(error) || error.message == MESSAGE) return error
        return IOException(MESSAGE, error)
    }

    fun userMessage(raw: String): String {
        val text = raw.trim()
        return if (isStall(IOException(text))) MESSAGE else "DeepSeek web error: $text"
    }
}
