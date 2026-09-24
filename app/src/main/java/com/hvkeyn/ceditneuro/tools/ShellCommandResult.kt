package com.hvkeyn.ceditneuro.tools

/** [com.hvkeyn.ceditneuro.shizuku.ShellUserService] prefixes output with `exit=` or `timed out`. */
internal fun shellFailed(text: String): Boolean =
    text.startsWith("timed out") || (text.startsWith("exit=") && !text.startsWith("exit=0"))

internal fun shellPayload(text: String): String {
    val newline = text.indexOf('\n')
    if (text.startsWith("exit=") && newline >= 0) return text.substring(newline + 1).trim()
    return text.trim()
}
