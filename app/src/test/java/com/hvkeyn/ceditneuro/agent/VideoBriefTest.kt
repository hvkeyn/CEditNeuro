package com.hvkeyn.ceditneuro.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class VideoBriefTest {
    @Test
    fun idsAndStreams() {
        assertEquals("abcdefghijk", VideoBrief.youtubeId("https://www.youtube.com/watch?v=abcdefghijk"))
        assertEquals("abcdefghijk", VideoBrief.youtubeId("https://youtu.be/abcdefghijk"))
        assertEquals("abcdefghijk", VideoBrief.youtubeId("https://m.youtube.com/shorts/abcdefghijk"))
        assertEquals("0123456789abcdef0123456789abcdef", VideoBrief.rutubeId("https://rutube.ru/video/0123456789abcdef0123456789abcdef/"))
        assertEquals("yandex", VideoBrief.platform("dzen.ru"))
        assertTrue(VideoBrief.reject("https://rr3---sn.googlevideo.com/videoplayback?mime=video")!!.contains("stream"))
        assertTrue(VideoBrief.reject("https://example.com/watch")!!.contains("YouTube"))
        assertNull(VideoBrief.reject("https://yandex.ru/video/preview/123"))
    }

    @Test
    fun playerKeepsCaptionsAndDropsTheStream() {
        val body = """
            {"videoDetails":{"title":"A talk","author":"Ada","shortDescription":"About glucose."},
            "streamingData":{"formats":[{"url":"https://rr.googlevideo.com/videoplayback?mime=video"}]},
            "captions":{"playerCaptionsTracklistRenderer":{"captionTracks":[
            {"baseUrl":"https://www.youtube.com/api/timedtext?v=1&lang=en","languageCode":"en","kind":"asr"},
            {"baseUrl":"https://www.youtube.com/api/timedtext?v=1&lang=ru","languageCode":"ru"}
            ]}}}
        """.trimIndent()
        val extracted = VideoBrief.playerJson("<script>ytInitialPlayerResponse = $body;</script>")
        assertTrue(extracted != null && extracted.contains("videoDetails"))
        val (facts, track) = VideoBrief.youtubePlayer(extracted!!)
        assertEquals("A talk", facts.title)
        assertEquals("ru", track?.lang)
        assertTrue(!track!!.url.contains("googlevideo"))
        val words = VideoBrief.captionsFrom("<transcript><text>The trial measured</text><text>fasting glucose</text></transcript>")
        assertTrue(words.contains("fasting glucose"))
        val timed = VideoBrief.captionsFrom("""<timedtext><p t="1">The trial <s>measured</s></p><p t="2">fasting glucose</p></timedtext>""")
        assertTrue(timed.contains("measured"))
        assertTrue(timed.contains("fasting glucose"))
        assertTrue(!timed.contains("<s>"))
        val rendered = VideoBrief.render(facts.copy(captions = words, captionLang = "ru"), "https://youtu.be/abcdefghijk")
        assertTrue(rendered.contains("captions: yes ru"))
        assertTrue(!rendered.contains("googlevideo"))
    }

    @Test
    fun aPageWithoutCaptionsSaysThePictureWasNotSeen() {
        val html = """<meta property="og:title" content="Обзор &amp; тест"><meta property="og:description" content="Коротко о деле.">"""
        val facts = VideoBrief.pageFacts(html)
        assertEquals("Обзор & тест", facts.title)
        val rendered = VideoBrief.render(facts, "https://dzen.ru/video/watch/abc")
        assertTrue(rendered.contains("captions: no"))
        assertTrue(rendered.contains("picture was not seen"))
        assertNull(VideoBrief.pageCaptionUrl(html + "https://cdn.example/movie.mp4"))
        assertTrue(VideoBrief.pageCaptionUrl(html + " https://strm.yandex.ru/captions/a.vtt")!!.endsWith(".vtt"))
    }

    @Test
    fun searchChannelAndPlaylistStayTitles() {
        val body = """
            {"contents":[{"compactVideoRenderer":{"videoId":"abcdefghijk","title":{"runs":[{"text":"A talk"}]},"longBylineText":{"simpleText":"Ada"},"lengthText":{"simpleText":"12:03"}}},
            {"compactVideoRenderer":{"videoId":"abcdefghijk","title":{"simpleText":"A talk again"}}},
            {"playlistVideoRenderer":{"videoId":"zzzzzzzzzzz","title":{"runs":[{"text":"Second"}]},"shortBylineText":{"runs":[{"text":"Bea"}]}}}]}
        """.trimIndent()
        val hits = VideoBrief.hitsFrom(body, 8)
        assertEquals(2, hits.size)
        assertEquals("A talk", hits[0].title)
        assertEquals("Ada", hits[0].author)
        assertEquals("12:03", hits[0].length)
        val rendered = VideoBrief.renderHits("search", "glucose", hits)
        assertTrue(rendered.contains("https://www.youtube.com/watch?v=abcdefghijk"))
        assertTrue(rendered.contains("Titles only"))
        assertTrue(!rendered.contains("googlevideo"))
        assertEquals("PLabcDEF1234567890", VideoBrief.playlistId("https://www.youtube.com/playlist?list=PLabcDEF1234567890"))
        assertEquals("@SomeChannel", VideoBrief.channelRef("https://www.youtube.com/@SomeChannel/videos"))
        assertEquals("UCaaaaaaaaaaaaaaaaaaaaaa", VideoBrief.channelIdFrom("""{"endpoint":{"browseId":"UCaaaaaaaaaaaaaaaaaaaaaa"}}"""))
        assertNull(VideoBrief.searchQuery("https://youtu.be/abcdefghijk"))
        assertEquals("kotlin talk", VideoBrief.searchQuery("  kotlin   talk "))
        assertEquals("\"a b\"", VideoBrief.jsonString("a\nb"))
    }

    @Test
    fun anOldVideoSkillGainsSearch() {
        val dir = Files.createTempDirectory("video-skill").toFile()
        val file = java.io.File(dir, "video-notes.md")
        file.writeText("# Notes from a video\n1. Call video_brief with the page URL.\n", Charsets.UTF_8)
        StarterSkills.ensure(dir)
        val text = file.readText()
        assertTrue(text.startsWith("# Notes from a video"))
        assertTrue(text.contains("action=search"))
        assertTrue(text.contains("transcript service"))
    }
}
