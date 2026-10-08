package com.hvkeyn.ceditneuro.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NoteVaultTest {
    @Test
    fun aWikiLinkKeepsItsTarget() {
        val root = temp()
        try {
            val book = BookText.parse(
                "index.md",
                "See [[Ports|the ports]] and [[#Setup]].\n\n## Setup\n\nReady.\n".toByteArray(),
                root,
            )
            val text = book.chapters.joinToString("\n") { it.text }
            val jumps = BookText.pagePieces(text).filterIsInstance<PagePiece.Jump>()
            assertEquals(listOf("the ports", "Setup"), jumps.map { it.label })
            assertEquals("Ports", jumps[0].target)
            assertEquals("Setup", jumps[1].heading)
            assertTrue(BookText.spoken(text).contains("the ports"))
            assertTrue(!BookText.spoken(text).contains("\u0002"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aFenceAndAWebLinkAreNotNotes() {
        val book = BookText.parse(
            "index.md",
            "Code `[[Hidden]]` and a [site](https://example.com).\n\n```\n[[Hidden]]\n```\n".toByteArray(),
            null,
        )
        val text = book.chapters.joinToString("\n") { it.text }
        assertTrue(BookText.pagePieces(text).none { it is PagePiece.Jump })
        assertTrue(text.contains("site"))
        assertTrue(!text.contains("https://example.com"))
    }

    @Test
    fun theSameFolderWinsAndAMentionIsListed() {
        val root = temp()
        try {
            val near = File(root, "notes")
            val far = File(root, "other")
            near.mkdirs()
            far.mkdirs()
            val here = File(near, "Index.md")
            here.writeText("Open [[Ports]] now.\n")
            File(near, "Ports.md").writeText("# Ports\n\n## Setup\n\nLocal.\n")
            File(far, "Ports.md").writeText("# Ports\n\nFar.\n")
            val hit = NoteVault.resolve(root, here, "Ports")
            assertTrue(hit is NoteVault.Hit.One)
            assertEquals(File(near, "Ports.md").canonicalFile, (hit as NoteVault.Hit.One).file)
            val mentions = NoteVault.mentions(root, File(near, "Ports.md"))
            assertEquals(listOf("Index"), mentions.map { it.first })
            val book = BookText.parse("Ports.md", File(near, "Ports.md").readBytes(), near)
            val pages = BookText.pages(book, 800)
            assertTrue(NoteVault.pageForHeading(pages, "Setup") >= 0)
            assertEquals(-1, NoteVault.pageForHeading(pages, "Missing"))
            val source = book.chapters.joinToString("\n") { "\u0000${it.title}\n${it.text}" }
            assertTrue(NoteVault.anchorForHeading(source, "Setup") > 0)
            assertEquals(-1, NoteVault.anchorForHeading(source, "Missing"))
            val sideBySide = BookText.parse("a.md", "[[One]][[Two]]".toByteArray(), null)
            val jumps = BookText.pagePieces(sideBySide.chapters.joinToString { it.text }).filterIsInstance<PagePiece.Jump>()
            assertEquals(listOf("One", "Two"), jumps.map { it.label })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun aLinkStaysInTheSentenceAndADottedNameResolves() {
        val book = BookText.parse(
            "index.md",
            "See [[Устойчивый интернет при вайтлистах]] today.\n".toByteArray(),
            null,
        )
        val source = book.chapters.joinToString("\n") { "\u0000${it.title}\n${it.text}" }
        val face = BookText.present(source)
        assertTrue(face.text.contains("See Устойчивый интернет при вайтлистах today."))
        assertTrue(!face.text.contains("\u0002"))
        val span = face.spans.single()
        assertEquals("Устойчивый интернет при вайтлистах", face.text.substring(span.start, span.end))
        assertEquals("Устойчивый интернет при вайтлистах", span.target)
        assertTrue(source.substring(face.sourceAt(span.end)).startsWith(" today."))
        val dated = BookText.parse("a.md", "[[План 01.06.2026]]\n".toByteArray(), null)
        val datedJump = BookText.pagePieces(dated.chapters.joinToString { it.text }).filterIsInstance<PagePiece.Jump>().single()
        assertEquals("План 01.06.2026", datedJump.label)
        assertEquals("План 01.06.2026", datedJump.target)

        val root = temp()
        try {
            val early = File(root, "early")
            early.mkdirs()
            repeat(420) { File(early, "note-$it.md").writeText("x\n") }
            var deep = File(root, "late")
            repeat(10) { deep = File(deep, "d$it").also { it.mkdirs() } }
            val target = File(deep, "Устойчивый интернет при вайтлистах.md")
            target.writeText("# title\n")
            val dotted = File(deep, "План 01.06.2026.md")
            dotted.writeText("x\n")
            val from = File(early, "note-0.md")
            val hit = NoteVault.resolve(root, from, "Устойчивый интернет при вайтлистах.")
            assertEquals(target.canonicalFile, (hit as NoteVault.Hit.One).file)
            val datedHit = NoteVault.resolve(root, from, "План 01.06.2026")
            assertEquals(dotted.canonicalFile, (datedHit as NoteVault.Hit.One).file)
        } finally {
            root.deleteRecursively()
        }
    }

    private fun temp(): File = File(System.getProperty("java.io.tmpdir"), "cedit-vault-" + System.nanoTime()).also { it.mkdirs() }
}
