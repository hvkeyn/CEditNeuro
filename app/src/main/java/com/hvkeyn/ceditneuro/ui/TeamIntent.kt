package com.hvkeyn.ceditneuro.ui

import java.util.Locale

/** A request that needs the team playbook, not only the tool. */
internal fun wantsTeamSkill(prompt: String): Boolean {
    val text = prompt.lowercase(Locale.US)
    return listOf(
        "team", "multi-agent", "multi agent", "parallel", "dual",
        "команд", "агент", "параллель", "двойн", "помощ",
    ).any { it in text }
}
