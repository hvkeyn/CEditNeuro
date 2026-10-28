package com.hvkeyn.ceditneuro.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualCardToolTest {
    private val svg = """<svg viewBox="0 0 200 120" role="img"><title>Pump</title><rect x="20" y="20" width="120" height="60" fill="none" stroke="#1c1b1f"/><path d="M140 50h30" stroke="#0b6bcb"/></svg>"""

    @Test
    fun drawsAnSvgOnAPaperCard() = runBlocking {
        val out = VisualCardTool().execute(buildJsonObject {
            put("title", "PUMP · SECTION")
            put("svg", svg)
            put("caption", "Illustrative, not to scale.")
        })
        assertTrue(!out.isError)
        assertTrue(out.content.startsWith("```svg"))
        assertTrue(out.content.contains("<svg"))
        assertTrue(out.content.contains("PUMP"))
        assertTrue(out.content.contains("Illustrative"))
    }

    @Test
    fun refusesScriptAndEventHandlers() = runBlocking {
        val out = VisualCardTool().execute(buildJsonObject {
            put("svg", """<svg viewBox="0 0 10 10" onload="alert(1)"><script>alert(1)</script></svg>""")
        })
        assertTrue(out.isError)
    }

    @Test
    fun refusesAnExternalImage() = runBlocking {
        val out = VisualCardTool().execute(buildJsonObject {
            put("svg", """<svg viewBox="0 0 10 10"><image href="https://example.com/a.png"/></svg>""")
        })
        assertTrue(out.isError)
    }

    @Test
    fun needsAViewBox() = runBlocking {
        val out = VisualCardTool().execute(buildJsonObject {
            put("svg", """<svg width="200" height="120"><rect width="10" height="10"/></svg>""")
        })
        assertTrue(out.isError)
    }
}
