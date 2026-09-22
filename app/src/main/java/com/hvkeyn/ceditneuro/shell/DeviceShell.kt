package com.hvkeyn.ceditneuro.shell

import android.content.Context
import com.hvkeyn.ceditneuro.net.AgentNet
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Our shell, inside this app. It runs `/system/bin/sh` (mksh + toybox) as the app
 * itself, so the user does not install a second terminal app.
 *
 * Programs installed for this app live in [toolchainBin]. install_jdk places an
 * Android build of OpenJDK there and adds its libraries to LD_LIBRARY_PATH.
 */
class DeviceShell(
    context: Context,
    private val networkAllowed: () -> Boolean = { true },
) {

    private val home: File = File(context.filesDir, "home").apply { mkdirs() }
    private val tmp: File = File(context.cacheDir, "shell").apply { mkdirs() }
    val toolchain: File = File(context.filesDir, "toolchain").apply { mkdirs() }
    val toolchainBin: File = File(toolchain, "bin").apply { mkdirs() }
    private val toolchainLib: File = File(toolchain, "lib").apply { mkdirs() }
    private val net = AgentNet()

    fun run(command: String, workDir: File, timeoutSeconds: Int): ShellOutput {
        val timeout = timeoutSeconds.coerceIn(5, 900)
        val directory = if (workDir.isDirectory) workDir else home
        parseFetch(command)?.let { (url, dest) ->
            return download(url, dest, directory)
        }

        val process = ProcessBuilder(SHELL, "-c", command)
            .directory(directory)
            .redirectErrorStream(true)
            .apply {
                environment().apply {
                    put("HOME", home.absolutePath)
                    put("TMPDIR", tmp.absolutePath)
                    put("TERM", "dumb")
                    val javaHome = File(toolchain, "lib/jvm/java-17-openjdk")
                    val libraryPath = buildList {
                        if (javaHome.isDirectory) {
                            add(File(javaHome, "lib").absolutePath)
                            add(File(javaHome, "lib/server").absolutePath)
                        }
                        add(toolchainLib.absolutePath)
                    }.joinToString(":")
                    put("TOOLCHAIN", toolchain.absolutePath)
                    put("PATH", "${toolchainBin.absolutePath}:/system/bin:/system/xbin")
                    put("LD_LIBRARY_PATH", libraryPath)
                    if (javaHome.isDirectory) put("JAVA_HOME", javaHome.absolutePath)
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

    /**
     * `fetch` is handled in this process. A separate app_process downloader is
     * killed on this Android version, and toybox has no curl.
     */
    private fun download(url: String, destArg: String, directory: File): ShellOutput {
        if (!networkAllowed()) {
            return ShellOutput(1, "Network is disabled in Settings.", timedOut = false)
        }
        val dest = if (File(destArg).isAbsolute) File(destArg) else File(directory, destArg)
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, dest.name + ".part")
        return runCatching {
            net.download(url, part, AgentNet.MAX_DOWNLOAD_BYTES)
            if (dest.exists() && !dest.delete()) {
                part.delete()
                return ShellOutput(1, "Could not replace ${dest.path}", timedOut = false)
            }
            if (!part.renameTo(dest)) {
                part.copyTo(dest, overwrite = true)
                part.delete()
            }
            ShellOutput(0, "saved ${dest.length()} bytes to ${dest.path}", timedOut = false)
        }.getOrElse { error ->
            part.delete()
            ShellOutput(1, error.message ?: "Download failed.", timedOut = false)
        }
    }

    companion object {
        private const val SHELL = "/system/bin/sh"
        private const val MAX_OUTPUT_CHARS = 20_000

        private fun parseFetch(command: String): Pair<String, String>? {
            val trimmed = command.trim()
            if (!trimmed.startsWith("fetch ") || trimmed.any { it == '\n' || it == ';' || it == '|' || it == '&' }) {
                return null
            }
            val parts = trimmed.split(Regex("\\s+"))
            if (parts.size != 3 || parts[0] != "fetch") return null
            return parts[1] to parts[2]
        }
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
