package com.hvkeyn.ceditneuro.ui.chat

import java.io.File
import java.util.ArrayDeque

/**
 * What a tap in the agent chat should open: a project file, a book, or a web page.
 * Relative paths stay beside the open project. A word that is not a file stays text.
 */
object ChatLinks {
    private val extensions = setOf(
        "md", "markdown", "txt", "pdf", "svg", "html", "htm",
        "png", "jpg", "jpeg", "webp", "gif", "bmp",
        "fb2", "epub", "fbz", "json", "csv", "xml", "py", "kt", "sh",
    )

    /** Project path, absolute path, or http(s) URL. Null when [raw] is ordinary text. */
    fun target(raw: String): String? {
        var value = raw.trim().trim('<', '>', '"', '\'')
        if (value.isEmpty() || value.length > 500) return null
        if (value.startsWith("http://") || value.startsWith("https://")) {
            return value.substringBefore(' ').trimEnd('.', ',', ';', ':', ')')
        }
        value = value.removePrefix("file://").replace('\\', '/')
        if (value.any { it.isWhitespace() }) return null
        val path = value.substringBefore('?').substringBefore('#')
        if (path.contains("://")) return null
        val name = path.substringAfterLast('/')
        val ext = name.substringAfterLast('.', "").lowercase()
        val local = path.startsWith("/") || ext in extensions
        if (!local) return null
        return path
    }

    fun isWeb(target: String): Boolean =
        target.startsWith("http://") || target.startsWith("https://")

    fun isSvgPath(target: String): Boolean =
        !isWeb(target) && target.substringBefore('?').substringBefore('#').endsWith(".svg", ignoreCase = true)

    /**
     * A file the chat can draw or open. A bare name such as `scheme.svg` is found
     * anywhere under the project, because notes name the file without its folder.
     */
    fun file(projectRoot: String?, raw: String): File? {
        val path = target(raw) ?: return null
        if (isWeb(path)) return null
        val root = projectRoot?.takeIf { it.isNotBlank() }?.let { File(it) }
        val direct = if (path.startsWith("/")) File(path) else {
            if (root == null) return null
            File(root, path)
        }
        if (direct.isFile) return direct
        if (root == null || path.startsWith("/")) return null
        return findNamed(root, File(path).name)
    }

    private fun findNamed(root: File, name: String): File? {
        if (name.isBlank() || !root.isDirectory) return null
        val queue = ArrayDeque<Pair<File, Int>>()
        queue.add(root to 0)
        var seen = 0
        while (queue.isNotEmpty() && seen < 4_000) {
            val (dir, depth) = queue.removeFirst()
            val children = dir.listFiles() ?: continue
            for (child in children) {
                seen++
                if (child.isFile && child.name.equals(name, ignoreCase = true)) return child
            }
            if (depth >= 8) continue
            for (child in children) {
                if (child.isDirectory && child.name != ".git" && child.name != "build") {
                    queue.add(child to depth + 1)
                }
            }
        }
        return null
    }

    /** The agent host could not be reached, so another send would fail the same way. */
    fun looksOffline(message: String): Boolean {
        val lower = message.lowercase()
        return "unable to resolve host" in lower ||
            "no address associated" in lower ||
            "network is unreachable" in lower ||
            "enetunreach" in lower ||
            "no network" in lower ||
            "offline" in lower && "agent" in lower
    }
}
