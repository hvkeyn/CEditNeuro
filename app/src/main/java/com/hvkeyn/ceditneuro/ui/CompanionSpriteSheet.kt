package com.hvkeyn.ceditneuro.ui

/** Fixed layout for an imported companion sprite sheet. */
object CompanionSpriteSheet {
    const val COLUMNS = 4
    const val ROWS = 8
    const val FRAMES = COLUMNS
    const val FRAME_MS = 160L

    fun rowFor(mood: CompanionMood): Int = when (mood) {
        CompanionMood.IDLE -> 0
        CompanionMood.SLEEPING -> 1
        CompanionMood.THINKING -> 2
        CompanionMood.WORKING -> 3
        CompanionMood.TEAM -> 4
        CompanionMood.HAPPY -> 5
        CompanionMood.WORRIED -> 6
        CompanionMood.OFFLINE -> 7
    }

    fun frameFor(now: Long): Int = ((now / FRAME_MS) % FRAMES).toInt()

    fun usable(width: Int, height: Int): Boolean =
        width > 0 && height > 0 && width % COLUMNS == 0 && height % ROWS == 0
}
