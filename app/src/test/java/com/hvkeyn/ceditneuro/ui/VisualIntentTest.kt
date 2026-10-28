package com.hvkeyn.ceditneuro.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VisualIntentTest {
    @Test
    fun russianVisualRequestsActivate() {
        assertTrue(wantsVisualSkill("Визуализируй процесс сборки"))
        assertTrue(wantsVisualSkill("Нарисуй схему архитектуры"))
        assertTrue(wantsVisualSkill("Разбери устройство на части"))
    }

    @Test
    fun englishVisualRequestsActivate() {
        assertTrue(wantsVisualSkill("Show a process diagram"))
        assertTrue(wantsVisualSkill("Teardown a phone in a static sheet"))
        assertTrue(wantsVisualSkill("Draw the system components"))
    }

    @Test
    fun ordinaryWorkDoesNotActivate() {
        assertFalse(wantsVisualSkill("Fix the process function in this file"))
        assertFalse(wantsVisualSkill("Read build.gradle.kts and run the tests"))
        assertFalse(wantsVisualSkill("Обнови README и запушь"))
    }
}
