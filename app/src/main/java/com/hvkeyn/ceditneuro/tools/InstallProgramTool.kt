package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.net.AgentNet
import com.hvkeyn.ceditneuro.shell.ProgramRun
import com.hvkeyn.ceditneuro.workspace.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Installs a compiler or other program into the app's private toolchain, the only
 * place Android will execute it. Shared storage is mounted noexec, so an existing
 * binary is copied here before it can run.
 */
class InstallProgramTool(
    private val workspace: Workspace,
    private val filesDir: File,
    private val toolchain: File,
    private val net: AgentNet,
    private val networkAllowed: () -> Boolean,
    private val ensureExec: suspend (String) -> Boolean,
) : Tool {

    private val bin = File(toolchain, "bin")

    override val name = "install_program"
    override val description =
        "Install a compiler or other program so the shell can run it by name. " +
            "Pass url for an http(s) file or zip, or source for a file or directory that already " +
            "exists in the project or on shared storage. The user is asked to allow this. " +
            "The program must be an Android aarch64 binary or a shell script. " +
            "Termux packages and ordinary Linux builds will not start. " +
            "Zip archives should contain a bin/ directory."
    override val parameters = objectSchema(
        properties = mapOf(
            "url" to stringProp("http(s) URL of a program file or zip. Omit when using source."),
            "source" to stringProp("Existing file or directory in the project or on shared storage."),
            "name" to stringProp("Program name in the toolchain bin directory. Defaults from the file name."),
        ),
        required = emptyList(),
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val url = args.stringArg("url")?.trim()?.ifBlank { null }
        val sourceArg = args.stringArg("source")?.trim()?.ifBlank { null }
        if (url == null && sourceArg == null) {
            return@withContext ToolResult.error("Pass url or source.")
        }
        if (url != null && sourceArg != null) {
            return@withContext ToolResult.error("Pass either url or source, not both.")
        }
        if (url != null && !networkAllowed()) {
            return@withContext ToolResult.error("Network is disabled in Settings.")
        }
        val allowed = ensureExec("Install and run a program inside this app.")
        if (!allowed) {
            return@withContext ToolResult.error(
                "The user did not allow running installed programs. They can turn it on in Settings.",
            )
        }
        bin.mkdirs()
        val staging = File(toolchain, ".staging").apply {
            deleteRecursively()
            mkdirs()
        }
        try {
            val payload = if (url != null) {
                download(url, staging)
            } else {
                copySource(sourceArg!!, staging)
            }
            val installed = unwrap(payload)
            val name = sanitize(args.stringArg("name") ?: installed.name.ifBlank { "program" })
                ?: return@withContext ToolResult.error("Program name must be letters, digits, dot, dash, or underscore.")
            place(installed, name)
            ToolResult.ok(
                "Installed $name into the toolchain. Run it by name. " +
                    "Binaries live in ${bin.absolutePath}. Shared storage cannot execute files.",
            )
        } catch (error: Exception) {
            ToolResult.error(error.message ?: "Install failed.")
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun download(url: String, staging: File): File {
        val fileName = url.substringBefore('?').substringAfterLast('/').ifBlank { "program" }
        val tmp = File(staging, "download.bin")
        net.download(url, tmp, MAX_DOWNLOAD)
        if (!isZip(tmp, fileName)) return tmp
        val extracted = File(staging, "extracted").apply { mkdirs() }
        unzip(tmp, extracted)
        tmp.delete()
        return extracted
    }

    private fun copySource(raw: String, staging: File): File {
        val source = resolveSource(raw)
        if (source.isFile) {
            val dest = File(staging, source.name)
            source.copyTo(dest, overwrite = true)
            return dest
        }
        val dest = File(staging, "payload")
        source.copyRecursively(dest, overwrite = true)
        return dest
    }

    private fun resolveSource(raw: String): File {
        val trimmed = raw.trim()
        val file = if (trimmed.startsWith("/")) File(trimmed) else workspace.resolve(trimmed)
        if (!file.exists()) throw IllegalArgumentException("Source does not exist: $raw")
        val path = file.canonicalPath
        val own = filesDir.canonicalPath
        val project = workspace.resolve("").canonicalPath
        val insideApp = path == own || path.startsWith(own + File.separator)
        val insideProject = path == project || path.startsWith(project + File.separator)
        val shared = path.startsWith("/storage/") || path.startsWith("/sdcard") || path.startsWith("/mnt/")
        if (!insideApp && !insideProject && !shared) {
            throw IllegalArgumentException("Source must be in the project or on shared storage.")
        }
        return file
    }

    private fun place(payload: File, name: String) {
        val root = unwrap(payload)
        if (root.isFile) {
            val dest = File(bin, name)
            root.copyTo(dest, overwrite = true)
            ProgramRun.markExecutable(dest)
            return
        }
        val nestedBin = File(root, "bin")
        if (nestedBin.isDirectory) {
            root.listFiles().orEmpty().forEach { child ->
                if (child.name == ".staging") return@forEach
                val dest = File(toolchain, child.name)
                if (child.isDirectory) child.copyRecursively(dest, overwrite = true)
                else child.copyTo(dest, overwrite = true)
            }
        } else {
            val dest = File(bin, name)
            root.copyRecursively(dest, overwrite = true)
        }
        ProgramRun.markTree(toolchain)
    }

    private fun unwrap(dir: File): File {
        if (!dir.isDirectory) return dir
        var current = dir
        while (true) {
            val children = current.listFiles()?.filter { it.name != ".part" }.orEmpty()
            when {
                children.size == 1 && children[0].isDirectory -> current = children[0]
                children.size == 1 && children[0].isFile -> return children[0]
                else -> return current
            }
        }
    }

    private fun isZip(file: File, fileName: String): Boolean {
        if (fileName.endsWith(".zip", ignoreCase = true)) return true
        val magic = ByteArray(2)
        file.inputStream().use { input -> if (input.read(magic) < 2) return false }
        return magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte()
    }

    private fun unzip(zip: File, dest: File) {
        val destRoot = dest.canonicalFile
        var count = 0
        var bytes = 0L
        ZipInputStream(zip.inputStream()).use { zipIn ->
            while (true) {
                val entry = zipIn.nextEntry ?: break
                if (count >= MAX_ENTRIES) throw IllegalStateException("Archive has too many entries.")
                val out = File(destRoot, entry.name).canonicalFile
                val rootPath = destRoot.path
                if (out.path != rootPath && !out.path.startsWith(rootPath + File.separator)) {
                    throw IllegalStateException("Archive entry escapes the toolchain: ${entry.name}")
                }
                if (entry.isDirectory) {
                    out.mkdirs()
                } else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val read = zipIn.read(buffer)
                            if (read < 0) break
                            bytes += read
                            if (bytes > MAX_UNPACKED) throw IllegalStateException("Unpacked archive is too large.")
                            output.write(buffer, 0, read)
                        }
                    }
                    count++
                }
                zipIn.closeEntry()
            }
        }
    }

    private fun sanitize(name: String): String? {
        val base = name.trim().replace('\\', '/').substringAfterLast('/')
        val cleaned = if (base.endsWith(".zip", ignoreCase = true)) base.dropLast(4) else base
        if (!cleaned.matches(SAFE_NAME)) return null
        return cleaned
    }

    companion object {
        private val SAFE_NAME = Regex("[A-Za-z0-9._-]{1,80}")
        private const val MAX_ENTRIES = 4_000
        private const val MAX_DOWNLOAD = 256L * 1024L * 1024L
        private const val MAX_UNPACKED = 512L * 1024L * 1024L
    }
}
