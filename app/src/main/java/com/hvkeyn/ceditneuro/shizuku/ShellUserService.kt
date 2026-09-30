package com.hvkeyn.ceditneuro.shizuku

/**
 * Runs inside the Shizuku shell process (UID 2000). Shizuku creates this class
 * itself, so it has a public empty constructor and does not use the app process.
 */
class ShellUserService : IShellService.Stub() {
    override fun destroy() {
        System.exit(0)
    }

    override fun exec(command: String, cwd: String, timeoutSeconds: Int): String =
        ShellCommand.run(command, cwd, timeoutSeconds)
}
