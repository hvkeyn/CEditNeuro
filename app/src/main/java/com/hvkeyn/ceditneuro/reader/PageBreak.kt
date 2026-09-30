package com.hvkeyn.ceditneuro.reader

/** The exclusive end of one reader page, taken from lines that were actually measured. */
object PageBreak {
    fun exclusiveEnd(
        start: Int,
        limit: Int,
        height: Int,
        lineBottoms: List<Float>,
        lineEnds: List<Int>,
    ): Int {
        if (limit <= start) return limit
        if (lineBottoms.isEmpty() || lineEnds.isEmpty()) return (start + 1).coerceAtMost(limit)
        val count = minOf(lineBottoms.size, lineEnds.size)
        var line = 0
        while (line < count && lineBottoms[line] <= height) line++
        if (line == 0) line = 1
        val rel = lineEnds[line - 1].coerceAtLeast(1)
        return (start + rel).coerceIn(start + 1, limit)
    }
}
