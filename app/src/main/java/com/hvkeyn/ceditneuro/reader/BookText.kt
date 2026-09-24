package com.hvkeyn.ceditneuro.reader

import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.util.zip.ZipInputStream

data class BookChapter(val title: String, val text: String)

data class ParsedBook(val title: String, val chapters: List<BookChapter>)

data class BookPage(val chapter: String, val text: String)

object BookText {
    private val extensions = setOf("txt", "md", "markdown", "note", "notes", "html", "htm", "fb2", "epub")

    fun supports(name: String): Boolean = extension(name) in extensions

    fun parse(name: String, bytes: ByteArray): ParsedBook {
        if (bytes.size > MAX_BYTES) error("This file is larger than 8 MB.")
        val book = when (extension(name)) {
            "epub" -> parseEpub(bytes, name)
            "fb2" -> parseFb2(bytes, name)
            "html", "htm" -> htmlBook(name, String(bytes, charset(bytes)))
            "md", "markdown" -> markdownBook(name, String(bytes, charset(bytes)))
            else -> plainBook(name, String(bytes, charset(bytes)))
        }
        return book.copy(
            title = polish(book.title),
            chapters = book.chapters.map { it.copy(title = polish(it.title), text = polish(it.text)) },
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

    private fun htmlBook(name: String, html: String): ParsedBook {
        val title = Regex("(?is)<title>(.*?)</title>").find(html)?.groupValues?.get(1)?.let(::decode)
            ?: name.substringBeforeLast('.')
        val chapters = ArrayList<BookChapter>()
        val body = StringBuilder()
        var current = title
        val tokens = Regex("(?is)<h[1-3][^>]*>.*?</h[1-3]>|<p[^>]*>.*?</p>|<br\\s*/?>")
        var cursor = 0
        for (match in tokens.findAll(html)) {
            if (match.range.first > cursor) {
                val gap = stripTags(html.substring(cursor, match.range.first))
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
        val tail = stripTags(html.substring(cursor))
        if (tail.isNotBlank()) body.append(tail)
        val last = body.toString().trim()
        if (last.isNotEmpty()) chapters.add(BookChapter(current, last))
        if (chapters.isEmpty()) chapters.add(BookChapter(title, stripTags(html)))
        return ParsedBook(title, chapters)
    }

    private fun parseFb2(bytes: ByteArray, name: String): ParsedBook {
        val xml = String(bytes, charset(bytes))
        val title = Regex("(?is)<book-title>(.*?)</book-title>").find(xml)?.groupValues?.get(1)?.let(::decode)
            ?: name.substringBeforeLast('.')
        val sections = Regex("(?is)<section\\b[^>]*>(.*?)</section>").findAll(xml).toList()
        if (sections.isEmpty()) return htmlBook(name, xml)
        val chapters = sections.map { section ->
            val heading = Regex("(?is)<title>(.*?)</title>").find(section.value)?.groupValues?.get(1)?.let(::stripTags)
            val paragraphs = Regex("(?is)<p\\b[^>]*>(.*?)</p>").findAll(section.value)
                .joinToString("\n\n") { stripTags(it.groupValues[1]) }
            BookChapter(heading?.ifBlank { null } ?: title, paragraphs.ifBlank { stripTags(section.value) })
        }.filter { it.text.isNotBlank() }
        return ParsedBook(title, chapters.ifEmpty { listOf(BookChapter(title, stripTags(xml))) })
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
        for (id in hrefs) {
            val href = manifest[id] ?: continue
            val key = listOf(href, "$base/$href").firstOrNull { it in files } ?: continue
            val html = files.getValue(key).toString(Charsets.UTF_8)
            val parsed = htmlBook(href.substringAfterLast('/'), html)
            chapters.addAll(parsed.chapters)
        }
        return ParsedBook(title, chapters.ifEmpty { listOf(BookChapter(title, "(empty book)")) })
    }

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
}
