package com.hvkeyn.ceditneuro.shell

import java.io.File

/**
 * Decides whether a shell command starts something other than the system toybox shell.
 * Those commands need the one-time user allow, because they can run a compiler.
 */
object ProgramRun {
    fun needsConsent(command: String): Boolean =
        command.split(SEGMENT).any { segment ->
            val token = firstToken(segment) ?: return@any false
            when {
                token.startsWith("/system/") || token.startsWith("/vendor/") || token.startsWith("/apex/") -> false
                !token.contains('/') && token in SHELL_COMMANDS -> false
                else -> true
            }
        }

    fun markExecutable(file: File) {
        file.setReadable(true, true)
        file.setWritable(true, true)
        file.setExecutable(true, true)
    }

    fun markTree(root: File) {
        if (!root.exists()) return
        root.walkTopDown().forEach { file ->
            if (file.isDirectory) {
                file.setExecutable(true, true)
            } else if (file.parentFile?.name == "bin" || file.parentFile == root) {
                markExecutable(file)
            }
        }
    }

    private fun firstToken(segment: String): String? {
        val raw = segment.trim().split(Regex("\\s+"), limit = 2).firstOrNull()?.trim('"', '\'', '`')
        return raw?.takeIf { it.isNotEmpty() }
    }

    private val SEGMENT = Regex("&&|\\|\\||;|\\||\\n")

    private val SHELL_COMMANDS = setOf(
        "echo", "printf", "ls", "mkdir", "rm", "cp", "mv", "cat", "grep", "egrep", "fgrep",
        "sed", "find", "chmod", "chown", "ln", "pwd", "id", "uname", "df", "du", "ps",
        "toybox", "sh", "mksh", "head", "tail", "wc", "sort", "uniq", "cut", "tr", "xargs",
        "tar", "gzip", "gunzip", "unzip", "sha1sum", "md5sum", "stat", "touch", "dirname",
        "basename", "readlink", "which", "env", "cd", "true", "false", "test", "[",
        "date", "sleep", "kill", "diff", "od", "hexdump", "fetch", "clear", "export",
    )
}
