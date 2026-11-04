package com.hvkeyn.ceditneuro.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ReaVideoPlanTest {
    @Test
    fun aSteadyClipIsOneShot() {
        val samples = (0..8).map { ReaSample(it * 500L, 0x203040) }
        val shots = ReaVideoPlanner.plan(4_000, samples, threshold = 30)
        assertEquals(1, shots.size)
        assertEquals(0L, shots.single().startMs)
        assertEquals(4_000L, shots.single().endMs)
    }

    @Test
    fun aLargeChangeStartsANewShot() {
        val samples = listOf(
            ReaSample(0, 0x101010),
            ReaSample(500, 0x101010),
            ReaSample(1_000, 0xF0F0F0),
            ReaSample(1_500, 0xF0F0F0),
        )
        val shots = ReaVideoPlanner.plan(2_000, samples, threshold = 30)
        assertEquals(2, shots.size)
        assertEquals(0L, shots[0].startMs)
        assertEquals(1_000L, shots[0].endMs)
        assertEquals(1_000L, shots[1].startMs)
        assertEquals(2_000L, shots[1].endMs)
    }

    @Test
    fun tinyCutsAreIgnoredAndTheCapHolds() {
        val samples = (0..40).map { ReaSample(it * 100L, if (it % 2 == 0) 0x000000 else 0xFFFFFF) }
        val shots = ReaVideoPlanner.plan(4_100, samples, threshold = 20, maxShots = 4)
        assertTrue(shots.size <= 4)
        assertEquals(0L, shots.first().startMs)
        assertEquals(4_100L, shots.last().endMs)
    }
}
