package com.hvkeyn.ceditneuro.video

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ShrinkPlanTest {
    @Test
    fun aLowerQualityUsesASmallerEvenFrame() {
        val full = ShrinkPlan.frame(4000, 3000, 90, null)
        assertEquals(4000, full.first)
        assertEquals(3000, full.second)
        val mid = ShrinkPlan.frame(4000, 3000, 50, null)
        assertEquals(1280, mid.first)
        assertEquals(960, mid.second)
        assertEquals(0, mid.first % 2)
        assertEquals(0, mid.second % 2)
        val named = ShrinkPlan.frame(4000, 3000, 90, 640)
        assertEquals(640, named.first)
        assertEquals(480, named.second)
    }

    @Test
    fun sampleSizeStaysAPowerOfTwo() {
        assertEquals(1, ShrinkPlan.sampleSize(800, 600, 1280))
        assertEquals(4, ShrinkPlan.sampleSize(4000, 3000, 854))
    }

    @Test
    fun quantizeDropsCloseColors() {
        val pixels = intArrayOf(0xFF102030.toInt(), 0xFF112131.toInt(), 0xFF000000.toInt())
        ShrinkPlan.quantize(pixels, 40)
        assertEquals(pixels[0], pixels[1])
        assertEquals(0xFF000000.toInt(), pixels[2])
        val sharp = intArrayOf(0xFF102030.toInt())
        ShrinkPlan.quantize(sharp, 95)
        assertEquals(0xFF102030.toInt(), sharp[0])
        val rate = ShrinkPlan.videoBitrate(1280, 720, 60, 15)
        assertTrue(rate in 120_000..8_000_000)
        assertEquals(12, ShrinkPlan.videoFps(20))
        assertEquals(64_000, ShrinkPlan.audioBitrate(20))
    }
}
