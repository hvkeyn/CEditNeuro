package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.net.AgentNet
import com.hvkeyn.ceditneuro.workspace.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.zip.ZipInputStream

/**
 * Downloads a file or zip into the project. This is how the agent installs
 * libraries and source modules without a system package manager.
 * Android will not execute a binary that was just written here.
 */
class InstallModuleTool(
    private val workspace: Workspace,
    private val net: AgentNet,
    private val allowed: () -> Boolean,
    private val onInstalled: (String) -> Unit = {},
) : Tool {

    override val name = "install_module"
    override val description =
        "Download a module into the project. Zip archives are unpacked; any other file is saved as-is. " +
            "Use it for libraries, sources, and assets from http(s) URLs. " +
            "Downloaded binaries cannot be executed on this Android version."
    override val parameters = objectSchema(
        properties = mapOf(
            "url" to stringProp("http(s) URL of a file or .zip archive."),
            "name" to stringProp("Module folder name. Defaults from the URL file name."),
            "dest" to stringProp("Project path to install into. Defaults to modules/<name>."),
        ),
        required = listOf("url"),
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        if (!allowed()) return@withContext ToolResult.error("Network is disabled in Settings.")
        val url = args.stringArg("url") ?: return@withContext ToolResult.error("Missing 'url'.")
        val fileName = url.substringBefore('?').substringAfterLast('/').ifBlank { "module" }
        val name = sanitize(args.stringArg("name") ?: fileName.substringBeforeLast('.').ifBlank { fileName })
            ?: return@withContext ToolResult.error("Module name must be letters, digits, dot, dash, or underscore.")
        val destRelative = args.stringArg("dest")?.trim()?.trim('/')?.ifBlank { null } ?: "modules/$name"
        val dest = runCatching { workspace.resolve(destRelative) }
            .getOrElse { return@withContext ToolResult.error(it.message ?: "Bad dest.") }
        if (dest.isFile) return@withContext ToolResult.error("$destRelative is a file.")
        dest.mkdirs()

        val tmp = File.createTempFile("module-", ".bin", dest.parentFile)
        try {
            runCatching { net.download(url, tmp, AgentNet.MAX_DOWNLOAD_BYTES) }
                .getOrElse {
                    tmp.delete()
                    return@withContext ToolResult.error(it.message ?: "Download failed.")
                }
            val unpacked = if (isZip(tmp, fileName)) {
                unzip(tmp, dest)
            } else {
                val target = File(dest, sanitize(fileName) ?: "download.bin")
                tmp.copyTo(target, overwrite = true)
                1
            }
            onInstalled(destRelative)
            ToolResult.ok("Installed $unpacked item(s) into $destRelative from $url")
        } finally {
            tmp.delete()
        }
    }

    private fun isZip(file: File, fileName: String): Boolean {
        if (fileName.endsWith(".zip", ignoreCase = true)) return true
        val magic = ByteArray(2)
        file.inputStream().use { input ->
            if (input.read(magic) < 2) return false
        }
        return magic[0] == 0x50.toByte() && magic[1] == 0x4B.toByte()
    }

    private fun unzip(zip: File, dest: File): Int {
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
                    throw IllegalStateException("Archive entry escapes the module folder: ${entry.name}")
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
        return count
    }

    private fun sanitize(name: String): String? {
        val cleaned = name.trim().replace('\\', '/').substringAfterLast('/')
        if (cleaned.isEmpty() || cleaned == "." || cleaned == ".." || cleaned.length > 80) return null
        if (!cleaned.matches(SAFE_NAME)) return null
        return cleaned
    }

    companion object {
        private val SAFE_NAME = Regex("[A-Za-z0-9._-]{1,80}")
        private const val MAX_ENTRIES = 2_000
        private const val MAX_UNPACKED = 64L * 1024L * 1024L
    }
}
