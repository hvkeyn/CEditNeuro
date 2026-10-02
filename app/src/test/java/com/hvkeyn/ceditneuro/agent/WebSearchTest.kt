package com.hvkeyn.ceditneuro.agent

import com.hvkeyn.ceditneuro.tools.WebSearchPages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WebSearchTest {
    private val page = """
        <a rel="nofollow" class="result__a" href="https://commons.wikimedia.org/wiki/Category:Ocean_waves">Ocean &amp; waves</a>
        <td class="result__snippet">Media in category &quot;Ocean&quot;</td>
        <a rel="nofollow" class="result__a" href="https://www.youtube.com/watch?v=abc">A film</a>
        <td class="result__snippet">watch</td>
        <a rel="nofollow" class="result__a" href="https://commons.wikimedia.org/wiki/Category:Ocean_waves">Ocean &amp; waves</a>
        <td class="result__snippet">again</td>
        <a rel="nofollow" class="result__a" href="https://archive.org/details/ocean">Archive</a>
        <a class="result__snippet" href="https://archive.org/details/ocean">A public recording</a>
    """.trimIndent()

    @Test
    fun linksArePagesAndStoreHostsAreSkipped() {
        val (hits, skipped) = WebSearchPages.parse(page, 5)
        assertEquals(2, hits.size)
        assertEquals(1, skipped)
        assertEquals("Ocean & waves", hits[0].title)
        assertEquals("https://commons.wikimedia.org/wiki/Category:Ocean_waves", hits[0].url)
        assertEquals("Media in category \"Ocean\"", hits[0].snippet)
        assertTrue(hits[1].url.startsWith("https://archive.org/"))
        assertFalse(hits.any { it.url.contains("youtube") })
    }

    @Test
    fun aCheckPageIsNotAResultList() {
        assertTrue(WebSearchPages.isCheckPage("<html>anomaly.js</html>"))
        assertFalse(WebSearchPages.isCheckPage(page))
    }
}
