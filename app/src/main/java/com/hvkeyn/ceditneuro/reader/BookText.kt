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

    fun parse(name: String, bytes: ByteArray, baseDir: java.io.File? = null): ParsedBook {
        if (bytes.size > MAX_BYTES) error("This file is larger than 8 MB.")
        val book = when {
            isZip(bytes) -> parseContainer(bytes, name, 0)
            extension(name) == "html" || extension(name) == "htm" -> htmlBook(name, String(bytes, charset(bytes)))
            extension(name) == "md" || extension(name) == "markdown" -> markdownBook(name, String(bytes, charset(bytes)), baseDir)
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
        val kept = ArrayList<String>()
        var value = text.replace("\r\n", "\n")
        value = Regex("\u0002[^\u0002]*\u0002").replace(value) { match ->
            kept += match.value
            "\uE000${kept.lastIndex}\uE000"
        }
        value = Regex("\\[\\[([^\\]|]+)\\|([^\\]]+)]]").replace(value) { it.groupValues[2].trim() }
        value = Regex("\\[\\[([^\\]]+)]]").replace(value) {
            it.groupValues[1].substringAfterLast('/').substringAfterLast('|').trim()
        }
        value = Regex("!\\[[^\\]]*]\\([^)]*\\)").replace(value, "")
        value = Regex("\\[([^\\]]+)]\\([^)]*\\)").replace(value) { it.groupValues[1] }
        value = Regex("`{1,3}([^`]*)`{1,3}").replace(value) { it.groupValues[1] }
        value = value.replace("**", "").replace("__", "").replace("~~", "")
        val lined = value.lineSequence().joinToString("\n") { line ->
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
        return Regex("\uE000(\\d+)\uE000").replace(lined) { match ->
            kept.getOrNull(match.groupValues[1].toInt()) ?: match.value
        }
    }

    /** Drops figure and link markers, keeping the words a person would hear. */
    fun spoken(text: String): String = text
        .replace('\u0000', '\n')
        .replace(Regex("\u0001[^\u0001]*\u0001"), " ")
        .replace(Regex("\u0002([^\u001f]*)\u001f[^\u0002]*\u0002"), "$1")
        .replace(Regex("[ ]{2,}"), " ")
        .trim()

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
                val sliceEnd = snapMarker(body, start, end)
                val slice = body.substring(start, sliceEnd).trim()
                if (slice.isNotEmpty()) pages.add(BookPage(chapter.title, slice))
                start = sliceEnd
                while (start < body.length && body[start].isWhitespace()) start++
            }
        }
        if (pages.isEmpty()) pages.add(BookPage(book.title, "(empty)"))
        return pages
    }

    /** Keeps a figure or a note link on one page instead of cutting through its marker. */
    private fun snapMarker(body: String, start: Int, end: Int): Int {
        if (start >= body.length) return body.length
        val mark = body[start]
        if (mark == '\u0001' || mark == '\u0002') {
            val close = body.indexOf(mark, start + 1)
            if (close > start) return (close + 1).coerceAtMost(body.length)
        }
        if (end >= body.length) return body.length
        var cut = end
        for (token in charArrayOf('\u0001', '\u0002')) {
            val open = body.lastIndexOf(token, (cut - 1).coerceAtLeast(start))
            if (open <= start) continue
            val close = body.indexOf(token, open + 1)
            if (close < 0 || close >= cut) cut = open
        }
        return if (cut <= start) end.coerceAtMost(body.length) else cut
    }

    private fun plainBook(name: String, text: String): ParsedBook {
        val title = name.substringBeforeLast('.').ifBlank { name }
        val woven = if (text.contains("[[") || text.contains("](")) weaveNoteLinks(text) else text
        return ParsedBook(title, listOf(BookChapter(title, woven.trim())))
    }

    /** A page is words, a picture, or both. Figure ids match keys in [ParsedBook.images]. */
    fun pagePieces(text: String): List<PagePiece> {
        val out = ArrayList<PagePiece>()
        var index = 0
        while (index < text.length) {
            val figureAt = text.indexOf('\u0001', index).takeIf { it >= 0 }
            val linkAt = text.indexOf('\u0002', index).takeIf { it >= 0 }
            val at = listOfNotNull(figureAt, linkAt).minOrNull()
            if (at == null) {
                addWords(out, text.substring(index))
                break
            }
            addWords(out, text.substring(index, at))
            val mark = text[at]
            val close = text.indexOf(mark, at + 1)
            if (close < 0) break
            val body = text.substring(at + 1, close)
            if (mark == '\u0001') {
                out += PagePiece.Figure(body)
            } else {
                val parts = body.split('\u001f')
                if (parts.size >= 3) out += PagePiece.Jump(parts[0], parts[1], parts[2])
            }
            index = close + 1
        }
        if (out.isEmpty()) out += PagePiece.Words(text)
        return out
    }

    fun isSvgBytes(bytes: ByteArray): Boolean {
        val head = String(bytes.copyOfRange(0, minOf(bytes.size, 400)), Charsets.UTF_8)
            .trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (head.startsWith("<svg", ignoreCase = true)) return true
        if (!head.startsWith("<?xml", ignoreCase = true)) return false
        val more = String(bytes.copyOfRange(0, minOf(bytes.size, 2_000)), Charsets.UTF_8)
        return more.contains("<svg", ignoreCase = true)
    }

    private fun markdownBook(name: String, text: String, baseDir: java.io.File?): ParsedBook {
        val images = LinkedHashMap<String, ByteArray>()
        val prepared = weaveNoteLinks(pullFigures(text, baseDir, images))
        val title = prepared.lineSequence().firstOrNull { it.startsWith("# ") }?.removePrefix("# ")?.trim()
            ?: name.substringBeforeLast('.')
        val chapters = ArrayList<BookChapter>()
        val body = StringBuilder()
        var current = title
        fun flush() {
            val value = body.toString().trim()
            body.clear()
            if (value.isNotEmpty()) chapters.add(BookChapter(current, value))
        }
        for (line in prepared.lineSequence()) {
            if (line.startsWith("# ")) {
                flush()
                current = line.trimStart('#').trim().ifBlank { current }
                current = Regex("\u0002([^\u001f]*)\u001f[^\u0002]*\u0002").replace(current) { it.groupValues[1] }
            } else {
                body.append(line.replace(Regex("^#{2,6}\\s+"), "")).append('\n')
            }
        }
        flush()
        if (chapters.isEmpty()) chapters.add(BookChapter(title, prepared.trim()))
        return ParsedBook(title, chapters, images)
    }

    private fun pullFigures(text: String, baseDir: java.io.File?, images: MutableMap<String, ByteArray>): String {
        var next = 0
        fun store(bytes: ByteArray): String {
            if (bytes.size !in 1..MAX_IMAGE) return ""
            val id = "fig-${next++}"
            images[id] = bytes
            return "\n\n\u0001$id\u0001\n\n"
        }
        var body = Regex("(?is)```[ \\t]*svg[ \\t]*\\r?\\n(.*?)```").replace(text) { match ->
            val svg = match.groupValues[1].trim()
            if (svg.contains("<svg", ignoreCase = true)) store(svg.toByteArray(Charsets.UTF_8)).ifEmpty { match.value }
            else match.value
        }
        body = Regex("(?is)<svg\\b.*?</svg>").replace(body) { match ->
            store(match.value.toByteArray(Charsets.UTF_8)).ifEmpty { match.value }
        }
        body = Regex("!\\[\\[([^\\]|#]+)]]").replace(body) { match ->
            val bytes = readFigure(baseDir, match.groupValues[1].trim()) ?: return@replace match.value
            store(bytes).ifEmpty { match.value }
        }
        body = Regex("!\\[([^\\]]*)]\\(([^)]+)\\)").replace(body) { match ->
            val src = match.groupValues[2].trim().trim('<', '>').substringBefore(' ').trim('"')
            val bytes = readFigure(baseDir, src) ?: return@replace match.value
            store(bytes).ifEmpty { match.value }
        }
        body = mapOutsideFences(body) { part ->
            Regex("`([^`\\s]+\\.(?i:svg|png|jpg|jpeg|webp|gif|bmp))`").replace(part) { match ->
                val bytes = readFigure(baseDir, match.groupValues[1]) ?: return@replace match.value
                store(bytes).ifEmpty { match.value }
            }
        }
        return body
    }

    private fun weaveNoteLinks(text: String): String = mapOutsideFences(text) { part ->
        mapOutsideTicks(part) { plain ->
            var value = WIKI.replace(plain) { match ->
                val body = match.groupValues[1]
                val alias = body.substringAfter('|', "").trim()
                val core = body.substringBefore('|').trim()
                val target = core.substringBefore('#').trim()
                val heading = core.substringAfter('#', "").trim()
                val label = alias.ifBlank {
                    target.substringAfterLast('/').substringBeforeLast('.').ifBlank { heading }.ifBlank { "note" }
                }
                linkMark(label, target, heading)
            }
            value = MARKDOWN_LINK.replace(value) { match ->
                val dest = match.groupValues[2].trim().trim('<', '>')
                if (dest.startsWith("http://") || dest.startsWith("https://") || dest.startsWith("mailto:")) {
                    return@replace match.value
                }
                val path = dest.substringBefore('#').substringBefore(' ').trim()
                val heading = dest.substringAfter('#', "").substringBefore(' ').replace("%20", " ").trim()
                linkMark(match.groupValues[1].trim().ifBlank { path.substringAfterLast('/') }, path, heading)
            }
            value
        }
    }

    private fun linkMark(label: String, target: String, heading: String): String {
        fun clean(value: String) = value.replace('\u0002', ' ').replace('\u001f', ' ').replace('\n', ' ').trim()
        return "\u0002${clean(label)}\u001f${clean(target)}\u001f${clean(heading)}\u0002"
    }

    private fun mapOutsideTicks(text: String, block: (String) -> String): String {
        val out = StringBuilder()
        var index = 0
        while (index < text.length) {
            if (text[index] == '`') {
                val end = text.indexOf('`', index + 1)
                if (end < 0) {
                    out.append(block(text.substring(index)))
                    break
                }
                out.append(text, index, end + 1)
                index = end + 1
            } else {
                val next = text.indexOf('`', index).let { if (it < 0) text.length else it }
                out.append(block(text.substring(index, next)))
                index = next
            }
        }
        return out.toString()
    }

    private fun addWords(out: MutableList<PagePiece>, raw: String) {
        if (raw.any { !it.isWhitespace() }) out += PagePiece.Words(raw)
    }

    private fun mapOutsideFences(text: String, block: (String) -> String): String {
        val fences = Regex("(?s)```.*?```")
        val out = StringBuilder()
        var last = 0
        for (match in fences.findAll(text)) {
            out.append(block(text.substring(last, match.range.first)))
            out.append(match.value)
            last = match.range.last + 1
        }
        out.append(block(text.substring(last)))
        return out.toString()
    }

    private fun readFigure(baseDir: java.io.File?, src: String): ByteArray? {
        if (baseDir == null) return null
        val clean = src.substringBefore('?').substringBefore('#')
        if (clean.startsWith("http://") || clean.startsWith("https://")) return null
        val ext = clean.substringAfterLast('.', "").lowercase()
        if (ext !in FIGURE_EXT) return null
        val relative = clean.removePrefix("file://").removePrefix("./")
        val file = locateFigure(baseDir, relative) ?: return null
        if (!file.isFile || file.length() !in 1..MAX_IMAGE) return null
        return runCatching { file.readBytes() }.getOrNull()
    }

    /** The note's folder, the folder above it, or one subfolder. Bare names live beside the report. */
    private fun locateFigure(baseDir: java.io.File, relative: String): java.io.File? {
        val direct = if (relative.startsWith("/")) java.io.File(relative) else java.io.File(baseDir, relative)
        val candidates = ArrayList<java.io.File>()
        candidates.add(direct)
        if (!relative.startsWith("/") && !relative.contains('/')) {
            baseDir.parentFile?.let { candidates.add(java.io.File(it, relative)) }
            baseDir.listFiles()?.forEach { child ->
                if (child.isDirectory && !child.name.startsWith(".")) candidates.add(java.io.File(child, relative))
            }
        }
        val base = runCatching { baseDir.canonicalFile }.getOrNull() ?: return null
        val roots = listOfNotNull(base, base.parentFile)
        for (candidate in candidates) {
            val target = runCatching { candidate.canonicalFile }.getOrNull() ?: continue
            val allowed = roots.any { root ->
                target.path == root.path || target.path.startsWith(root.path + java.io.File.separator)
            }
            if (allowed && target.isFile) return target
        }
        return null
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
                    markdownBook(leaf, String(data, charset(data)), null)
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

    private val WIKI = Regex("!?\\[\\[([^\\]]+)]]")
    private val MARKDOWN_LINK = Regex("(?<!!)\\[([^\\]]+)]\\(([^)]+)\\)")
    private val FIGURE = Regex("\u0001([^\u0001]+)\u0001")
    private val FIGURE_EXT = setOf("svg", "png", "jpg", "jpeg", "webp", "gif", "bmp")
    private const val MAX_BYTES = 8 * 1024 * 1024
    private const val MAX_UNCOMPRESSED = 16 * 1024 * 1024
    private const val MAX_IMAGE = 2 * 1024 * 1024
}

sealed class PagePiece {
    data class Words(val text: String) : PagePiece()
    data class Figure(val id: String) : PagePiece()
    data class Jump(val label: String, val target: String, val heading: String) : PagePiece()
}
