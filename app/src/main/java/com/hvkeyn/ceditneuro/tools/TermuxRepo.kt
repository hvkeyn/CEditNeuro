package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.net.AgentNet
import java.io.File
import java.io.IOException
import java.net.URLEncoder
import java.util.zip.GZIPInputStream

/**
 * Termux drops old Debian filenames when a library is rebuilt. A pinned URL is tried
 * first. A 404 is resolved through the current package index so the installer does not
 * stop on a version that the mirror has already replaced.
 */
internal object TermuxRepo {
    private const val BASE = "https://packages.termux.dev/apt/termux-main/"
    private const val INDEX = BASE + "dists/stable/main/binary-aarch64/Packages.gz"

    private var index: Map<String, String>? = null

    fun download(net: AgentNet, path: String, dest: File, maxBytes: Long, timeoutSeconds: Long) {
        try {
            net.download(url(path), dest, maxBytes, timeoutSeconds)
        } catch (error: IOException) {
            if (error.message?.contains("HTTP 404") != true) throw error
            val fresh = currentFilename(net, packageName(path))
            if (fresh.isNullOrBlank() || fresh == path) throw error
            net.download(url(fresh), dest, maxBytes, timeoutSeconds)
        }
    }

    private fun url(path: String): String =
        BASE + path.split('/').joinToString("/") { segment ->
            URLEncoder.encode(segment, Charsets.UTF_8).replace("+", "%20")
        }

    private fun packageName(path: String): String =
        path.trim('/').substringBeforeLast('/').substringAfterLast('/')

    private fun currentFilename(net: AgentNet, name: String): String? {
        val known = index ?: loadIndex(net).also { index = it }
        return known[name]
    }

    private fun loadIndex(net: AgentNet): Map<String, String> {
        val file = File.createTempFile("termux-packages", ".gz")
        return try {
            net.download(INDEX, file, 4L * 1024L * 1024L, 90)
            GZIPInputStream(file.inputStream()).bufferedReader().use { reader ->
                val found = linkedMapOf<String, String>()
                var packageName = ""
                var filename = ""
                fun store() {
                    if (packageName.isNotEmpty() && filename.isNotEmpty()) found[packageName] = filename
                }
                while (true) {
                    val line = reader.readLine() ?: break
                    when {
                        line.isBlank() -> {
                            store()
                            packageName = ""
                            filename = ""
                        }
                        line.startsWith("Package: ") -> packageName = line.removePrefix("Package: ").trim()
                        line.startsWith("Filename: ") -> filename = line.removePrefix("Filename: ").trim()
                    }
                }
                store()
                found
            }
        } finally {
            file.delete()
        }
    }
}
