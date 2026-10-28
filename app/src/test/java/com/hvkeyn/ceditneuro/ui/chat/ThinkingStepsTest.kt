package com.hvkeyn.ceditneuro.ui.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThinkingStepsTest {
    @Test
    fun keepsReadableStepsAndDropsJson() {
        val steps = thinkingSteps(
            """
            - Read the request and identify the drawing.
            {"tool": "visual_card", "svg": "<svg>"}
            The plan should be checked against the source.
            """.trimIndent()
        )
        assertTrue(steps.any { it.contains("Read the request") })
        assertTrue(steps.any { it.contains("plan") })
        assertFalse(steps.any { it.startsWith("{") })
    }

    @Test
    fun emptyTextHasNoSteps() {
        assertTrue(thinkingSteps("").isEmpty())
    }
}
