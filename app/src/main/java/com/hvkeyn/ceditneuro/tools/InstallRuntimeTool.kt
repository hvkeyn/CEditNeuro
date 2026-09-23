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

/**
 * Downloads Android builds of git or CPython into the private toolchain.
 * The packages are Termux aarch64 debs. Their libraries use DT_RUNPATH, so the
 * shell's LD_LIBRARY_PATH is what makes them start under this app.
 */
class InstallRuntimeTool(
    private val toolchain: File,
    private val net: AgentNet,
    private val networkAllowed: () -> Boolean,
    private val ensureExec: suspend (String) -> Boolean,
) : Tool {
    override val name = "install_runtime"
    override val description =
        "Download an Android aarch64 program into this app. name is git or python. " +
            "Call it once, then use git or python from run_command. " +
            "These are not Linux or Termux-path binaries; they run from this app's toolchain."
    override val parameters = objectSchema(
        properties = mapOf(
            "name" to stringProp("git or python."),
        ),
        required = listOf("name"),
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val name = args.stringArg("name")?.trim()?.lowercase().orEmpty()
        val spec = RUNTIMES[name]
            ?: return@withContext ToolResult.error("Unknown runtime '$name'. Use git or python.")
        if (alreadyInstalled(spec)) {
            return@withContext ToolResult.ok(spec.already)
        }
        if (!networkAllowed()) {
            return@withContext ToolResult.error("Agent network is off. Turn on Agent network in Settings.")
        }
        val allowed = ensureExec("Download and run ${spec.title} inside this app.")
        if (!allowed) {
            return@withContext ToolResult.error(
                "The user did not allow running installed programs. They can turn it on in Settings.",
            )
        }
        val staging = File(toolchain, ".runtime-staging").apply {
            deleteRecursively()
            mkdirs()
        }
        val unpacked = longArrayOf(0L)
        try {
            for (path in spec.debs) {
                val deb = File(staging, path.substringAfterLast('/').replace(':', '_'))
                TermuxRepo.download(net, path, deb, MAX_DOWNLOAD, TIMEOUT_SECONDS)
                extractDeb(deb, toolchain, unpacked)
                deb.delete()
            }
            markPrograms()
            File(toolchain, spec.stampName).writeText(spec.stamp)
            ToolResult.ok(spec.installed)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ToolResult.error(error.message ?: "Runtime install failed.")
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun alreadyInstalled(spec: RuntimeSpec): Boolean {
        val stamp = File(toolchain, spec.stampName)
        return stamp.isFile && stamp.readText() == spec.stamp && File(toolchain, spec.binary).isFile
    }

    private fun markPrograms() {
        listOf("bin", "libexec").forEach { dirName ->
            val dir = File(toolchain, dirName)
            if (!dir.isDirectory) return@forEach
            dir.walkTopDown().forEach { file ->
                if (file.isFile) ProgramRun.markExecutable(file)
            }
        }
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
                if (read < 60) {
                    throw IllegalStateException(
                        "Truncated Debian package: ${deb.name} (${deb.length()} bytes, header read $read)",
                    )
                }
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
        while (true) {
            val entry = tar.nextEntry ?: break
            val rel = toolchainRelative(entry.name) ?: continue
            if (skipEntry(rel)) continue
            val path = ArchivePaths.output(destRoot, rel)
            when {
                entry.isDirectory -> ArchivePaths.directory(path)
                entry.isSymbolicLink -> ArchivePaths.symlink(path, toolchain, entry.linkName)
                entry.isFile -> {
                    ArchivePaths.openNewFile(path).use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val read = tar.read(buffer)
                            if (read < 0) break
                            unpacked[0] += read
                            if (unpacked[0] > MAX_UNPACKED) {
                                throw IllegalStateException("Unpacked runtime is too large.")
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
            }
        }
    }

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
        rel.startsWith("include/") ||
            rel.startsWith("share/doc/") ||
            rel.startsWith("share/man/") ||
            rel.startsWith("share/info/") ||
            rel.startsWith("share/locale/") ||
            rel.startsWith("share/bash-completion/")

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

        override fun skip(n: Long): Long = drain(n)

        override fun close() {
            drain(left)
        }

        private fun drain(n: Long): Long {
            if (n <= 0 || left <= 0) return 0
            var remaining = minOf(n, left)
            var skipped = 0L
            val buf = ByteArray(8192)
            while (remaining > 0) {
                val read = read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (read < 0) break
                skipped += read
                remaining -= read
            }
            return skipped
        }
    }

    private class RuntimeSpec(
        val title: String,
        val stampName: String,
        val stamp: String,
        val binary: String,
        val debs: List<String>,
        val already: String,
        val installed: String,
    )

    companion object {
        private const val TIMEOUT_SECONDS = 600L
        private const val MAX_DOWNLOAD = 80L * 1024L * 1024L
        private const val MAX_UNPACKED = 400L * 1024L * 1024L
        private val AR_MAGIC = "!<arch>\n".toByteArray(Charsets.US_ASCII)

        private val GIT_DEBS = listOf(
            "pool/main/z/zlib/zlib_1.3.2_aarch64.deb",
            "pool/main/p/pcre2/pcre2_10.47_aarch64.deb",
            "pool/main/o/openssl/openssl_1:3.6.3_aarch64.deb",
            "pool/main/c/ca-certificates/ca-certificates_1:2026.08.13_all.deb",
            "pool/main/l/less/less_710_aarch64.deb",
            "pool/main/n/ncurses/ncurses_6.6.20260307+really6.5.20250830_aarch64.deb",
            "pool/main/libi/libiconv/libiconv_1.19_aarch64.deb",
            "pool/main/libe/libexpat/libexpat_2.8.5_aarch64.deb",
            "pool/main/libc/libcurl/libcurl_8.22.0_aarch64.deb",
            "pool/main/libs/libssh2/libssh2_1.11.1-2_aarch64.deb",
            "pool/main/libn/libngtcp2/libngtcp2_1.25.0_aarch64.deb",
            "pool/main/libn/libnghttp3/libnghttp3_1.18.0_aarch64.deb",
            "pool/main/libn/libnghttp2/libnghttp2_1.70.0_aarch64.deb",
            "pool/main/g/git/git_2.55.0_aarch64.deb",
        )

        private val PYTHON_DEBS = listOf(
            "pool/main/z/zstd/zstd_1.5.7-1_aarch64.deb",
            "pool/main/z/zlib/zlib_1.3.2_aarch64.deb",
            "pool/main/libl/liblzma/liblzma_5.8.4_aarch64.deb",
            "pool/main/r/readline/readline_8.3.6_aarch64.deb",
            "pool/main/n/ncurses/ncurses_6.6.20260307+really6.5.20250830_aarch64.deb",
            "pool/main/liba/libandroid-support/libandroid-support_29-1_aarch64.deb",
            "pool/main/o/openssl/openssl_1:3.6.3_aarch64.deb",
            "pool/main/c/ca-certificates/ca-certificates_1:2026.08.13_all.deb",
            "pool/main/n/ncurses-ui-libs/ncurses-ui-libs_6.6.20260307+really6.5.20250830_aarch64.deb",
            "pool/main/libs/libsqlite/libsqlite_3.53.4_aarch64.deb",
            "pool/main/libf/libffi/libffi_3.8.0_aarch64.deb",
            "pool/main/libe/libexpat/libexpat_2.8.5_aarch64.deb",
            "pool/main/libc/libcrypt/libcrypt_0.2-6_aarch64.deb",
            "pool/main/libb/libbz2/libbz2_1.0.8-8_aarch64.deb",
            "pool/main/liba/libandroid-posix-semaphore/libandroid-posix-semaphore_0.1-4_aarch64.deb",
            "pool/main/g/gdbm/gdbm_1.26-1_aarch64.deb",
            "pool/main/p/python/python_3.14.6-1_aarch64.deb",
        )

        private val RUNTIMES = mapOf(
            "git" to RuntimeSpec(
                title = "git",
                stampName = "runtime-git",
                stamp = "git-2.55.0",
                binary = "bin/git",
                debs = GIT_DEBS,
                already = "git 2.55.0 is already installed. Use it from run_command.",
                installed = "Installed git 2.55.0. It is on PATH in run_command.",
            ),
            "python" to RuntimeSpec(
                title = "Python",
                stampName = "runtime-python",
                stamp = "python-3.14.6",
                binary = "bin/python3.14",
                debs = PYTHON_DEBS,
                already = "Python 3.14.6 is already installed. Use python from run_command.",
                installed = "Installed Python 3.14.6. python and python3 are on PATH in run_command.",
            ),
        )
    }
}
