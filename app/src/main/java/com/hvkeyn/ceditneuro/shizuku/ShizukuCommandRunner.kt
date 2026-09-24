package com.hvkeyn.ceditneuro.shizuku

/**
 * One shell-user command through the existing [ShizukuShell].
 * Shizuku (or su on a rooted phone) must already be allowed; this does not raise privileges.
 */
class ShizukuCommandRunner(private val shell: ShizukuShell) {
    suspend fun executeShizukuCommand(command: String, timeoutSeconds: Int = 30): String =
        shell.exec(command, cwd = "/", timeoutSeconds = timeoutSeconds)
}
