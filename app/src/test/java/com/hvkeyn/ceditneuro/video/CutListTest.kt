package com.hvkeyn.ceditneuro.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CutListTest {
    @Test
    fun aFadeOverlapsAndThePageCoversItsLength() {
        val pieces = CutList.parse(
            """
            0 2 Hello
            1.5 4 fade Next & more
            """.trimIndent(),
        )
        requireNotNull(pieces)
        assertTrue(pieces[1].fade)
        assertEquals("Next & more", pieces[1].caption)
        val sheet = requireNotNull(CutList.page("clip.mp4", 1080, 1920, pieces))
        assertTrue(sheet.seconds in 4.1..4.2)
        assertTrue(sheet.html.contains("&amp;"))
        assertTrue(Composition.audit(sheet.html).isEmpty())
    }

    @Test
    fun aSavedReelOpensAsTheSameEdit() {
        val saved = CutList.write(
            CutList.Project("clip.mp4", 720, 1280, listOf(CutList.Piece(1.0, 2.5, "Hi", fade = true))),
        )
        val loaded = requireNotNull(CutList.load(saved))
        assertEquals("clip.mp4", loaded.source)
        assertEquals(720, loaded.width)
        assertEquals("Hi", loaded.pieces.single().caption)
        assertTrue(loaded.pieces.single().fade)
        assertNull(CutList.load("source: ../secret.mp4\n"))
    }

    @Test
    fun aTimelineKeepsTwoPicturesMusicAndEffects() {
        val project = CutList.Project(
            source = "a.mp4",
            width = 720,
            height = 1280,
            pieces = emptyList(),
            clips = listOf(
                CutList.Clip("video", 0, "a.mp4", 0.0, 0.0, 3.0, setOf("gray", "fade"), "Title"),
                CutList.Clip("video", 1, "b.mp4", 1.0, 0.5, 2.0, setOf("zoom"), ""),
                CutList.Clip("music", 0, "assets/bgm/track.wav", 0.0, 0.0, 3.0),
            ),
        )
        val loaded = requireNotNull(CutList.load(CutList.write(project)))
        assertEquals(3, loaded.clips.size)
        assertTrue(loaded.clips[0].effects.contains("gray"))
        assertEquals("Title", loaded.clips[0].caption)
        assertEquals(1, loaded.clips[1].lane)
        assertEquals("music", loaded.clips[2].kind)
        val sheet = requireNotNull(CutList.page(loaded))
        assertTrue(sheet.html.contains("grayscale"))
        assertTrue(sheet.html.contains("z-index:2"))
        assertTrue(sheet.html.contains("track.wav"))
        assertTrue(sheet.html.contains("scale(1.18)"))
        assertTrue(Composition.audit(sheet.html).isEmpty())
        val old = requireNotNull(CutList.load("source: clip.mp4\nwidth: 720\nheight: 1280\n1 2.5 fade Hi\n"))
        assertEquals("video", old.clips.single().kind)
        assertTrue(old.clips.single().effects.contains("fade"))
        assertNull(CutList.load("source: a.mp4\nclip video 0 ../b.mp4 0 0 1\n"))
        val more = requireNotNull(
            CutList.load(
                """
                source: a.mp4
                width: 720
                height: 1280
                clip video 0 a.mp4 0 0 3
                clip video 2 c.mp4 0 0 1
                clip music 1 assets/sfx/ping.mp3 0 0 1
                """.trimIndent(),
            ),
        )
        assertEquals(2, more.clips[1].lane)
        assertEquals(1, more.clips[2].lane)
        assertTrue(requireNotNull(CutList.page(more)).html.contains("z-index:3"))
    }

    @Test
    fun speedSplitAndAnEditListComeBack() {
        val clip = CutList.Clip("video", 0, "a.mp4", 0.0, 0.0, 4.0, setOf("zoom"), "Hello", 2.0, 0.5)
        val loaded = requireNotNull(CutList.load(CutList.write(CutList.Project("a.mp4", 720, 1280, emptyList(), listOf(clip))))).clips.single()
        assertEquals(2.0, loaded.speed, 0.001)
        assertEquals(0.5, loaded.volume, 0.001)
        assertEquals(2.0, loaded.span(), 0.001)
        val sheet = requireNotNull(CutList.page(CutList.Project("a.mp4", 720, 1280, emptyList(), listOf(loaded))))
        assertTrue(sheet.html.contains("data-playback-rate=\"2\""))
        assertTrue(sheet.html.contains("data-volume=\"0.5\""))
        assertTrue(Composition.audit(sheet.html).isEmpty())
        val parts = requireNotNull(CutList.split(loaded, 0.0))
        assertEquals(2.0, parts.first.end, 0.001)
        assertEquals(1.0, parts.second.at, 0.001)
        val back = requireNotNull(CutList.takeIn(CutList.edl(CutList.Project("a.mp4", 720, 1280, emptyList(), listOf(loaded))), emptyList()))
        assertEquals("a.mp4", back.single().file)
        assertEquals(2.0, back.single().speed, 0.001)
        assertEquals("Hello", back.single().caption)
        assertTrue(back.single().effects.contains("zoom"))
        val captioned = requireNotNull(CutList.takeIn(CutList.srt(CutList.Project("a.mp4", 720, 1280, emptyList(), listOf(loaded))), listOf(loaded.copy(caption = ""))))
        assertEquals("Hello", captioned.single().caption)
        val gapped = CutList.closeGaps(listOf(clip, clip.copy(at = 5.0)), "video", 0)
        assertEquals(clip.span(), gapped[1].at, 0.001)
        assertTrue(CutList.review(CutList.Project("a.mp4", 720, 1280, emptyList(), listOf(clip.copy(file = "gone.mp4"))), setOf("a.mp4")).any { it.contains("Missing") })
        assertTrue(CutList.review(CutList.Project("a.mp4", 720, 1280, emptyList(), listOf(clip, clip.copy(at = 0.2))), setOf("a.mp4")).any { it.contains("overlap") })
    }

    @Test
    fun aBlockMovesToAnotherLaneAndKeepsItsTransition() {
        val video = CutList.Clip("video", 0, "a.mp4", 0.0, 0.0, 2.0)
        val other = CutList.Clip("video", 1, "b.mp4", 0.0, 0.0, 2.0)
        val bed = CutList.Clip("music", 0, "bed.wav", 0.0, 0.0, 4.0, setOf("fade"))
        val moved = CutList.place(listOf(video, other, bed), 0, 1.2, 1)
        assertEquals(1, moved[0].lane)
        assertEquals(1.2, moved[0].at, 0.001)
        assertEquals(1, moved[1].lane)
        assertEquals(0, moved[2].lane)
        val styled = CutList.Clip("video", 1, "a.mp4", 0.0, 0.0, 2.0, setOf("blur", "flip"), "", 1.0, 1.0, "wipe")
        val project = CutList.Project("a.mp4", 720, 1280, emptyList(), listOf(styled, bed))
        val loaded = requireNotNull(CutList.load(CutList.write(project))).clips
        assertEquals("wipe", loaded[0].transition)
        assertTrue(loaded[0].effects.contains("blur"))
        assertEquals(1, loaded[0].lane)
        val html = requireNotNull(CutList.page(project.copy(clips = loaded))).html
        assertTrue(html.contains("data-transition=\"wipe\""))
        assertTrue(html.contains("blur(6px)"))
        assertTrue(html.contains("scaleX(-1)"))
        assertTrue(html.contains("data-automation"))
        assertTrue(Composition.audit(html).isEmpty())
        val back = requireNotNull(CutList.takeIn(CutList.edl(project.copy(clips = loaded)), emptyList()))
        assertEquals("wipe", back[0].transition)
        assertTrue(back[0].effects.contains("flip"))
        assertNull(CutList.load("source: a.mp4\nclip video 0 a.mp4 0 0 1 transition:spin\n"))
    }

    @Test
    fun aVoiceFileIsKeptAndABadRangeIsRefused() {
        val pieces = CutList.parse("0 1.2 voice:line.wav Caption")
        requireNotNull(pieces)
        assertEquals("line.wav", pieces.single().voice)
        assertNull(CutList.parse("3 1 too soon"))
        assertNull(CutList.parse("0 1 voice:../secret.wav Hi"))
    }
}
