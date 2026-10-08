package com.hvkeyn.ceditneuro.reader

import java.io.File

/** Finds a note the way a vault does: by name, by path, then by the folder it was linked from. */
object NoteVault {
    sealed class Hit {
        data class One(val file: File) : Hit()
        data class Many(val names: List<String>) : Hit()
        data object Missing : Hit()
    }

    fun resolve(root: File, from: File, target: String): Hit {
        val raw = target.trim().removePrefix("./")
        val clean = raw.trimEnd('.', ',', ';', ':', '!', '?', ')', '"', '»')
        if (clean.isEmpty()) return Hit.One(from)
        val given = File(clean)
        if (given.isFile && inside(root, given)) return Hit.One(given.canonicalFile)
        val stemPath = noteStem(clean)
        val direct = listOf(
            File(from.parentFile, clean),
            File(from.parentFile, "$stemPath.md"),
            File(root, clean),
            File(root, "$stemPath.md"),
        ).firstOrNull { it.isFile && inside(root, it) }
        if (direct != null) return Hit.One(direct.canonicalFile)

        val want = stemPath.replace('\\', '/').trim('/').lowercase()
        val leaf = want.substringAfterLast('/')
        val matches = index(root).files.filter { file ->
            val relative = relative(root, file).substringBeforeLast('.').lowercase()
            relative == want || relative.substringAfterLast('/') == leaf ||
                file.nameWithoutExtension.lowercase() == leaf
        }
        val sameFolder = matches.filter { it.parentFile?.canonicalPath == from.parentFile?.canonicalPath }
        if (sameFolder.size == 1) return Hit.One(sameFolder[0])
        if (matches.size == 1) return Hit.One(matches[0])
        if (matches.isEmpty()) return Hit.Missing
        return Hit.Many(matches.map { relative(root, it) }.distinct().take(6))
    }

    /** -1 when the heading is not in this book. */
    fun pageForHeading(pages: List<BookPage>, heading: String): Int {
        val want = heading.trim()
        if (want.isEmpty()) return 0
        val block = want.removePrefix("^")
        pages.forEachIndexed { index, page ->
            if (page.chapter.equals(want, ignoreCase = true)) return index
            val hit = page.text.lineSequence().any { raw ->
                val line = BookText.spoken(raw).trim().trimStart('#', ' ')
                line.equals(want, ignoreCase = true) ||
                    (want.startsWith("^") && raw.contains("^$block"))
            }
            if (hit) return index
        }
        return -1
    }

    /** -1 when the heading is not in this text. The number is a character offset, not a screen page. */
    fun anchorForHeading(source: String, heading: String): Int {
        val want = heading.trim()
        if (want.isEmpty()) return 0
        val block = want.removePrefix("^")
        var index = 0
        for (line in source.lineSequence()) {
            val shown = BookText.spoken(line).trim().trimStart('#', ' ')
            if (shown.equals(want, ignoreCase = true) || (want.startsWith("^") && line.contains("^$block"))) {
                return index
            }
            index += line.length + 1
        }
        return -1
    }

    fun mentions(root: File, note: File): List<Pair<String, String>> {
        val stem = note.nameWithoutExtension.lowercase()
        if (stem.isBlank()) return emptyList()
        val self = relative(root, note)
        return index(root).mentions[stem].orEmpty().filter { it.second != self }.take(24)
    }

    private data class Index(
        val root: String,
        val files: List<File>,
        val mentions: Map<String, List<Pair<String, String>>>,
    )

    @Volatile
    private var cached: Index? = null

    private fun index(root: File): Index {
        val key = root.canonicalPath
        cached?.takeIf { it.root == key }?.let { return it }
        val files = ArrayList<File>()
        walk(root, root, 0, files)
        val mentions = HashMap<String, MutableList<Pair<String, String>>>()
        val link = Regex("\\[\\[\\s*([^\\]#|]+)")
        for (file in files) {
            if (file.length() > 400_000) continue
            val text = runCatching { file.readText(Charsets.UTF_8).take(60_000) }.getOrNull() ?: continue
            val fromName = file.nameWithoutExtension
            val fromPath = relative(root, file)
            for (match in link.findAll(text)) {
                val stem = noteStem(match.groupValues[1]).lowercase()
                if (stem.isBlank() || stem == fromName.lowercase()) continue
                val list = mentions.getOrPut(stem) { ArrayList() }
                if (list.none { it.second == fromPath } && list.size < 24) list += fromName to fromPath
            }
        }
        return Index(key, files, mentions).also { cached = it }
    }

    private fun noteStem(raw: String): String {
        var name = raw.trim().removePrefix("./").replace('\\', '/').trim('/')
        name = name.trimEnd('.', ',', ';', ':', '!', '?', ')', '"', '»')
        val lower = name.lowercase()
        for (ext in listOf("markdown", "notes", "note", "txt", "md")) {
            if (lower.endsWith(".$ext")) {
                name = name.dropLast(ext.length + 1)
                break
            }
        }
        return name.trim()
    }

    private fun walk(root: File, dir: File, depth: Int, out: MutableList<File>) {
        if (depth > 24 || out.size >= 8_000) return
        val children = dir.listFiles() ?: return
        for (child in children) {
            if (child.name.startsWith(".")) continue
            if (child.isDirectory) {
                if (child.name in SKIP) continue
                walk(root, child, depth + 1, out)
            } else if (child.isFile && child.extension.lowercase() in NOTE_EXT && child.length() <= 1_500_000) {
                out += child
            }
        }
    }

    private fun inside(root: File, file: File): Boolean {
        val base = root.canonicalPath
        val path = file.canonicalPath
        return path == base || path.startsWith(base + File.separator)
    }

    private fun relative(root: File, file: File): String {
        val base = root.canonicalFile
        val path = file.canonicalFile
        val text = path.path
        val rootPath = base.path
        return if (text == rootPath || text.startsWith(rootPath + File.separator)) {
            path.relativeTo(base).invariantSeparatorsPath
        } else {
            path.name
        }
    }

    private val NOTE_EXT = setOf("md", "markdown", "txt", "note", "notes")
    private val SKIP = setOf(".git", ".gradle", ".idea", "build", "node_modules", "__pycache__", ".ceditneuro")
}
