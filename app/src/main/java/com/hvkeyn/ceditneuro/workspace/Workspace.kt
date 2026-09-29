package com.hvkeyn.ceditneuro.workspace

import java.io.File

data class FileEntry(
    val name: String,
    val relativePath: String,
    val isDirectory: Boolean,
    val size: Long,
)

/**
 * Project root plus path rules for the agent. Relative paths stay inside the project.
 * Absolute paths are real filesystem paths: shared storage, this app's files, and
 * anything else Android lets the shell on this phone open, another app's data included.
 */
class Workspace(val root: File) {

    private val canonicalRoot: File = root.canonicalFile

    init {
        require(canonicalRoot.isDirectory) { "Not a directory: ${canonicalRoot.path}" }
    }

    fun resolve(path: String): File {
        val normalized = path.trim().replace('\\', '/')
        require(!normalized.contains('\u0000')) { "Path contains NUL." }
        return when {
            normalized.isEmpty() -> canonicalRoot
            normalized.startsWith("/") -> StoragePaths.absolute(normalized)
            else -> StoragePaths.finish(File(canonicalRoot, normalized))
        }
    }

    fun relativize(file: File): String {
        val canonical = file.canonicalFile
        val rootPath = canonicalRoot.path
        val path = canonical.path
        return if (path == rootPath || path.startsWith(rootPath + File.separator)) {
            canonical.toRelativeString(canonicalRoot).replace('\\', '/')
        } else {
            path
        }
    }

    fun children(relativePath: String = ""): List<FileEntry> {
        val dir = resolve(relativePath)
        if (!dir.exists()) throw IllegalArgumentException("No such path: $relativePath")
        if (!dir.isDirectory) throw IllegalArgumentException("Not a directory: $relativePath")
        val listed = dir.listFiles() ?: throw IllegalArgumentException("Cannot list $relativePath")
        val insideProject = dir.path == canonicalRoot.path || dir.path.startsWith(canonicalRoot.path + File.separator)
        return listed
            .filterNot { insideProject && it.name in IGNORED_NAMES }
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
        val parent = file.parentFile
        if (parent != null && !parent.exists() && !parent.mkdirs()) {
            throw java.io.IOException("Could not create ${parent.absolutePath}")
        }
        file.writeText(content)
        if (!file.isFile) throw java.io.IOException("Nothing was written to ${file.absolutePath}")
        StoragePaths.scan(file)
    }

    companion object {
        /** Directories that are never worth showing to the user or the agent. */
        val IGNORED_NAMES = setOf(
            ".git", ".gradle", ".idea", "build", "node_modules", "__pycache__", ".ceditneuro",
        )

        const val MAX_SEARCHABLE_FILE_BYTES = 1_000_000L
    }
}
