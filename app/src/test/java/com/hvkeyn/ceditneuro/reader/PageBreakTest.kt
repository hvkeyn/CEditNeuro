package com.hvkeyn.ceditneuro.reader

import org.junit.Assert.assertEquals
import org.junit.Test

class PageBreakTest {
    @Test
    fun aPageEndsOnTheLastLineThatFits() {
        val end = PageBreak.exclusiveEnd(
            start = 0,
            limit = 40,
            height = 30,
            lineBottoms = listOf(16f, 32f, 48f),
            lineEnds = listOf(12, 28, 40),
        )
        assertEquals(12, end)
    }

    @Test
    fun theNextPageStartsWhereThisOneStopped() {
        val first = PageBreak.exclusiveEnd(0, 40, 30, listOf(16f, 32f, 48f), listOf(12, 28, 40))
        val second = PageBreak.exclusiveEnd(first, 40, 30, listOf(16f, 32f), listOf(16, 28))
        assertEquals(12, first)
        assertEquals(28, second)
    }

    @Test
    fun aShortRemainderStaysOnTheSamePage() {
        assertEquals(18, PageBreak.exclusiveEnd(0, 18, 80, listOf(16f, 32f), listOf(10, 18)))
    }

    @Test
    fun oneLineIsTakenWhenEvenTheFirstLineIsTall() {
        assertEquals(9, PageBreak.exclusiveEnd(0, 9, 4, listOf(20f), listOf(9)))
    }
}
