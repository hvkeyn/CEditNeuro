package com.hvkeyn.ceditneuro.ui

import java.util.Locale

/** A request for a designed explanation page, not a single diagram or a clone. */
internal fun wantsExplainerSkill(prompt: String): Boolean {
    val text = prompt.lowercase(Locale.US)
    if ("visual-explainer" in text || "visual explainer" in text) return true
    val ask = listOf(
        "объясни", "объяснить", "разбери", "разбор", "сравни", "сравнение",
        "explain", "review", "recap", "compare", "walk through", "break down",
    )
    val subject = listOf(
        "архитектур", "систем", "план", "дифф", "изменен", "процесс", "поток",
        "architecture", "system", "plan", "diff", "change", "process", "flow",
        "codebase", "code", "schema", "design",
    )
    return ask.any { it in text } && subject.any { it in text }
}
