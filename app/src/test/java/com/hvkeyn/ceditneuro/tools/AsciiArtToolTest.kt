package com.hvkeyn.ceditneuro.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Test

class AsciiArtToolTest {
    @Test
    fun sharkSceneIsAscii() = runBlocking {
        val out = AsciiArtTool().execute(buildJsonObject {
            put("subject", "shark")
        })
        assertTrue(!out.isError)
        assertTrue(out.content.startsWith("```html"))
        assertTrue(out.content.contains("<pre"))
        assertTrue(out.content.contains("%"))
        assertTrue(out.content.length < 12_000)
    }

    @Test
    fun textBecomesABlockBanner() = runBlocking {
        val out = AsciiArtTool().execute(buildJsonObject {
            put("subject", "text")
            put("text", "REA 6.8")
        })
        assertTrue(!out.isError)
        assertTrue(out.content.contains("#"))
        assertTrue(out.content.contains("#####"))
    }

    @Test
    fun framesAreLabelled() = runBlocking {
        val out = AsciiArtTool().execute(buildJsonObject {
            put("subject", "ocean")
            put("frames", 2)
        })
        assertTrue(!out.isError)
        assertTrue(out.content.contains("frame 1/2"))
        assertTrue(out.content.contains("frame 2/2"))
    }

    @Test
    fun animatedFramesUseACssLoop() = runBlocking {
        val out = AsciiArtTool().execute(buildJsonObject {
            put("subject", "ocean")
            put("frames", 3)
            put("animate", true)
        })
        assertTrue(!out.isError)
        assertTrue(out.content.contains("@keyframes ascii-cycle"))
        assertTrue(out.content.contains("animation:ascii-cycle"))
        assertTrue(out.content.contains("3-frame loop"))
        assertTrue(!out.content.contains("frame 1/3"))
        assertTrue(out.content.length < 12_000)
    }

    @Test
    fun anUnknownSubjectIsRefused() = runBlocking {
        val out = AsciiArtTool().execute(buildJsonObject { put("subject", "dragon") })
        assertTrue(out.isError)
    }
}
