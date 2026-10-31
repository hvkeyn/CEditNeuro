package com.hvkeyn.ceditneuro.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReanimateIntentTest {
    @Test
    fun referenceAnimationsActivate() {
        assertTrue(wantsReanimateSkill("Reanimate this GIF into our project"))
        assertTrue(wantsReanimateSkill("Clone this animation from the site"))
        assertTrue(wantsReanimateSkill("Повтори анимацию из гифки"))
        assertTrue(wantsReanimateSkill("Сделай как на видео"))
    }

    @Test
    fun ordinaryRequestsDoNotActivate() {
        assertFalse(wantsReanimateSkill("Fix the animation test"))
        assertFalse(wantsReanimateSkill("Read the video notes"))
        assertFalse(wantsReanimateSkill("Обнови README"))
    }
}
