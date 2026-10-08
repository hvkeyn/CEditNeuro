package com.hvkeyn.ceditneuro.reader

/** Maps a saved place onto the pages that fit the current screen. */
object ReaderPlace {
    fun pageFor(
        ranges: List<IntRange>,
        textLength: Int,
        anchor: Int,
        fraction: Float,
        savedPage: Int,
    ): Int {
        if (ranges.isEmpty()) return 0
        val last = ranges.lastIndex
        if (anchor > 0) {
            val hit = ranges.indexOfLast { it.first <= anchor }
            if (hit >= 0) return hit.coerceAtMost(last)
        }
        if (fraction > 0f && textLength > 0) {
            val target = (fraction * textLength).toInt().coerceIn(0, textLength)
            val hit = ranges.indexOfLast { it.first <= target }
            if (hit >= 0) return hit.coerceAtMost(last)
        }
        return savedPage.coerceIn(0, last)
    }

    /** The screen page that contains this character, including the start of the text. */
    fun pageAt(ranges: List<IntRange>, anchor: Int): Int {
        if (ranges.isEmpty()) return 0
        val hit = ranges.indexOfLast { it.first <= anchor.coerceAtLeast(0) }
        return if (hit >= 0) hit else 0
    }
}
