package com.hvkeyn.ceditneuro.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionSpriteSheetTest {
    @Test
    fun rowsMatchTheDocumentedOrder() {
        assertEquals(0, CompanionSpriteSheet.rowFor(CompanionMood.IDLE))
        assertEquals(1, CompanionSpriteSheet.rowFor(CompanionMood.SLEEPING))
        assertEquals(3, CompanionSpriteSheet.rowFor(CompanionMood.WORKING))
        assertEquals(7, CompanionSpriteSheet.rowFor(CompanionMood.OFFLINE))
    }

    @Test
    fun onlyAFullGridIsAccepted() {
        assertTrue(CompanionSpriteSheet.usable(256, 512))
        assertFalse(CompanionSpriteSheet.usable(250, 512))
        assertFalse(CompanionSpriteSheet.usable(256, 500))
        assertFalse(CompanionSpriteSheet.usable(0, 0))
    }

    @Test
    fun framesLoopOverFourCells() {
        assertEquals(0, CompanionSpriteSheet.frameFor(0))
        assertEquals(1, CompanionSpriteSheet.frameFor(160))
        assertEquals(0, CompanionSpriteSheet.frameFor(640))
    }
}
