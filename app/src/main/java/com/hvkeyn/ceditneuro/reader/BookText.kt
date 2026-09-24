package com.hvkeyn.ceditneuro.reader

import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.util.zip.ZipInputStream

data class BookChapter(val title: String, val text: String)

data class ParsedBook(
    val title: String,
    val chapters: List<BookChapter>,
    val images: Map<String, ByteArray> = emptyMap(),
)

data class BookPage(val chapter: String, val text: String)

object BookText {
    private val extensions = setOf("txt", "md", "markdown", "note", "notes", "html", "htm", "fb2", "epub")
    private val readable = extensions + setOf("zip", "fbz")

    fun supports(name: String): Boolean {
        val lower = name.lowercase()
        return extension(lower) in readable || lower.endsWith(".fb2.zip")
    }

    /** FB2, EPUB, and a zipped FB2 open in the reader. Plain notes stay editable. */
    fun prefersReader(name: String): Boolean {
        val lower = name.lowercase()
        val ext = extension(lower)
        return ext == "fb2" || ext == "epub" || ext == "fbz" || lower.endsWith(".fb2.zip")
    }

    /** A zip of notes and books, as opposed to an arbitrary archive. */
    fun looksLikeBooks(file: java.io.File): Boolean {
        if (!file.isFile || file.length() > MAX_BYTES) return false
        return runCatching {
            java.util.zip.ZipFile(file).use { zip ->
                zip.entries().asSequence().any { entry -> readableName(entry.name) }
            }
        }.getOrDefault(false)
    }

    fun parse(name: String, bytes: ByteArray): ParsedBook {
        if (bytes.size > MAX_BYTES) error("This file is larger than 8 MB.")
        val book = when {
            isZip(bytes) -> parseContainer(bytes, name, 0)
            extension(name) == "html" || extension(name) == "htm" -> htmlBook(name, String(bytes, charset(bytes)))
            extension(name) == "md" || extension(name) == "markdown" -> markdownBook(name, String(bytes, charset(bytes)))
            extension(name) == "fb2" -> parseFb2(bytes, name)
            else -> plainBook(name, String(bytes, charset(bytes)))
        }
        return book.copy(
            title = polish(book.title),
            chapters = book.chapters.map { it.copy(title = polish(it.title), text = polish(it.text)) },
            images = book.images,
        )
    }

    /** Turns Markdown, wiki links, and pipe tables into lines meant to be read. */
    fun polish(text: String): String {
        var value = text.replace("\r\n", "\n")
        value = Regex("\\[\\[([^\\]|]+)\\|([^\\]]+)]]").replace(value) { it.groupValues[2].trim() }
        value = Regex("\\[\\[([^\\]]+)]]").replace(value) {
            it.groupValues[1].substringAfterLast('/').substringAfterLast('|').trim()
        }
        value = Regex("!\\[[^\\]]*]\\([^)]*\\)").replace(value, "")
        value = Regex("\\[([^\\]]+)]\\([^)]*\\)").replace(value) { it.groupValues[1] }
        value = Regex("`{1,3}([^`]*)`{1,3}").replace(value) { it.groupValues[1] }
        value = value.replace("**", "").replace("__", "").replace("~~", "")
        return value.lineSequence().joinToString("\n") { line ->
            val trimmed = line.trim()
            when {
                trimmed.startsWith("|") && trimmed.contains("---") -> ""
                trimmed.startsWith("|") && trimmed.endsWith("|") ->
                    trimmed.trim('|').split('|').joinToString("  ·  ") { it.trim() }
                trimmed.startsWith(">") -> trimmed.trimStart('>', ' ')
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> "• ${trimmed.drop(2)}"
                else -> line
            }
        }.replace(Regex("\n{3,}"), "\n\n").trim()
    }

    fun pages(book: ParsedBook, charsPerPage: Int): List<BookPage> {
        val size = charsPerPage.coerceIn(400, 4_000)
        val pages = ArrayList<BookPage>()
        for (chapter in book.chapters) {
            val body = chapter.text.trim()
            if (body.isEmpty()) continue
            var start = 0
            while (start < body.length) {
                var end = (start + size).coerceAtMost(body.length)
                if (end < body.length) {
                    val breakAt = body.lastIndexOf("\n\n", end).takeIf { it > start + size / 3 }
                        ?: body.lastIndexOf(' ', end).takeIf { it > start + size / 2 }
                    if (breakAt != null) end = breakAt
                }
                val slice = body.substring(start, end).trim()
                if (slice.isNotEmpty()) pages.add(BookPage(chapter.title, slice))
                start = end
                while (start < body.length && body[start].isWhitespace()) start++
            }
        }
        if (pages.isEmpty()) pages.add(BookPage(book.title, "(empty)"))
        return pages
    }

    private fun plainBook(name: String, text: String): ParsedBook {
        val title = name.substringBeforeLast('.').ifBlank { name }
        return ParsedBook(title, listOf(BookChapter(title, text.trim())))
    }

    private fun markdownBook(name: String, text: String): ParsedBook {
        val title = text.lineSequence().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()
            ?: name.substringBeforeLast('.')
        val chapters = ArrayList<BookChapter>()
        val body = StringBuilder()
        var current = title
        fun flush() {
            val value = body.toString().trim()
            body.clear()
            if (value.isNotEmpty()) chapters.add(BookChapter(current, value))
        }
        for (line in text.lineSequence()) {
            if (line.startsWith("# ")) {
                flush()
                current = line.trimStart('#').trim().ifBlank { current }
            } else {
                body.append(line.replace(Regex("^#{2,6}\\s+"), "")).append('\n')
            }
        }
        flush()
        if (chapters.isEmpty()) chapters.add(BookChapter(title, text.trim()))
        return ParsedBook(title, chapters)
    }

    private fun htmlBook(
        name: String,
        html: String,
        files: Map<String, ByteArray> = emptyMap(),
        base: String = "",
        images: MutableMap<String, ByteArray> = LinkedHashMap(),
    ): ParsedBook {
        val withPictures = embedHtmlImages(html, files, base, images)
        val title = Regex("(?is)<title>(.*?)</title>").find(html)?.groupValues?.get(1)?.let(::decode)
            ?: name.substringBeforeLast('.')
        val chapters = ArrayList<BookChapter>()
        val body = StringBuilder()
        var current = title
        val tokens = Regex("(?is)<h[1-3][^>]*>.*?</h[1-3]>|<p[^>]*>.*?</p>|<br\\s*/?>")
        var cursor = 0
        for (match in tokens.findAll(withPictures)) {
            if (match.range.first > cursor) {
                val gap = stripTags(withPictures.substring(cursor, match.range.first))
                if (gap.isNotBlank()) body.append(gap).append("\n\n")
            }
            val token = match.value
            if (token.startsWith("<h", ignoreCase = true)) {
                val value = body.toString().trim()
                body.clear()
                if (value.isNotEmpty()) chapters.add(BookChapter(current, value))
                current = stripTags(token).ifBlank { current }
            } else {
                body.append(stripTags(token)).append("\n\n")
            }
            cursor = match.range.last + 1
        }
        val tail = stripTags(withPictures.substring(cursor))
        if (tail.isNotBlank()) body.append(tail)
        val last = body.toString().trim()
        if (last.isNotEmpty()) chapters.add(BookChapter(current, last))
        if (chapters.isEmpty()) chapters.add(BookChapter(title, stripTags(withPictures)))
        return ParsedBook(title, chapters, images)
    }

    private fun parseFb2(bytes: ByteArray, name: String): ParsedBook {
        if (isZip(bytes)) return parseContainer(bytes, name, 0)
        val xml = String(bytes, charset(bytes))
        val images = fb2Images(xml)
        val title = Regex("(?is)<book-title>(.*?)</book-title>").find(xml)?.groupValues?.get(1)?.let(::decode)
            ?: name.substringBeforeLast('.')
        val body = Regex("(?is)<body\\b[^>]*>(.*)</body>").find(xml)?.groupValues?.get(1) ?: xml
        val chapters = ArrayList<BookChapter>()
        val paras = StringBuilder()
        var current = title
        fun flush() {
            val text = paras.toString().trim()
            paras.clear()
            if (text.isNotEmpty()) chapters.add(BookChapter(current, text))
        }
        val cover = Regex("(?is)<coverpage\\b[^>]*>(.*?)</coverpage>").find(xml)?.groupValues?.get(1).orEmpty()
        imageMarker(cover, images)?.let { paras.append(it).append("\n\n") }
        val tokens = Regex("(?is)<title\\b[^>]*>.*?</title>|<image\\b[^>]*?/?>|<p\\b[^>]*>.*?</p>|<empty-line\\s*/?>")
        for (match in tokens.findAll(body)) {
            val token = match.value
            when {
                token.startsWith("<title", ignoreCase = true) -> {
                    flush()
                    current = stripTags(token).ifBlank { current }
                }
                token.startsWith("<image", ignoreCase = true) ->
                    imageMarker(token, images)?.let { paras.append(it).append("\n\n") }
                token.startsWith("<empty", ignoreCase = true) -> paras.append("\n\n")
                else -> paras.append(textWithImages(token, images)).append("\n\n")
            }
        }
        flush()
        if (chapters.isEmpty()) chapters.add(BookChapter(title, stripTags(body).ifBlank { stripTags(xml) }))
        return ParsedBook(title, chapters, images)
    }

    /**
     * FB2 files from catalogs are zip archives. A zip can also hold several notes
     * and books (txt, Markdown, HTML, FB2, EPUB).
     */
    private fun parseContainer(bytes: ByteArray, name: String, depth: Int): ParsedBook {
        if (depth > 2) error("This archive is nested too deep.")
        val files = LinkedHashMap<String, ByteArray>()
        var total = 0
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && !entry.name.contains("__MACOSX")) {
                    val data = zip.readBytes()
                    total += data.size
                    if (total > MAX_UNCOMPRESSED) error("Unpacked text is larger than 16 MB.")
                    files[entry.name.replace('\\', '/')] = data
                }
                entry = zip.nextEntry
            }
        }
        val epub = "META-INF/container.xml" in files ||
            files["mimetype"]?.toString(Charsets.US_ASCII)?.contains("epub") == true
        if (epub) return parseEpub(bytes, name)
        val parts = ArrayList<ParsedBook>()
        val images = LinkedHashMap<String, ByteArray>()
        for ((path, data) in files) {
            val leaf = path.substringAfterLast('/')
            if (leaf.isEmpty() || leaf.startsWith(".")) continue
            val part = when {
                isZip(data) -> parseContainer(data, leaf, depth + 1)
                extension(leaf) == "epub" -> parseEpub(data, leaf)
                extension(leaf) == "fb2" || extension(leaf) == "fbz" -> parseFb2(data, leaf)
                extension(leaf) == "html" || extension(leaf) == "htm" ->
                    htmlBook(leaf, String(data, charset(data)))
                extension(leaf) == "md" || extension(leaf) == "markdown" ->
                    markdownBook(leaf, String(data, charset(data)))
                extension(leaf) in setOf("txt", "note", "notes") ->
                    plainBook(leaf, String(data, charset(data)))
                else -> null
            } ?: continue
            parts.add(part)
            part.images.forEach { (id, bytes) -> images.putIfAbsent(id, bytes) }
        }
        if (parts.isEmpty()) error("This archive has no readable book or note.")
        if (parts.size == 1) return parts[0]
        val chapters = parts.flatMap { part ->
            val label = part.title.ifBlank { name }
            part.chapters.map { chapter ->
                chapter.copy(title = if (part.chapters.size == 1) label else "$label · ${chapter.title}")
            }
        }
        return ParsedBook(name.substringBeforeLast('.'), chapters, images)
    }

    private fun isZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte()

    private fun readableName(path: String): Boolean {
        val leaf = path.substringAfterLast('/').substringAfterLast('\\').lowercase()
        if (leaf.isEmpty() || leaf.startsWith(".")) return false
        return extension(leaf) in readable || leaf.endsWith(".fb2.zip")
    }

    private fun parseEpub(bytes: ByteArray, name: String): ParsedBook {
        val files = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) files[entry.name.replace('\\', '/')] = zip.readBytes()
                entry = zip.nextEntry
            }
        }
        val container = files["META-INF/container.xml"]?.toString(Charsets.UTF_8).orEmpty()
        val opfPath = Regex("full-path=\"([^\"]+)\"").find(container)?.groupValues?.get(1)
            ?: files.keys.firstOrNull { it.endsWith(".opf") }
            ?: return plainBook(name, "")
        val opf = files[opfPath]?.toString(Charsets.UTF_8).orEmpty()
        val base = opfPath.substringBeforeLast('/', "")
        val title = Regex("(?is)<dc:title[^>]*>(.*?)</dc:title>").find(opf)?.groupValues?.get(1)?.let(::decode)
            ?: name.substringBeforeLast('.')
        val hrefs = Regex("(?is)<itemref[^>]*idref=\"([^\"]+)\"").findAll(opf).map { it.groupValues[1] }.toList()
        val manifest = Regex("(?is)<item\\b[^>]*>").findAll(opf).associate { tag ->
            val id = Regex("id=\"([^\"]+)\"").find(tag.value)?.groupValues?.get(1).orEmpty()
            val href = Regex("href=\"([^\"]+)\"").find(tag.value)?.groupValues?.get(1).orEmpty()
            id to href
        }
        val chapters = ArrayList<BookChapter>()
        val images = LinkedHashMap<String, ByteArray>()
        for (id in hrefs) {
            val href = manifest[id] ?: continue
            val key = listOf(href, "$base/$href").firstOrNull { it in files } ?: continue
            val html = files.getValue(key).toString(Charsets.UTF_8)
            val dir = key.substringBeforeLast('/', "")
            val parsed = htmlBook(href.substringAfterLast('/'), html, files, dir, images)
            chapters.addAll(parsed.chapters)
        }
        return ParsedBook(title, chapters.ifEmpty { listOf(BookChapter(title, "(empty book)")) }, images)
    }

    private fun fb2Images(xml: String): Map<String, ByteArray> {
        val images = LinkedHashMap<String, ByteArray>()
        Regex("(?is)<binary\\b([^>]*)>(.*?)</binary>").findAll(xml).forEach { match ->
            val id = attr(match.groupValues[1], "id") ?: return@forEach
            val type = attr(match.groupValues[1], "content-type").orEmpty()
            if (type.isNotEmpty() && !type.startsWith("image/")) return@forEach
            val raw = match.groupValues[2].replace(Regex("\\s+"), "")
            val bytes = runCatching { java.util.Base64.getMimeDecoder().decode(raw) }.getOrNull() ?: return@forEach
            if (bytes.size in 32..MAX_IMAGE) images[id] = bytes
        }
        return images
    }

    private fun imageMarker(fragment: String, images: Map<String, ByteArray>): String? {
        val href = Regex("(?is)(?:l:href|xlink:href|href)\\s*=\\s*[\"']#?([^\"']+)[\"']")
            .find(fragment)?.groupValues?.get(1) ?: return null
        val id = href.removePrefix("#").substringAfterLast('/')
        if (id !in images && href !in images) return null
        val key = if (id in images) id else href
        return "\u0001$key\u0001"
    }

    private fun textWithImages(fragment: String, images: Map<String, ByteArray>): String {
        val marked = Regex("(?is)<image\\b[^>]*?/?>").replace(fragment) { tag ->
            imageMarker(tag.value, images)?.let { "\n$it\n" } ?: ""
        }
        return stripTags(marked)
    }

    private fun embedHtmlImages(
        html: String,
        files: Map<String, ByteArray>,
        base: String,
        images: MutableMap<String, ByteArray>,
    ): String = Regex("(?is)<img\\b[^>]*>|<image\\b[^>]*?/?>").replace(html) { tag ->
        val src = attr(tag.value, "src") ?: attr(tag.value, "xlink:href") ?: attr(tag.value, "href")
            ?: return@replace tag.value
        val clean = java.net.URLDecoder.decode(src.substringBefore('?'), Charsets.UTF_8).removePrefix("#")
        val key = listOf(clean, "$base/$clean", clean.removePrefix("./")).firstOrNull { it in files } ?: return@replace ""
        val bytes = files.getValue(key)
        if (bytes.size !in 32..MAX_IMAGE) return@replace ""
        val id = "img-${images.size}-${clean.substringAfterLast('/')}"
        images[id] = bytes
        "\n\u0001$id\u0001\n"
    }

    private fun attr(tag: String, name: String): String? =
        Regex("(?is)(?:^|\\s)${Regex.escape(name)}\\s*=\\s*[\"']([^\"']+)[\"']").find(tag)?.groupValues?.get(1)

    private fun stripTags(value: String): String = decode(
        value.replace(Regex("(?is)<(script|style)\\b[^>]*>.*?</\\1>"), " ")
            .replace(Regex("(?is)<br\\s*/?>"), "\n")
            .replace(Regex("(?is)</p>"), "\n\n")
            .replace(Regex("(?is)<[^>]+>"), " ")
            .replace(Regex("[ \\t\\x0c]+"), " ")
            .replace(Regex(" *\n *"), "\n")
            .replace(Regex("\n{3,}"), "\n\n"),
    ).trim()

    private fun decode(value: String): String = value
        .replace("&nbsp;", " ")
        .replace("&amp;", "&")
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&#39;", "'")
        .replace(Regex("&#(\\d+);")) { it.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: it.value }

    private fun charset(bytes: ByteArray): Charset {
        val head = String(bytes.take(240).toByteArray(), Charsets.ISO_8859_1)
        val named = Regex("encoding=[\"']([^\"']+)[\"']").find(head)?.groupValues?.get(1)
        return named?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
    }

    private fun extension(name: String) = name.substringAfterLast('.', "").lowercase()

    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_UNCOMPRESSED = 16 * 1024 * 1024
    private const val MAX_IMAGE = 2 * 1024 * 1024
}
