package com.hvkeyn.ceditneuro.data

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionInputTest {
    @Test
    fun anExplicitCapabilityWins() {
        assertTrue(CatalogModel("custom-vlm", "Custom VLM", supportsVision = true).seesImages())
        assertFalse(CatalogModel("custom-text", "Custom text", supportsVision = false).seesImages())
    }

    @Test
    fun modernNamesAreRecognizedWithoutBlockingDeepSeek() {
        assertTrue(CatalogModel("gpt-4o", "GPT-4o").seesImages())
        assertTrue(CatalogModel("claude-sonnet", "Claude").seesImages())
        assertTrue(CatalogModel("deepseek-flash", "DeepSeek Flash", supportsVision = true).seesImages())
        assertFalse(CatalogModel("deepseek-chat", "DeepSeek text").seesImages())
    }

    @Test
    fun builtInDeepSeekModelsAdvertiseVision() {
        assertTrue(ModelCatalog.deepSeek().models.all { it.seesImages() })
    }
}
