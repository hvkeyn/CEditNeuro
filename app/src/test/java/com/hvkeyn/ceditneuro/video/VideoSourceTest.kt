package com.hvkeyn.ceditneuro.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VideoSourceTest {

    @Test
    fun masterPlaylistKeepsVariantsAndPicksTheAskedHeight() {
        val text = """
            #EXTM3U
            #EXT-X-STREAM-INF:BANDWIDTH=800000,RESOLUTION=640x360
            360/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1280x720
            720/index.m3u8
            #EXT-X-STREAM-INF:BANDWIDTH=5000000,RESOLUTION=1920x1080
            https://cdn.example.com/1080/index.m3u8
        """.trimIndent()

        val playlist = VideoSource.parseHls(text, "https://cdn.example.com/master.m3u8")

        assertTrue(playlist.master)
        assertEquals(3, playlist.variants.size)
        assertEquals(1080, playlist.variants.last().height)
        assertEquals("https://cdn.example.com/1080/index.m3u8", playlist.variants.last().uri)
        assertEquals(1080, VideoSource.chooseVariant(playlist, "best")?.height)
        assertEquals(720, VideoSource.chooseVariant(playlist, "720")?.height)
        assertEquals(360, VideoSource.chooseVariant(playlist, "360")?.height)
        assertEquals(360, VideoSource.chooseVariant(playlist, "worst")?.height)
        assertEquals("", playlist.drm)
    }

    @Test
    fun mediaPlaylistReadsSegmentsMapAndAesKey() {
        val text = """
            #EXTM3U
            #EXT-X-VERSION:7
            #EXT-X-TARGETDURATION:6
            #EXT-X-MEDIA-SEQUENCE:12
            #EXT-X-MAP:URI="init.mp4"
            #EXT-X-KEY:METHOD=AES-128,URI="https://keys.example.com/k1",IV=0x0000000000000000000000000000000c
            #EXTINF:6.0,
            seg12.m4s
            #EXTINF:6.0,
            https://cdn.example.com/seg13.m4s
            #EXT-X-ENDLIST
        """.trimIndent()

        val playlist = VideoSource.parseHls(text, "https://cdn.example.com/path/index.m3u8")

        assertTrue(!playlist.master)
        assertTrue(playlist.ended)
        assertEquals(6, playlist.targetDuration)
        assertEquals("https://cdn.example.com/path/init.mp4", playlist.initUri)
        assertEquals(2, playlist.segments.size)
        assertEquals(12L, playlist.segments[0].sequence)
        assertEquals("https://cdn.example.com/path/seg12.m4s", playlist.segments[0].uri)
        assertEquals("https://cdn.example.com/seg13.m4s", playlist.segments[1].uri)
        assertEquals("AES-128", playlist.segments[0].key?.method)
        assertEquals("https://keys.example.com/k1", playlist.segments[0].key?.uri)
        assertEquals(12, playlist.segments[0].key?.iv?.last()?.toInt())
    }

    @Test
    fun aSeparateAudioGroupIsAnAudioVariant() {
        val text = """
            #EXTM3U
            #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="aud",NAME="Russian",URI="audio/ru.m3u8"
            #EXT-X-STREAM-INF:BANDWIDTH=2400000,RESOLUTION=1280x720,AUDIO="aud"
            video/720.m3u8
        """.trimIndent()

        val playlist = VideoSource.parseHls(text, "https://cdn.example.com/master.m3u8")
        val audio = VideoSource.chooseVariant(playlist, "audio")

        assertEquals("https://cdn.example.com/audio/ru.m3u8", audio?.uri)
        assertEquals("Russian", audio?.name)
        assertTrue(audio?.audio == true)
        assertEquals(720, VideoSource.chooseVariant(playlist, "best")?.height)
    }

    @Test
    fun aVodPlaylistWithoutEndlistIsStillFinished() {
        val text = """
            #EXTM3U
            #EXT-X-PLAYLIST-TYPE:VOD
            #EXT-X-TARGETDURATION:6
            #EXTINF:6.0,
            seg1.ts
        """.trimIndent()

        val playlist = VideoSource.parseHls(text, "https://cdn.example.com/a.m3u8")

        assertTrue(playlist.vod)
        assertTrue(!playlist.ended)
        assertEquals(1, playlist.segments.size)
    }

    @Test
    fun drmIsNamedAndNotTreatedAsPlainAes() {
        val sampleAes = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-KEY:METHOD=SAMPLE-AES,URI="skd://key",KEYFORMAT="com.apple.streamingkeydelivery"
            #EXTINF:6.0,
            seg1.ts
            #EXT-X-ENDLIST
        """.trimIndent()
        val widevine = """
            #EXTM3U
            #EXT-X-TARGETDURATION:6
            #EXT-X-KEY:METHOD=AES-128,URI="https://keys.example.com/k",KEYFORMAT="urn:uuid:edef8ba9-79d6-4ace-a3c8-27dcd51d21ed"
            #EXTINF:6.0,
            seg1.ts
            #EXT-X-ENDLIST
        """.trimIndent()

        assertTrue(VideoSource.parseHls(sampleAes, "https://cdn.example.com/a.m3u8").drm.contains("SAMPLE-AES"))
        assertTrue(VideoSource.parseHls(widevine, "https://cdn.example.com/a.m3u8").drm.contains("DRM"))
    }

    @Test
    fun pageLinksReadMetaTagVideoTagAndEscapedJson() {
        val html = """
            <html><head>
            <meta property="og:video" content="https://cdn.example.com/1080/movie.mp4" />
            <meta property="og:title" content="A Film &amp; Its Title" />
            </head><body>
            <video><source src="/media/trailer.webm" type="video/webm"></video>
            <script>window.__DATA__ = {"hlsUrl":"https:\/\/cdn.example.com\/hls\/master.m3u8","contentUrl":"https:\/\/cdn.example.com\/movie.mp4"}</script>
            </body></html>
        """.trimIndent()

        val links = VideoSource.pageLinks(html, "https://page.example.com/watch/1")

        assertTrue(links.any { it.url == "https://cdn.example.com/1080/movie.mp4" })
        assertTrue(links.any { it.url == "https://cdn.example.com/hls/master.m3u8" })
        assertTrue(links.any { it.url == "https://page.example.com/media/trailer.webm" })
        assertEquals("A Film & Its Title", VideoSource.titleFromHtml(html))
    }

    @Test
    fun directMediaIsRecognisedBeforeTheQuery() {
        assertTrue(VideoSource.isDirectMedia("https://cdn.example.com/a/b.mp4?token=1"))
        assertTrue(VideoSource.isDirectMedia("https://cdn.example.com/a/b.m3u8#x"))
        assertTrue(!VideoSource.isDirectMedia("https://page.example.com/watch/1"))
        assertEquals(MediaKind.HLS, VideoSource.kindOf("https://cdn.example.com/a.m3u8"))
        assertEquals(MediaKind.DASH, VideoSource.kindOf("https://cdn.example.com/a.mpd"))
        assertNull(VideoSource.kindOf("https://page.example.com/watch/1"))
    }

    @Test
    fun safeFileNameRemovesPathAndKeepsOneExtension() {
        assertEquals("A Film 2026", VideoSource.safeFileName("A/Film: 2026?"))
        assertEquals("video", VideoSource.safeFileName("   "))
        assertTrue(VideoSource.safeFileName("x".repeat(300)).length <= 110)
        assertEquals("Movie.mp4", VideoSource.withExtension("Movie.mkv", "mp4"))
        assertEquals("Movie.mp4", VideoSource.withExtension("Movie", ".MP4"))
    }

    @Test
    fun durationAndSizeAreReadable() {
        assertEquals("9:56", VideoSource.formatDuration(596))
        assertEquals("1:02:03", VideoSource.formatDuration(3723))
        assertEquals("unknown", VideoSource.formatDuration(null))
        assertEquals("1.0 MB", VideoSource.formatBytes(1024L * 1024L))
    }

    @Test
    fun anExtractorFormatWithNoCodecFieldsIsTreatedAsMuxed() {
        val unknown = YtFormat("1", "mp4", 720, 1280, "", "", 1_000L, "https")
        val videoOnly = unknown.copy(vcodec = "avc1", acodec = "none")
        val audioOnly = unknown.copy(vcodec = "none", acodec = "mp4a")

        assertTrue(unknown.muxed)
        assertTrue(!videoOnly.muxed)
        assertTrue(!audioOnly.muxed)
    }

    @Test
    fun extractorHostsMatchTheirSubdomains() {
        assertTrue(VideoEngine.isExtractorHost("youtube.com"))
        assertTrue(VideoEngine.isExtractorHost("www.youtube.com"))
        assertTrue(VideoEngine.isExtractorHost("m.vkvideo.ru"))
        assertTrue(!VideoEngine.isExtractorHost("notyoutube.com"))
        assertTrue(!VideoEngine.isExtractorHost("example.com"))
    }
}
