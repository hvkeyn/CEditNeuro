package com.hvkeyn.ceditneuro.shell

import android.content.Context
import com.hvkeyn.ceditneuro.net.AgentNet
import com.hvkeyn.ceditneuro.workspace.StoragePaths
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
        if (isPlainFetch(command)) {
            val parsed = parseFetch(command)
                ?: return ShellOutput(1, "Usage: fetch URL DEST", timedOut = false)
            return download(parsed.first, parsed.second, directory)
        }

        val rewritten = StoragePaths.rewriteCommand(command)
        val process = ProcessBuilder(SHELL, "-c", rewritten)
            .directory(directory)
            .redirectErrorStream(false)
            .apply {
                environment().apply {
                    put("HOME", home.absolutePath)
                    put("TMPDIR", tmp.absolutePath)
                    put("EXTERNAL_STORAGE", StoragePaths.primaryRoot().absolutePath)
                    put("DOWNLOAD", StoragePaths.publicDownload().absolutePath)
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
                    val cert = File(toolchain, "etc/tls/cert.pem")
                    if (cert.isFile) {
                        put("SSL_CERT_FILE", cert.absolutePath)
                        put("GIT_SSL_CAINFO", cert.absolutePath)
                        put("CURL_CA_BUNDLE", cert.absolutePath)
                    }
                    val gitExec = File(toolchain, "libexec/git-core")
                    if (gitExec.isDirectory) put("GIT_EXEC_PATH", gitExec.absolutePath)
                    val templates = File(toolchain, "share/git-core/templates")
                    if (templates.isDirectory) put("GIT_TEMPLATE_DIR", templates.absolutePath)
                    val pythonHome = File(toolchain, "lib").listFiles()?.any {
                        it.isDirectory && it.name.startsWith("python3.")
                    } == true
                    if (pythonHome) {
                        put("PYTHONHOME", toolchain.absolutePath)
                        put("PYTHONUNBUFFERED", "1")
                    }
                    val androidSdk = File(toolchain, "android-sdk")
                    if (androidSdk.isDirectory) {
                        put("ANDROID_HOME", androidSdk.absolutePath)
                        put("ANDROID_SDK_ROOT", androidSdk.absolutePath)
                    }
                }
            }
            .start()

        val stdout = StringBuilder()
        val stderr = StringBuilder()
        val truncated = java.util.concurrent.atomic.AtomicBoolean(false)
        val pumps = listOf(process.inputStream to stdout, process.errorStream to stderr).map { (source, target) ->
            Thread {
                val buffer = ByteArray(4096)
                while (true) {
                    val read = runCatching { source.read(buffer) }.getOrDefault(-1)
                    if (read < 0) break
                    val chunk = String(buffer, 0, read)
                    synchronized(stdout) {
                        val used = stdout.length + stderr.length
                        if (used >= MAX_OUTPUT_CHARS) {
                            truncated.set(true)
                        } else {
                            val room = MAX_OUTPUT_CHARS - used
                            if (chunk.length <= room) target.append(chunk) else {
                                target.append(chunk.take(room))
                                truncated.set(true)
                            }
                        }
                    }
                }
            }.also { it.start() }
        }

        val finished = process.waitFor(timeout.toLong(), TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            pumps.forEach { it.join(1_000) }
            return ShellOutput(
                exitCode = null,
                output = renderOutput(stdout, stderr, truncated.get()) + StoragePaths.noteVisibleFiles(rewritten),
                timedOut = true,
            )
        }
        pumps.forEach { it.join(2_000) }
        return ShellOutput(
            exitCode = process.exitValue(),
            output = renderOutput(stdout, stderr, truncated.get()) + StoragePaths.noteVisibleFiles(rewritten),
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
        val dest = if (File(destArg).isAbsolute) {
            StoragePaths.absolute(destArg)
        } else {
            StoragePaths.finish(File(directory, destArg))
        }
        dest.parentFile?.mkdirs()
        val part = File(dest.parentFile, dest.name + ".part")
        return runCatching {
            val started = System.currentTimeMillis()
            net.download(url, part, AgentNet.MAX_FILE_BYTES, 180)
            if (dest.exists() && !dest.delete()) {
                part.delete()
                return ShellOutput(1, "Could not replace ${dest.path}", timedOut = false)
            }
            if (!part.renameTo(dest)) {
                part.copyTo(dest, overwrite = true)
                part.delete()
            }
            StoragePaths.scan(dest)
            val seconds = ((System.currentTimeMillis() - started).coerceAtLeast(1)) / 1000.0
            val bytes = dest.length()
            val mbit = bytes * 8.0 / seconds / 1_000_000.0
            ShellOutput(
                0,
                "saved $bytes bytes in ${"%.1f".format(seconds)} s (${"%.2f".format(mbit)} Mbit/s) to ${dest.absolutePath}",
                timedOut = false,
            )
        }.getOrElse { error ->
            part.delete()
            val detail = error.message ?: "Download failed."
            val text = if (
                detail.contains("Unable to resolve host", ignoreCase = true) ||
                detail.contains("No address associated", ignoreCase = true)
            ) {
                "$detail Do not retry this URL."
            } else {
                detail
            }
            ShellOutput(1, text, timedOut = false)
        }
    }

    companion object {
        private const val SHELL = "/system/bin/sh"
        private const val MAX_OUTPUT_CHARS = 20_000

        private fun isPlainFetch(command: String): Boolean {
            val trimmed = command.trim()
            if (trimmed.any { it == '\n' || it == ';' || it == '|' || it == '&' }) return false
            return trimmed == "fetch" || trimmed.startsWith("fetch ")
        }

        private fun parseFetch(command: String): Pair<String, String>? {
            val parts = command.trim().split(Regex("\\s+"))
            if (parts.size != 3 || parts[0] != "fetch") return null
            return parts[1] to parts[2]
        }

        private fun renderOutput(stdout: StringBuilder, stderr: StringBuilder, truncated: Boolean): String {
            val out = stdout.toString().trimEnd()
            val err = stderr.toString().trimEnd()
            val text = buildString {
                append(out)
                if (err.isNotEmpty()) {
                    if (isNotEmpty()) append('\n')
                    append("--- stderr ---\n")
                    append(err)
                }
            }
            return if (truncated) "$text\n… truncated" else text
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
