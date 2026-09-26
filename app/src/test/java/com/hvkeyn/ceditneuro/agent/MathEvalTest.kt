package com.hvkeyn.ceditneuro.agent

import com.hvkeyn.ceditneuro.ui.reader.findPages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MathEvalTest {
    @Test
    fun evaluatesPrecedenceFunctionsAndDecimalComma() {
        assertEquals("14", MathEval.format(MathEval.eval("2 + 3 * 4")))
        assertEquals("512", MathEval.format(MathEval.eval("2^3^2")))
        assertEquals("5", MathEval.format(MathEval.eval("sqrt(9) + abs(-2)")))
        assertEquals("1.5", MathEval.format(MathEval.eval("3,0 / 2")))
        assertEquals("3", MathEval.format(MathEval.eval("log(1000)")))
        assertEquals("1", MathEval.format(MathEval.eval("round(sin(pi/2))")))
        assertEquals("25000", MathEval.format(MathEval.eval("2.5e4")))
    }

    @Test
    fun rejectsUnknownNamesAndBrokenInput() {
        assertTrue(runCatching { MathEval.eval("foo(2)") }.isFailure)
        assertTrue(runCatching { MathEval.eval("(1 + 2") }.isFailure)
        assertTrue(runCatching { MathEval.eval("2 +") }.isFailure)
    }

    @Test
    fun findsThePageOfEachMatch() {
        val text = "Alpha beta.\nGamma delta.\nBeta again."
        val ranges = listOf(0..11, 12..24, 25..text.lastIndex)
        val hits = findPages(text, ranges, "beta")
        assertEquals(listOf(0, 2), hits.map { it.first })
        assertTrue(findPages(text, ranges, "b").isEmpty())
    }
}
