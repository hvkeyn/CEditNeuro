package com.hvkeyn.ceditneuro.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChartSheetTest {
    @Test
    fun aHeaderAndTwoSeriesBecomeColumns() {
        val sheet = ChartSheet.parse(
            """
            type: columns
            title: week
            day sales costs
            mon 10 8
            tue 14 9
            wed 12 11
            """.trimIndent(),
        )
        requireNotNull(sheet)
        assertEquals("columns", sheet.type)
        assertEquals("week", sheet.title)
        assertEquals(listOf("sales", "costs"), sheet.series)
        assertEquals(listOf("mon", "tue", "wed"), sheet.xLabels)
        assertEquals(listOf(10.0, 14.0, 12.0), sheet.columns[0])
        assertEquals(listOf(8.0, 9.0, 11.0), sheet.columns[1])
    }

    @Test
    fun aCommaRowAndADecimalCommaStillParse() {
        val sheet = ChartSheet.parse("type: spark\n1,2,10\n")
        requireNotNull(sheet)
        assertEquals(listOf(1.0, 2.0, 10.0), sheet.columns.single())
        assertEquals(3, ChartSheet.spark(sheet.columns.single()).length)
        val decimal = ChartSheet.parse("1,5 8\n2,5 9\n")
        requireNotNull(decimal)
        assertEquals(listOf(1.5, 2.5), decimal.columns[0])
        assertEquals(listOf(8.0, 9.0), decimal.columns[1])
    }

    @Test
    fun wordsThatAreNotNumbersAreRefused() {
        assertNull(ChartSheet.parse("type: pie\n1 2 3"))
        assertNull(ChartSheet.parse("hello"))
        assertNull(ChartSheet.parse(""))
    }

    @Test
    fun aCommaBetweenWholeNumbersIsTwoValues() {
        val sheet = ChartSheet.parse("type: bars\n6,1\n")
        requireNotNull(sheet)
        assertEquals(listOf(6.0, 1.0), sheet.columns.single())
    }

    @Test
    fun chartliNamesDrawAsTheSameCharts() {
        assertEquals("line", ChartSheet.parse("type: ascii\n1 3 2")!!.type)
        assertEquals("line", ChartSheet.parse("type: braille\n1 3 2")!!.type)
        assertEquals("bars", ChartSheet.parse("type: bar\n1 3 2")!!.type)
    }

    @Test
    fun aNumberTableBecomesColumnsAndASentenceDoesNot() {
        val drawn = ChartSheet.fromTable(
            listOf("day", "sales"),
            listOf(listOf("mon", "10"), listOf("tue", "14")),
        )
        requireNotNull(drawn)
        assertEquals(listOf(10.0, 14.0), ChartSheet.parse(drawn)!!.columns.single())
        assertNull(
            ChartSheet.fromTable(
                listOf("day", "note"),
                listOf(listOf("mon", "busy"), listOf("tue", "quiet")),
            ),
        )
    }
}
