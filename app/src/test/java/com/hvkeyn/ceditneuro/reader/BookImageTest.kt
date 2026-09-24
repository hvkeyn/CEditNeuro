package com.hvkeyn.ceditneuro.reader

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

class BookImageTest {
    @Test
    fun fb2KeepsAReferencedPicture() {
        val png = Base64.getEncoder().encodeToString(ByteArray(40) { 1 })
        val xml = """
            <FictionBook>
              <description><title-info><book-title>Tale</book-title>
                <coverpage><image l:href="#cover.png"/></coverpage>
              </title-info></description>
              <body><section>
                <p>Hello</p>
                <image l:href="#pic.png"/>
                <p>After</p>
              </section></body>
              <binary id="cover.png" content-type="image/png">$png</binary>
              <binary id="pic.png" content-type="image/png">$png</binary>
            </FictionBook>
        """.trimIndent()
        val book = BookText.parse("tale.fb2", xml.toByteArray())
        val text = book.chapters.joinToString("\n") { it.text }
        assertTrue(text.contains("\u0001cover.png\u0001"))
        assertTrue(text.contains("\u0001pic.png\u0001"))
        assertTrue(text.contains("Hello"))
        assertEquals(2, book.images.size)
    }
}
