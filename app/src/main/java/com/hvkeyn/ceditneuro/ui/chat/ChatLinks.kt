package com.hvkeyn.ceditneuro.ui.chat

import java.io.File

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

    /** A file the chat can draw or open. Web pages stay in the browser. */
    fun file(projectRoot: String?, raw: String): File? {
        val path = target(raw) ?: return null
        if (isWeb(path)) return null
        val file = if (path.startsWith("/")) File(path) else {
            val root = projectRoot?.takeIf { it.isNotBlank() } ?: return null
            File(root, path)
        }
        return file.takeIf { it.isFile }
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
