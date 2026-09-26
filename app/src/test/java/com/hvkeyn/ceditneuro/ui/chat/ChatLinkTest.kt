package com.hvkeyn.ceditneuro.ui.chat

import com.hvkeyn.ceditneuro.reader.BookText
import com.hvkeyn.ceditneuro.reader.PagePiece
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class ChatLinkTest {
    @Test
    fun fileAndWebTargets() {
        assertEquals("research/report.md", ChatLinks.target("research/report.md"))
        assertEquals("research/knigi.svg", ChatLinks.target("`research/knigi.svg`".trim('`')))
        assertEquals("https://example.com/a", ChatLinks.target("https://example.com/a."))
        assertTrue(ChatLinks.isWeb("https://example.com/a"))
        assertTrue(ChatLinks.isSvgPath("research/scheme.svg"))
        assertFalse(ChatLinks.isSvgPath("https://example.com/a.svg"))
        assertNull(ChatLinks.target("ordinary sentence"))
        assertNull(ChatLinks.target("v0.51.0"))
    }

    @Test
    fun offlineWording() {
        assertTrue(ChatLinks.looksOffline("Unable to resolve host \"api.example.com\": No address associated with hostname"))
        assertFalse(ChatLinks.looksOffline("HTTP 400 Invalid assistant message"))
    }

    @Test
    fun svgMarkupAndFile() {
        val root = File(System.getProperty("java.io.tmpdir"), "cedit-link-" + System.nanoTime())
        root.mkdirs()
        val svg = File(root, "scheme.svg")
        svg.writeText("<svg xmlns=\"http://www.w3.org/2000/svg\"><rect width=\"10\" height=\"10\"/></svg>")
        assertTrue(ChatLinks.file(root.path, "scheme.svg")!!.isFile)
        assertTrue(isSvgMarkup("svg", svg.readText()))
        assertTrue(BookText.isSvgBytes(svg.readBytes()))
        root.deleteRecursively()
    }

    @Test
    fun markdownKeepsAnSvgFigure() {
        val root = File(System.getProperty("java.io.tmpdir"), "cedit-book-" + System.nanoTime())
        root.mkdirs()
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 10 10\"><circle r=\"4\"/></svg>"
        File(root, "scheme.svg").writeText(svg)
        val md = """
            # Notes

            See the scheme.

            ![scheme](scheme.svg)

            ```svg
            $svg
            ```
        """.trimIndent()
        val book = BookText.parse("notes.md", md.toByteArray(), root)
        assertEquals(2, book.images.size)
        val pieces = book.chapters.flatMap { BookText.pagePieces(it.text) }
        assertTrue(pieces.any { it is PagePiece.Figure })
        assertTrue(pieces.any { it is PagePiece.Words && it.text.contains("See the scheme") })
        root.deleteRecursively()
    }
}
