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
    fun separatesArgumentsWithCommasAndComputesStatistics() {
        assertEquals("2", MathEval.format(MathEval.eval("max(1,2)")))
        assertEquals("1", MathEval.format(MathEval.eval("min(1, 2, 3)")))
        assertEquals("10", MathEval.format(MathEval.eval("sum(1,2,3,4)")))
        assertEquals("2.5", MathEval.format(MathEval.eval("mean(1; 2; 3; 4)")))
        assertEquals("3", MathEval.format(MathEval.eval("median(5, 1, 3)")))
        assertEquals("1", MathEval.format(MathEval.eval("stdev(1, 2, 3)")))
        assertEquals("25", MathEval.format(MathEval.eval("pct(80, 100)")))
        assertEquals("7", MathEval.format(MathEval.eval("3,5 * 2")))
        assertTrue(runCatching { MathEval.eval("stdev(4)") }.isFailure)
    }

    @Test
    fun drawsAChartFromData() {
        val series = ResearchPlot.parseSeries("speed: 1, 2.5, 4\nbase: 1 1 1")
        assertEquals(listOf("speed", "base"), series.map { it.name })
        assertEquals(listOf(1.0, 2.5, 4.0), series[0].values)
        val svg = ResearchPlot.svg("Run <1>", "bar", listOf("a", "b", "c"), series)
        assertTrue(svg.startsWith("<svg") && svg.endsWith("</svg>"))
        assertTrue(svg.contains("Run &lt;1&gt;"))
        assertTrue(!svg.contains("<script"))
        assertTrue(runCatching { ResearchPlot.parseSeries("x: 1, two") }.isFailure)
    }

    @Test
    fun starterSkillsAreWrittenOnceAndStayDeleted() {
        val dir = kotlin.io.path.createTempDirectory("skills").toFile()
        try {
            ResearchSkill.ensure(dir)
            val debug = java.io.File(dir, "android-debug.md")
            assertTrue(java.io.File(dir, "research.md").readText().contains("research_plot"))
            assertTrue(debug.isFile)
            debug.delete()
            ResearchSkill.ensure(dir)
            assertTrue(!debug.exists())
            assertEquals(listOf("book-notes", "research"), SkillLibrary(null, dir).list().map { it.name })
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun oldResearchSkillGainsOnlyTheMissingLines() {
        val dir = kotlin.io.path.createTempDirectory("skills").toFile()
        try {
            val file = java.io.File(dir, "research.md")
            file.writeText("# My research\nuse calculate always\n")
            ResearchSkill.ensure(dir)
            val text = file.readText()
            assertTrue(text.startsWith("# My research"))
            assertTrue(text.contains("research_plot"))
            assertTrue(!text.contains("reference before"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun shellHistoryIsNewestFirstWithoutRepeats() {
        val lines = listOf("ls", "pwd", "ls", "git status").mapIndexed { i, c ->
            com.hvkeyn.ceditneuro.ui.ShellLine(i.toLong(), c, "")
        }
        assertEquals(listOf("git status", "ls", "pwd"), com.hvkeyn.ceditneuro.ui.shell.shellHistory(lines))
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
