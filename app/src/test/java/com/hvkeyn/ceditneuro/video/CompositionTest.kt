package com.hvkeyn.ceditneuro.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CompositionTest {
    private val page = """
        <!doctype html><html><head><meta charset="UTF-8" /></head><body>
        <div id="root" data-composition-id="intro" data-start="0" data-width="1080" data-height="1920">
          <section class="clip" data-start="0" data-duration="4" data-track-index="1"></section>
          <section class="clip" data-start="4" data-duration="2.5" data-track-index="2"></section>
        </div>
        <script>window.__timelines["intro"] = tl;</script>
        </body></html>
    """.trimIndent()

    @Test
    fun theRootAndTheLengthAreRead() {
        val info = Composition.parse(page)!!
        assertEquals("intro", info.id)
        assertEquals(1080, info.width)
        assertEquals(1920, info.height)
        assertEquals(6.5, info.seconds, 0.001)
        assertEquals(8.0, Composition.parse(page.replace("data-height=\"1920\"", "data-height=\"1920\" data-duration=\"8\""))!!.seconds, 0.001)
        assertNull(Composition.parse("<html><body>plain</body></html>"))
    }

    @Test
    fun theRegistryExistsBeforeThePageScripts() {
        val hosted = Composition.hosted(page, play = false)
        val registry = hosted.indexOf("window.__timelines=window.__timelines||{}")
        assertTrue(registry in 0 until hosted.indexOf("window.__timelines[\"intro\"]"))
        assertTrue(hosted.contains("__hfFrame"))
        assertFalse(hosted.contains("window.__hfPlay();"))
        assertTrue(Composition.hosted(page, play = true).contains("window.__hfPlay();"))
        assertTrue(hosted.trimEnd().endsWith("</html>"))
    }

    @Test
    fun theOutputKeepsTheAspectAndEvenSides() {
        val info = Composition.Info("a", 1920, 1080, 5.0)
        assertEquals(1280 to 720, Composition.outputSize(info, 1280))
        assertEquals(1080 to 1920, Composition.outputSize(Composition.Info("b", 1080, 1920, 5.0), 1920))
        val odd = Composition.outputSize(Composition.Info("c", 1001, 777, 5.0), 1280)
        assertEquals(0, odd.first % 2)
        assertEquals(0, odd.second % 2)
    }
}
