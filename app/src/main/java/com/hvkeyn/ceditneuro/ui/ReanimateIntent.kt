package com.hvkeyn.ceditneuro.ui

import java.util.Locale

/** A reference animation is different from a new diagram: route it to reanimate before visualize. */
internal fun wantsReanimateSkill(prompt: String): Boolean {
    val text = prompt.lowercase(Locale.US)
    if ("reanimate" in text) return true
    if ("clone" in text || "reverse-engineer" in text || "reverse engineer" in text) {
        if ("animation" in text || "motion" in text || "gif" in text || "video" in text || "lottie" in text) return true
    }
    if ("same animation" in text || "match this animation" in text || "copy this animation" in text) return true
    if ("как на видео" in text || "как в видео" in text || "как на гифке" in text) return true
    val reference = listOf("гифк", "gif", "видео", "video", "lottie", "сайт", "site")
    val animation = listOf("анимац", "animation", "движен", "motion", "переход", "transition", "эффект", "hover")
    val copy = listOf("повтор", "склон", "клон", "как на", "как в", "сделай", "сделать", "copy", "clone", "match", "recreate", "repeat")
    return reference.any { it in text } && animation.any { it in text } && copy.any { it in text }
}
