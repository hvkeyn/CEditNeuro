package com.hvkeyn.ceditneuro.shell

/**
 * The in-app shell reports a raw process code. Two of those codes are not failures:
 * grep exits 1 when it finds nothing, and logcat exits 1 because this app has no READ_LOGS.
 */
internal object ShellExit {
    private val GREP = Regex("""(?:^|[;&|(`\n])\s*grep\b""")
    private val LOGCAT = Regex("""(?:^|[;&|(`\n])\s*logcat\b""")

    fun adjust(command: String, exitCode: Int): Adjusted {
        val notes = mutableListOf<String>()
        var code = exitCode
        if (exitCode == 1 && GREP.containsMatchIn(command)) {
            code = 0
            notes += "No matches. A count of 0 is the result. Do not repeat this search."
        }
        if (LOGCAT.containsMatchIn(command)) {
            notes += "This app cannot read logcat. Do not run logcat again."
        }
        return Adjusted(code, notes.joinToString("\n"))
    }

    data class Adjusted(val exitCode: Int, val note: String)
}
