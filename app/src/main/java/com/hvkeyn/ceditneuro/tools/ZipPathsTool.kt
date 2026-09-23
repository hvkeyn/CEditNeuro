package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.workspace.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Packs files into a zip. The device shell has tar and unzip, and no zip program. */
class ZipPathsTool(private val workspace: Workspace) : Tool {
    override val name = "zip_paths"
    override val description =
        "Pack files or directories into a zip archive. paths is one path per line. " +
            "dest is the zip file to create. Paths may be project-relative or absolute."
    override val parameters = objectSchema(
        properties = mapOf(
            "dest" to stringProp("Zip file to create."),
            "paths" to stringProp("Files or directories to include, one path per line."),
        ),
        required = listOf("dest", "paths"),
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val destArg = args.stringArg("dest")?.trim().orEmpty()
        val rawPaths = args.stringArg("paths").orEmpty()
        if (destArg.isEmpty()) return@withContext ToolResult.error("Missing 'dest'.")
        val requested = rawPaths.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.toList()
        if (requested.isEmpty()) return@withContext ToolResult.error("Missing 'paths'.")
        val dest = runCatching { workspace.resolve(destArg) }.getOrElse { error ->
            return@withContext ToolResult.error(error.message ?: "Invalid dest.")
        }
        val files = mutableListOf<Pair<File, String>>()
        for (path in requested) {
            val file = runCatching { workspace.resolve(path) }.getOrElse { error ->
                return@withContext ToolResult.error(error.message ?: "Invalid path.")
            }
            if (!file.exists()) return@withContext ToolResult.error("No such path: $path")
            collect(file, file, files)
            if (files.size > MAX_ENTRIES) {
                return@withContext ToolResult.error("Too many files. Limit is $MAX_ENTRIES.")
            }
        }
        dest.parentFile?.mkdirs()
        var bytes = 0L
        ZipOutputStream(dest.outputStream()).use { zip ->
            for ((file, name) in files) {
                if (file.canonicalFile == dest.canonicalFile) continue
                val entry = ZipEntry(name)
                zip.putNextEntry(entry)
                file.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        bytes += read
                        if (bytes > MAX_BYTES) {
                            return@withContext ToolResult.error("Zip would exceed $MAX_BYTES bytes.")
                        }
                        zip.write(buffer, 0, read)
                    }
                }
                zip.closeEntry()
            }
        }
        ToolResult.ok("Wrote ${dest.path} (${files.size} file(s), $bytes bytes).")
    }

    private fun collect(root: File, file: File, out: MutableList<Pair<File, String>>) {
        if (file.isDirectory) {
            file.listFiles()?.forEach { collect(root, it, out) }
            return
        }
        if (!file.isFile) return
        val name = root.toPath().relativize(file.toPath()).toString().replace('\\', '/')
            .ifBlank { file.name }
        out += file to name
    }

    companion object {
        private const val MAX_ENTRIES = 400
        private const val MAX_BYTES = 40L * 1024L * 1024L
    }
}
