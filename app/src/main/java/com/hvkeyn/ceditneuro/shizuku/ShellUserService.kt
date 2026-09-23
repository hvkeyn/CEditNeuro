package com.hvkeyn.ceditneuro.shizuku

import java.io.File
import java.io.InputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Runs inside the Shizuku shell process (UID 2000). Shizuku creates this class
 * itself, so it has a public empty constructor and does not use the app process.
 */
class ShellUserService : IShellService.Stub() {
    override fun destroy() {
        System.exit(0)
    }

    override fun exec(command: String, cwd: String, timeoutSeconds: Int): String {
        val timeout = timeoutSeconds.coerceIn(5, 180)
        val directory = File(cwd).takeIf { it.isDirectory }
        return try {
            val process = ProcessBuilder("/system/bin/sh", "-c", command)
                .apply { if (directory != null) directory(directory) }
                .redirectErrorStream(false)
                .start()
            val output = StringBuilder()
            val truncated = AtomicBoolean(false)
            val pumps = listOf(process.inputStream, process.errorStream).map { pump(it, output, truncated) }
            val finished = process.waitFor(timeout.toLong(), TimeUnit.SECONDS)
            if (!finished) {
                process.destroyForcibly()
                pumps.forEach { it.join(1_000) }
                return "timed out\n${render(output, truncated.get())}"
            }
            pumps.forEach { it.join(2_000) }
            "exit=${process.exitValue()}\n${render(output, truncated.get())}"
        } catch (error: Exception) {
            "exit=1\n${error.message ?: "Shizuku command failed."}"
        }
    }

    private fun pump(source: InputStream, output: StringBuilder, truncated: AtomicBoolean): Thread =
        Thread {
            val buffer = ByteArray(4096)
            while (true) {
                val read = runCatching { source.read(buffer) }.getOrDefault(-1)
                if (read < 0) break
                val chunk = String(buffer, 0, read)
                synchronized(output) {
                    if (output.length >= MAX_OUTPUT) {
                        truncated.set(true)
                    } else {
                        val room = MAX_OUTPUT - output.length
                        if (chunk.length <= room) output.append(chunk) else {
                            output.append(chunk.take(room))
                            truncated.set(true)
                        }
                    }
                }
            }
        }.also { it.start() }

    private fun render(output: StringBuilder, truncated: Boolean): String {
        val text = synchronized(output) { output.toString() }.trimEnd().ifBlank { "(no output)" }
        return if (truncated) "$text\n… truncated" else text
    }

    companion object {
        private const val MAX_OUTPUT = 20_000
    }
}
