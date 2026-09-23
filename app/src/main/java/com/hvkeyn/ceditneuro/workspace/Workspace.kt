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
 * anything else Android lets this app open. Other apps' private data stays blocked.
 */
class Workspace(val root: File) {

    private val canonicalRoot: File = root.canonicalFile

    init {
        require(canonicalRoot.isDirectory) { "Not a directory: ${canonicalRoot.path}" }
    }

    fun resolve(path: String): File {
        val normalized = path.trim().replace('\\', '/')
        require(!normalized.contains('\u0000')) { "Path contains NUL." }
        val candidate = when {
            normalized.isEmpty() -> canonicalRoot
            normalized.startsWith("/") -> File(normalized).canonicalFile
            else -> File(canonicalRoot, normalized).canonicalFile
        }
        require(isReachable(candidate)) {
            "This app cannot use $path. Other apps' private data and root-only paths stay closed."
        }
        return candidate
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

    private fun isReachable(file: File): Boolean {
        val path = file.path
        if (path == "/" || path == "/data" || path == "/data/local" || path == "/data/local/tmp") return true
        if (isForeignPrivateData(path)) return false
        return true
    }

    private fun isForeignPrivateData(path: String): Boolean {
        val prefixes = listOf("/data/data/", "/data/user/0/", "/data/user_de/0/")
        val prefix = prefixes.firstOrNull { path.startsWith(it) } ?: return false
        val rest = path.removePrefix(prefix)
        val packageName = rest.substringBefore('/')
        if (packageName.isEmpty()) return false
        return packageName != "com.hvkeyn.ceditneuro" && packageName != "com.hvkeyn.ceditneuro.debug"
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
