package com.hvkeyn.ceditneuro.shell

/**
 * Commands this phone's shell cannot finish. Rejecting them once is the result.
 * A later call with the same shape is the same failure.
 */
internal object ShellShape {
    fun reject(command: String): String? {
        val text = command.trim()
        if ("<<" in text) {
            return "This shell cannot run a heredoc. write_file the script in the project, then run python on that file. Do not use << again."
        }
        if (TIMEOUT.containsMatchIn(text)) {
            return "Do not wrap a command in timeout. The shell stops on its own. Do not repeat this wrapper."
        }
        if (SED_PIPE.containsMatchIn(text)) {
            return "Do not pipe through sed to shorten output. Run the program by itself. Do not repeat the pipe."
        }
        return null
    }

    /** Same key for every heredoc, every timeout wrapper, and every sed pipe. */
    fun shapeKey(command: String): String? {
        val text = command.trim()
        return when {
            "<<" in text -> "run_command\nheredoc"
            TIMEOUT.containsMatchIn(text) -> "run_command\ntimeout-wrapper"
            SED_PIPE.containsMatchIn(text) -> "run_command\nsed-pipe"
            else -> null
        }
    }

    private val TIMEOUT = Regex("""(?:^|[;&|])\s*timeout\s+\d+""")
    private val SED_PIPE = Regex("""\|\s*sed\b""")
}
