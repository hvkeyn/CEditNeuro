package com.hvkeyn.ceditneuro.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class ReaderPlaceTest {
    private val ranges = listOf(0..99, 100..199, 200..299)

    @Test
    fun anchorWinsOverTheOldPageIndex() {
        assertEquals(2, ReaderPlace.pageFor(ranges, 300, anchor = 240, fraction = 0.1f, savedPage = 0))
    }

    @Test
    fun fractionRestoresAPlaceSavedBeforeAnchors() {
        assertEquals(1, ReaderPlace.pageFor(ranges, 300, anchor = 0, fraction = 0.5f, savedPage = 0))
    }

    @Test
    fun savedPageIsUsedWhenNothingElseIsKnown() {
        assertEquals(2, ReaderPlace.pageFor(ranges, 300, anchor = 0, fraction = 0f, savedPage = 2))
    }

    @Test
    fun aShortLayoutDoesNotPushThePlacePastTheEnd() {
        assertEquals(2, ReaderPlace.pageFor(ranges, 300, anchor = 0, fraction = 0f, savedPage = 40))
    }
}
