package com.hvkeyn.ceditneuro.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchPagesTest {
    @Test
    fun parsesDuckDuckGoResult() {
        val html = """
            <a class="result__a" href="https://example.com/a">Example A</a>
            <span class="result__snippet">A result snippet.</span>
        """.trimIndent()
        val (hits, _) = WebSearchPages.parseDuck(html, 5)
        assertEquals(1, hits.size)
        assertEquals("https://example.com/a", hits[0].url)
        assertEquals("Example A", hits[0].title)
        assertTrue(hits[0].snippet.contains("snippet"))
    }

    @Test
    fun parsesBingResult() {
        val html = """
            <li class="b_algo">
              <h2><a href="https://example.org/b">Example B</a></h2>
              <p>Another snippet.</p>
            </li>
        """.trimIndent()
        val (hits, _) = WebSearchPages.parseBing(html, 5)
        assertEquals(1, hits.size)
        assertEquals("https://example.org/b", hits[0].url)
        assertEquals("Example B", hits[0].title)
    }

    @Test
    fun parsesBingRss() {
        val xml = """
            <rss><channel><item>
              <title>Android Developers</title>
              <link>https://developer.android.com/develop/ui/views/launch/icon_design_adaptive</link>
              <description>Adaptive icons documentation.</description>
            </item></channel></rss>
        """.trimIndent()
        val (hits, _) = WebSearchPages.parseBingRss(xml, 5)
        assertEquals(1, hits.size)
        assertEquals("https://developer.android.com/develop/ui/views/launch/icon_design_adaptive", hits[0].url)
        assertEquals("Android Developers", hits[0].title)
    }

    @Test
    fun parsesMojeekResult() {
        val html = """<a class="title" href="https://example.net/c">Example C</a>"""
        val (hits, _) = WebSearchPages.parseMojeek(html, 5)
        assertEquals(1, hits.size)
        assertEquals("https://example.net/c", hits[0].url)
    }
}
