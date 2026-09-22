package com.hvkeyn.ceditneuro.workspace

import java.io.File

data class FileEntry(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val size: Long,
)

/**
 * A project root plus the path-safety rules the agent tools rely on. Every path that
 * reaches the filesystem goes through [resolve], which rejects escapes outside the root.
 */
class Workspace(val root: File) {

    private val canonicalRoot: File = root.canonicalFile

    init {
        require(canonicalRoot.isDirectory) { "Not a directory: ${canonicalRoot.path}" }
    }

    fun resolve(relativePath: String): File {
        val normalized = relativePath.trim().replace('\\', '/').trimStart('/')
        val candidate = if (normalized.isEmpty()) canonicalRoot else File(canonicalRoot, normalized).canonicalFile
        val rootPath = canonicalRoot.path
        require(candidate.path == rootPath || candidate.path.startsWith(rootPath + File.separator)) {
            "Path escapes the workspace: $relativePath"
        }
        return candidate
    }

    fun relativize(file: File): String =
        file.canonicalFile.toRelativeString(canonicalRoot).replace('\\', '/')

    fun children(relativePath: String = ""): List<FileEntry> {
        val dir = resolve(relativePath)
        if (!dir.isDirectory) return emptyList()
        return dir.listFiles()
            .orEmpty()
            .filterNot { it.name in IGNORED_NAMES }
            .map { child ->
                FileEntry(
                    name = child.name,
                    relativePath = relativize(child),
                    isDirectory = child.isDirectory,
                    size = if (child.isFile) child.length() else 0L,
                )
            }
            .sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() }))
    }

    fun read(relativePath: String): String = resolve(relativePath).readText()

    fun write(relativePath: String, content: String) {
        val file = resolve(relativePath)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    companion object {
        /** Directories that are never worth showing to the user or the agent. */
        val IGNORED_NAMES = setOf(
            ".git", ".gradle", ".idea", "build", "node_modules", "__pycache__", ".ceditneuro",
        )

        const val MAX_SEARCHABLE_FILE_BYTES = 1_000_000L
    }
}
