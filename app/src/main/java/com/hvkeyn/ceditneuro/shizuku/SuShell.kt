package com.hvkeyn.ceditneuro.shizuku

import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Same commands as the Shizuku shell user, run by this app when the phone has root.
 * A normal app process cannot become UID 2000 by itself.
 */
object SuShell {
    @Volatile
    private var cached: Boolean? = null

    fun available(): Boolean {
        cached?.let { return it }
        val probe = run("id", "/", 8)
        val ok = probe.startsWith("exit=0") && probe.contains("uid=0")
        cached = ok
        return ok
    }

    fun exec(command: String, cwd: String, timeoutSeconds: Int): String {
        val quoted = "'" + cwd.replace("'", "'\\''") + "'"
        return run("cd $quoted && $command", cwd, timeoutSeconds)
    }

    private fun run(command: String, cwd: String, timeoutSeconds: Int): String {
        val timeout = timeoutSeconds.coerceIn(5, 180)
        val directory = File(cwd).takeIf { it.isDirectory }
        return try {
            val process = ProcessBuilder("su", "-c", command)
                .apply { if (directory != null) directory(directory) }
                .redirectErrorStream(true)
                .start()
            val output = StringBuilder()
            val truncated = AtomicBoolean(false)
            val pump = Thread {
                val buffer = ByteArray(4096)
                while (true) {
                    val read = runCatching { process.inputStream.read(buffer) }.getOrDefault(-1)
                    if (read < 0) break
                    val chunk = String(buffer, 0, read)
                    synchronized(output) {
                        if (output.length >= MAX_OUTPUT) truncated.set(true)
                        else {
                            val room = MAX_OUTPUT - output.length
                            if (chunk.length <= room) output.append(chunk) else {
                                output.append(chunk.take(room))
                                truncated.set(true)
                            }
                        }
                    }
                }
            }.also { it.start() }
            val finished = process.waitFor(timeout.toLong(), TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                pump.join(1_000)
                return "timed out\n${render(output, truncated.get())}"
            }
            pump.join(2_000)
            "exit=${process.exitValue()}\n${render(output, truncated.get())}"
        } catch (error: Exception) {
            "exit=1\n${error.message ?: "su failed."}"
        }
    }

    private fun render(output: StringBuilder, truncated: Boolean): String {
        val text = synchronized(output) { output.toString() }.trimEnd().ifBlank { "(no output)" }
        return if (truncated) "$text\n… truncated" else text
    }

    private const val MAX_OUTPUT = 20_000
}
