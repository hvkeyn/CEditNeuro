package com.hvkeyn.ceditneuro.shell

/**
 * The in-app shell reports a raw process code. Two of those codes are not failures:
 * grep exits 1 when it finds nothing, and logcat exits 1 because this app has no READ_LOGS.
 */
internal object ShellExit {
    private val GREP = Regex("""(?:^|[;&|(`\n])\s*grep\b""")
    private val LOGCAT = Regex("""(?:^|[;&|(`\n])\s*logcat\b""")

    fun adjust(command: String, exitCode: Int, output: String = ""): Adjusted {
        val notes = mutableListOf<String>()
        var code = exitCode
        if (exitCode == 1 && GREP.containsMatchIn(command)) {
            code = 0
            notes += "No matches. A count of 0 is the result. Do not repeat this search."
        }
        if (LOGCAT.containsMatchIn(command)) {
            code = 0
            notes += "This app cannot read logcat. Do not run logcat again."
        }
        val refusal = refusal(output)
        if (refusal.isNotEmpty()) {
            code = 0
            notes += refusal
        }
        return Adjusted(code, notes.joinToString("\n"))
    }

    fun refusal(output: String): String {
        val line = output.lowercase()
        return when {
            "chain validation" in line || "certpathvalidator" in line ->
                "The certificate chain was rejected. Do not retry this host."
            "incorrect user data" in line || "\"status\":401" in line ->
                "The server rejected the user data. Do not repeat this login."
            "записан:" in line || "saved " in line && "bytes" in line ->
                "The file was written. Do not run this command again."
            "http 522" in line || "allorigins" in line ->
                "That proxy failed. Request the page URL directly. Do not call the proxy again."
            else -> ""
        }
    }

    data class Adjusted(val exitCode: Int, val note: String)
}
