package com.hvkeyn.ceditneuro.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AudioTest {
    private val manifest = File("src/main/assets/sfx/manifest.json").readText()

    @Test
    fun aCueFindsTheBundledEffect() {
        val library = SfxLibrary(manifest)
        assertEquals(19, library.effects.size)
        for (effect in library.effects) assertTrue(effect.file, File("src/main/assets/sfx/${effect.file}").isFile)
        assertEquals("whoosh", library.match("whoosh")!!.key)
        assertEquals("whoosh", library.match("whoosh.mp3")!!.key)
        assertEquals("click", library.match("ui click")!!.key)
        assertEquals("whoosh", library.match("swoosh")!!.key)
        assertEquals("impact-bass-1", library.match("bass hit")!!.key)
        assertTrue(library.match("build up")!!.seconds > 10)
        assertNull(library.match("violin solo"))
    }

    @Test
    fun aVolumeLaneHoldsItsEndsAndInterpolates() {
        val lane = listOf(1.0 to 1.0, 3.0 to 0.2)
        assertEquals(1.0, AudioMix.laneValue(lane, 0.0), 1e-9)
        assertEquals(0.6, AudioMix.laneValue(lane, 2.0), 1e-9)
        assertEquals(0.2, AudioMix.laneValue(lane, 9.0), 1e-9)
        assertEquals(1.0, AudioMix.laneValue(emptyList(), 4.0), 1e-9)
    }

    @Test
    fun theBriefPicksTheMoodAndTheSeedTheTune() {
        assertEquals(MusicSynth.Mood.EPIC, MusicSynth.moodFor("Эпичный трейлер про битву"))
        assertEquals(MusicSynth.Mood.ELECTRONIC, MusicSynth.moodFor("crypto wallet launch"))
        assertEquals(MusicSynth.Mood.CALM, MusicSynth.moodFor("fintech payments explainer"))
        assertEquals(MusicSynth.Mood.UPLIFTING, MusicSynth.moodFor("SaaS dashboard"))
        val spec = MusicSynth.spec("tense thriller 132 bpm in minor", 8.0, key = "F#", seed = 7)
        assertEquals(132, spec.bpm)
        assertEquals(6, spec.root)
        assertEquals(MusicSynth.Scale.MINOR, spec.scale)
        val a = MusicSynth.arrange(spec)
        assertEquals(a.map { it.midi to it.start }, MusicSynth.arrange(spec).map { it.midi to it.start })
        assertNotEquals(a.map { it.midi }, MusicSynth.arrange(spec.copy(seed = 8)).map { it.midi })
        assertTrue(a.all { it.start < spec.seconds })
        assertTrue(a.any { it.voice == MusicSynth.Voice.KICK })
        assertFalse(MusicSynth.arrange(spec.copy(intensity = 0.1)).any { it.voice == MusicSynth.Voice.KICK })
    }

    @Test
    fun aRenderedBedHasTheAskedLengthAndSound() {
        val spec = MusicSynth.spec("uplifting product launch", 3.0, seed = 1)
        val out = File.createTempFile("bed", ".wav")
        try {
            var peak = 0
            Wav.write(out, 22_050, 2, (spec.seconds * 22_050).toInt()) { sink ->
                MusicSynth.render(spec, 22_050) { samples, count ->
                    for (i in 0 until count * 2) peak = maxOf(peak, kotlin.math.abs(samples[i].toInt()))
                    sink(samples, count)
                }
            }
            assertEquals(3.0, Wav.seconds(out)!!, 0.01)
            assertEquals(44L + 3 * 22_050 * 4, out.length())
            assertTrue("silent bed", peak > 2_000)
            assertTrue("clipped bed", peak < 32_767)
        } finally {
            out.delete()
        }
    }

    @Test
    fun thePlayerBarAndTheMediaListAreInThePage() {
        val page = """<html><head></head><body><div data-composition-id="a" data-duration="4"><audio id="m" src="assets/bgm/track.wav" data-start="0" data-volume="0.9"></audio></div></body></html>"""
        val played = Composition.hosted(page, play = true)
        assertTrue(played.contains("hf-rate"))
        assertTrue(played.contains("requestFullscreen"))
        assertTrue(played.contains("window.__hfMedia"))
        assertFalse(Composition.hosted(page, play = false).contains("hf-rate"))
    }
}
