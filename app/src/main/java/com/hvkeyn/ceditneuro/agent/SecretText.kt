package com.hvkeyn.ceditneuro.agent

/** Keeps keys and passwords out of skills, memory, and session-search snippets. */
object SecretText {
    private val blocked = listOf(
        Regex("""sk-[A-Za-z0-9_\-]{8,}"""),
        Regex("""ghp_[A-Za-z0-9]{8,}"""),
        Regex("""github_pat_[A-Za-z0-9_]{8,}"""),
        Regex("""AKIA[0-9A-Z]{12,}"""),
        Regex("""-----BEGIN [A-Z ]*PRIVATE KEY-----"""),
    )
    private val passwordField = Regex(""""password"\s*:\s*"(?:\\.|[^"\\])*"""")

    fun reject(text: String): String? =
        if (blocked.any { it.containsMatchIn(text) }) {
            "That text looks like a key or password. Do not store it in a skill or memory."
        } else {
            null
        }

    fun redact(text: String): String {
        var out = passwordField.replace(text, """"password":"…"""")
        blocked.forEach { pattern -> out = pattern.replace(out, "…") }
        return out
    }
}
