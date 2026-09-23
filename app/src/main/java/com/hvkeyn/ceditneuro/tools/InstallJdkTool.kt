package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.net.AgentNet
import com.hvkeyn.ceditneuro.shell.ProgramRun
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.util.zip.ZipInputStream

/**
 * Downloads an Android build of OpenJDK 17 plus the Kotlin compiler into the private
 * toolchain. The JDK binaries use DT_RUNPATH, so the shell's LD_LIBRARY_PATH is what
 * finds libjli and the small Android support libraries.
 */
class InstallJdkTool(
    private val toolchain: File,
    private val net: AgentNet,
    private val networkAllowed: () -> Boolean,
    private val ensureExec: suspend (String) -> Boolean,
) : Tool {
    override val name = "install_jdk"
    override val description =
        "Download OpenJDK 17 and the Kotlin compiler into this app so java, javac, and kotlinc " +
            "can build programs. Call this once before compiling. The user is asked to allow it. " +
            "This builds ordinary Java and Kotlin programs, not Android APKs."
    override val parameters = objectSchema(properties = emptyMap())

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        if (alreadyInstalled()) {
            return@withContext ToolResult.ok(ALREADY)
        }
        if (!networkAllowed()) {
            return@withContext ToolResult.error("Agent network is off. Turn on Agent network in Settings.")
        }
        val allowed = ensureExec("Download and run OpenJDK and the Kotlin compiler inside this app.")
        if (!allowed) {
            return@withContext ToolResult.error(
                "The user did not allow running installed programs. They can turn it on in Settings.",
            )
        }
        val staging = File(toolchain, ".jdk-staging").apply {
            deleteRecursively()
            mkdirs()
        }
        val unpacked = longArrayOf(0L)
        try {
            for (path in DEBS) {
                val deb = File(staging, path.substringAfterLast('/'))
                net.download(BASE + path, deb, MAX_DOWNLOAD, TIMEOUT_SECONDS)
                extractDeb(deb, toolchain, unpacked)
                deb.delete()
            }
            val zip = File(staging, "kotlin-compiler.zip")
            net.download(KOTLIN_URL, zip, MAX_DOWNLOAD, TIMEOUT_SECONDS)
            extractKotlin(zip, File(toolchain, "kotlin/lib"), unpacked)
            writeWrappers()
            ProgramRun.markTree(toolchain)
            File(toolchain, STAMP_NAME).writeText(STAMP)
            ToolResult.ok(INSTALLED)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ToolResult.error(error.message ?: "JDK install failed.")
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun alreadyInstalled(): Boolean {
        val stamp = File(toolchain, STAMP_NAME)
        return stamp.isFile && stamp.readText() == STAMP &&
            File(toolchain, "bin/javac").isFile &&
            File(toolchain, "bin/kotlinc").isFile
    }

    private fun extractDeb(deb: File, destRoot: File, unpacked: LongArray) {
        FileInputStream(deb).use { input ->
            val magic = ByteArray(8)
            readFully(input, magic)
            if (!magic.contentEquals(AR_MAGIC)) throw IllegalStateException("Not a Debian package: ${deb.name}")
            while (true) {
                val header = ByteArray(60)
                val read = readFullyOrEof(input, header)
                if (read == 0) break
                if (read < 60) throw IllegalStateException("Truncated Debian package: ${deb.name}")
                val name = String(header, 0, 16, Charsets.US_ASCII).trim().trimEnd('/')
                val size = String(header, 48, 10, Charsets.US_ASCII).trim().toLong()
                if (name == "data.tar.xz") {
                    BoundedInputStream(input, size).use { slice ->
                        XZCompressorInputStream(slice).use { xz ->
                            extractTar(TarArchiveInputStream(xz), destRoot, unpacked)
                        }
                    }
                } else {
                    skipFully(input, size)
                }
                if (size % 2L == 1L) skipFully(input, 1)
            }
        }
    }

    private fun extractTar(tar: TarArchiveInputStream, destRoot: File, unpacked: LongArray) {
        val rootPath = destRoot.canonicalFile
        while (true) {
            val entry = tar.nextEntry ?: break
            val rel = toolchainRelative(entry.name) ?: continue
            if (skipEntry(rel)) continue
            val out = File(rootPath, rel).canonicalFile
            if (out.path != rootPath.path && !out.path.startsWith(rootPath.path + File.separator)) {
                throw IllegalStateException("Archive entry escapes the toolchain: ${entry.name}")
            }
            when {
                entry.isDirectory -> out.mkdirs()
                entry.isSymbolicLink -> {
                    out.parentFile?.mkdirs()
                    val target = linkTarget(entry.linkName)
                    val path = out.toPath()
                    if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.delete(path)
                    Files.createSymbolicLink(path, Paths.get(target))
                }
                entry.isFile -> {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val read = tar.read(buffer)
                            if (read < 0) break
                            unpacked[0] += read
                            if (unpacked[0] > MAX_UNPACKED) {
                                throw IllegalStateException("Unpacked JDK is too large.")
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
            }
        }
    }

    private fun extractKotlin(zip: File, libDir: File, unpacked: LongArray) {
        libDir.mkdirs()
        ZipInputStream(zip.inputStream()).use { zipIn ->
            while (true) {
                val entry = zipIn.nextEntry ?: break
                val name = entry.name.replace('\\', '/')
                if (entry.isDirectory || !name.startsWith("kotlinc/lib/") || !name.endsWith(".jar")) {
                    zipIn.closeEntry()
                    continue
                }
                val base = name.substringAfterLast('/')
                if (base.isBlank() || base.contains("..")) {
                    throw IllegalStateException("Unexpected Kotlin archive entry: $name")
                }
                val out = File(libDir, base)
                out.outputStream().use { output ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = zipIn.read(buffer)
                        if (read < 0) break
                        unpacked[0] += read
                        if (unpacked[0] > MAX_UNPACKED) throw IllegalStateException("Unpacked JDK is too large.")
                        output.write(buffer, 0, read)
                    }
                }
                zipIn.closeEntry()
            }
        }
        if (libDir.listFiles().isNullOrEmpty()) {
            throw IllegalStateException("Kotlin compiler archive did not contain jars.")
        }
    }

    private fun writeWrappers() {
        val bin = File(toolchain, "bin").apply { mkdirs() }
        File(bin, "java").writeText(toolWrapper("java"))
        File(bin, "javac").writeText(toolWrapper("javac"))
        File(bin, "jar").writeText(toolWrapper("jar"))
        File(bin, "kotlinc").writeText(kotlinWrapper())
    }

    private fun toolWrapper(name: String): String = """
        #!/system/bin/sh
        ROOT=${'$'}(CDPATH= cd "${'$'}(dirname "${'$'}0")/.." && pwd)
        JAVA_HOME="${'$'}ROOT/lib/jvm/java-17-openjdk"
        export JAVA_HOME
        export LD_LIBRARY_PATH="${'$'}JAVA_HOME/lib:${'$'}JAVA_HOME/lib/server:${'$'}ROOT/lib${'$'}{LD_LIBRARY_PATH:+:${'$'}LD_LIBRARY_PATH}"
        exec "${'$'}JAVA_HOME/bin/$name" "${'$'}@"
    """.trimIndent() + "\n"

    private fun kotlinWrapper(): String = """
        #!/system/bin/sh
        ROOT=${'$'}(CDPATH= cd "${'$'}(dirname "${'$'}0")/.." && pwd)
        JAVA_HOME="${'$'}ROOT/lib/jvm/java-17-openjdk"
        export JAVA_HOME
        export LD_LIBRARY_PATH="${'$'}JAVA_HOME/lib:${'$'}JAVA_HOME/lib/server:${'$'}ROOT/lib${'$'}{LD_LIBRARY_PATH:+:${'$'}LD_LIBRARY_PATH}"
        mkdir -p "${'$'}ROOT/tmp"
        CP=
        for jar in "${'$'}ROOT/kotlin/lib/"*.jar; do
          if [ -z "${'$'}CP" ]; then CP="${'$'}jar"; else CP="${'$'}CP:${'$'}jar"; fi
        done
        exec "${'$'}JAVA_HOME/bin/java" -Djava.io.tmpdir="${'$'}ROOT/tmp" -Xmx768m -cp "${'$'}CP" org.jetbrains.kotlin.cli.jvm.K2JVMCompiler "${'$'}@"
    """.trimIndent() + "\n"

    private fun toolchainRelative(name: String): String? {
        val normalized = name.replace('\\', '/')
        val marker = "com.termux/files/usr/"
        val index = normalized.indexOf(marker)
        if (index < 0) return null
        val rel = normalized.substring(index + marker.length).trimStart('/')
        if (rel.isEmpty() || rel.split('/').any { it == ".." }) return null
        return rel
    }

    private fun skipEntry(rel: String): Boolean =
        rel.startsWith("share/") ||
            rel.startsWith("include/") ||
            rel.startsWith("lib/jvm/java-17-openjdk/jmods/") ||
            rel.startsWith("lib/jvm/java-17-openjdk/demo/") ||
            rel.startsWith("lib/jvm/java-17-openjdk/man/") ||
            rel.startsWith("lib/jvm/java-17-openjdk/include/") ||
            "/man/" in rel

    private fun linkTarget(raw: String): String {
        val normalized = raw.replace('\\', '/')
        val marker = "com.termux/files/usr/"
        val index = normalized.indexOf(marker)
        if (index >= 0) {
            return File(toolchain, normalized.substring(index + marker.length)).path
        }
        if (normalized.startsWith("/")) {
            throw IllegalStateException("Refusing symlink outside the toolchain: $raw")
        }
        return raw
    }

    private fun readFully(input: InputStream, buffer: ByteArray) {
        if (readFullyOrEof(input, buffer) != buffer.size) {
            throw IllegalStateException("Truncated archive.")
        }
    }

    private fun readFullyOrEof(input: InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) return offset
            offset += read
        }
        return offset
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var left = bytes
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped > 0) {
                left -= skipped
                continue
            }
            if (input.read() < 0) throw IllegalStateException("Truncated archive.")
            left--
        }
    }

    private class BoundedInputStream(
        private val source: InputStream,
        private var left: Long,
    ) : FilterInputStream(source) {
        override fun read(): Int {
            if (left <= 0) return -1
            val value = source.read()
            if (value >= 0) left--
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val read = source.read(b, off, minOf(len.toLong(), left).toInt())
            if (read > 0) left -= read
            return read
        }

        override fun close() = Unit
    }

    companion object {
        private const val BASE = "https://packages.termux.dev/apt/termux-main/"
        private const val KOTLIN_URL =
            "https://github.com/JetBrains/kotlin/releases/download/v2.0.21/kotlin-compiler-2.0.21.zip"
        private const val STAMP_NAME = "jdk-stamp"
        private const val STAMP = "openjdk-17.0.20 kotlin-2.0.21"
        private const val TIMEOUT_SECONDS = 600L
        private const val MAX_DOWNLOAD = 220L * 1024L * 1024L
        private const val MAX_UNPACKED = 700L * 1024L * 1024L
        private val AR_MAGIC = "!<arch>\n".toByteArray(Charsets.US_ASCII)
        private val DEBS = listOf(
            "pool/main/libc/libc++/libc++_29_aarch64.deb",
            "pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb",
            "pool/main/liba/libandroid-spawn/libandroid-spawn_0.3_aarch64.deb",
            "pool/main/z/zlib/zlib_1.3.2_aarch64.deb",
            "pool/main/l/littlecms/littlecms_2.19.1_aarch64.deb",
            "pool/main/libj/libjpeg-turbo/libjpeg-turbo_3.2.0_aarch64.deb",
            "pool/main/o/openjdk-17/openjdk-17_17.0.20_aarch64.deb",
        )
        private const val ALREADY =
            "OpenJDK 17 and kotlinc are already installed. Use javac, kotlinc, and java."
        private const val INSTALLED =
            "Installed OpenJDK 17 and Kotlin 2.0.21. java, javac, jar, and kotlinc are on PATH. " +
                "Example: kotlinc src/main.kt -include-runtime -d app.jar && java -jar app.jar. " +
                "This builds Java and Kotlin programs, not Android APKs."
    }
}
