package com.hvkeyn.ceditneuro.ui.chat

import com.hvkeyn.ceditneuro.ui.editor.svgAspect
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SvgVisualTest {
    @Test
    fun aspectUsesViewBoxBeforeWidthAndHeight() {
        val aspect = svgAspect("""<svg width="100%" viewBox="0 0 960 480"><rect width="960" height="480"/></svg>""")
        assertEquals(0.5f, aspect, 0.01f)
    }

    @Test
    fun aspectFallsBackToWidthAndHeight() {
        val aspect = svgAspect("""<svg width="200" height="100"><rect width="200" height="100"/></svg>""")
        assertEquals(0.5f, aspect, 0.01f)
    }

    @Test
    fun staticSvgIsNotReplayed() {
        assertNull(svgReplayMs("""<svg viewBox="0 0 10 10"><rect width="10" height="10"/></svg>"""))
    }

    @Test
    fun animatedSvgGetsFiveSecondPause() {
        val css = """<svg viewBox="0 0 10 10"><style>@keyframes slide{from{opacity:0}to{opacity:1}}</style><rect style="animation:slide 2s ease"/></svg>"""
        assertEquals(7_000L, svgReplayMs(css))
        assertTrue((svgReplayMs(css) ?: 0) >= 5_000L)
    }
}
