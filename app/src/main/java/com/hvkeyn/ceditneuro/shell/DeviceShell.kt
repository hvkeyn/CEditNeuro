package com.hvkeyn.ceditneuro.shell

import android.content.Context
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Our shell, inside this app. It runs `/system/bin/sh` (mksh + toybox) as the app
 * itself, so the user does not install a second terminal app.
 *
 * This is not the Termux distribution. Termux packages are built for the hardcoded
 * prefix `/data/data/com.termux/files/usr` and cannot be executed from another
 * application id. Compilers and `pkg` are therefore not part of this shell.
 */
class DeviceShell(context: Context) {

    private val home: File = File(context.filesDir, "home").apply { mkdirs() }
    private val tmp: File = File(context.cacheDir, "shell").apply { mkdirs() }

    fun run(command: String, workDir: File, timeoutSeconds: Int): ShellOutput {
        val timeout = timeoutSeconds.coerceIn(5, 900)
        val directory = if (workDir.isDirectory) workDir else home

        val process = ProcessBuilder(SHELL, "-c", command)
            .directory(directory)
            .redirectErrorStream(true)
            .apply {
                environment().apply {
                    put("HOME", home.absolutePath)
                    put("TMPDIR", tmp.absolutePath)
                    put("TERM", "dumb")
                    put("PATH", "/system/bin:/system/xbin")
                }
            }
            .start()

        val output = StringBuilder()
        val pump = Thread {
            val buffer = ByteArray(4096)
            val stream = process.inputStream
            while (true) {
                val read = runCatching { stream.read(buffer) }.getOrDefault(-1)
                if (read < 0) break
                if (output.length < MAX_OUTPUT_CHARS) {
                    val chunk = String(buffer, 0, read)
                    val room = MAX_OUTPUT_CHARS - output.length
                    output.append(if (chunk.length <= room) chunk else chunk.take(room))
                }
            }
        }
        pump.start()

        val finished = process.waitFor(timeout.toLong(), TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            pump.join(1_000)
            return ShellOutput(
                exitCode = null,
                output = output.toString().trimEnd(),
                timedOut = true,
            )
        }
        pump.join(2_000)
        return ShellOutput(
            exitCode = process.exitValue(),
            output = output.toString().trimEnd(),
            timedOut = false,
        )
    }

    companion object {
        private const val SHELL = "/system/bin/sh"
        private const val MAX_OUTPUT_CHARS = 20_000
    }
}

data class ShellOutput(
    val exitCode: Int?,
    val output: String,
    val timedOut: Boolean,
) {
    fun render(): String = when {
        timedOut -> "timed out\n${output.ifBlank { "(no output)" }}"
        else -> "exit=$exitCode\n${output.ifBlank { "(no output)" }}"
    }
}
