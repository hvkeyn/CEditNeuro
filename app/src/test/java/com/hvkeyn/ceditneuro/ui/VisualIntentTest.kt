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
        assertTrue(wantsVisualSkill("Покажи картинку процесса"))
        assertTrue(wantsVisualSkill("Show a picture of the device"))
    }

    @Test
    fun ordinaryWorkDoesNotActivate() {
        assertFalse(wantsVisualSkill("Fix the process function in this file"))
        assertFalse(wantsVisualSkill("Read build.gradle.kts and run the tests"))
        assertFalse(wantsVisualSkill("Обнови README и запушь"))
    }
}
