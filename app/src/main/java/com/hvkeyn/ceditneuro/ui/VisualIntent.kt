package com.hvkeyn.ceditneuro.ui

import java.util.Locale

/**
 * A prompt that asks to see a process, system, device, or drawing should carry the
 * visualize skill instead of leaving the model to notice a catalog entry.
 */
internal fun wantsVisualSkill(prompt: String): Boolean {
    val text = prompt.lowercase(Locale.US)
    val direct = listOf(
        "визуал", "нарису", "схем", "диаграм", "чертёж", "чертеж",
        "разбери", "разбор", "устройств", "внутренност", "из чего состоит",
        "visual", "draw", "diagram", "schema", "teardown", "exploded",
        "blueprint", "illustrat", "flow chart", "flowchart",
    )
    if (direct.any { it in text }) return true
    val subject = listOf(
        "процесс", "архитектур", "систем", "компонент", "поток",
        "process", "architecture", "system", "component", "flow", "device",
    )
    val ask = listOf(
        "покажи", "показать", "объясни", "сделай", "построй",
        "show", "explain", "make", "build", "visualize",
    )
    return subject.any { it in text } && ask.any { it in text }
}
