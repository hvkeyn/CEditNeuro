package com.hvkeyn.ceditneuro.ui

/** The cast for a team: each role keeps one face in the group panel. */
object TeamLooks {
    fun forRole(role: String): CompanionCharacter? = when (role) {
        "researcher" -> CompanionRoster.byId("momo")
        "reviewer" -> CompanionRoster.byId("rei")
        "architect" -> CompanionRoster.byId("sora")
        "tester" -> CompanionRoster.byId("yuki")
        "solver-a" -> CompanionRoster.byId("hana")
        "solver-b" -> CompanionRoster.byId("ren")
        "main" -> CompanionRoster.byId("kira")
        else -> null
    }

    fun mood(status: String): CompanionMood = when (status) {
        "working" -> CompanionMood.WORKING
        "done" -> CompanionMood.HAPPY
        "failed" -> CompanionMood.WORRIED
        else -> CompanionMood.IDLE
    }
}
