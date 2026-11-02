package com.hvkeyn.ceditneuro.ui

import com.hvkeyn.ceditneuro.data.AgentSettings
import kotlin.math.absoluteValue

/**
 * Original chibi companions. The art is drawn locally with Canvas; no external
 * anime artwork or sprite sheet is used.
 */
data class CompanionCharacter(
    val id: String,
    val name: String,
    val tagline: String,
    val skin: Long,
    val hair: Long,
    val eyes: Long,
    val outfit: Long,
    val accent: Long,
    val accessory: String,
    val hairstyle: String,
)

object CompanionRoster {
    const val AUTO = "auto"

    val all: List<CompanionCharacter> = listOf(
        CompanionCharacter(
            id = "kira",
            name = "Kira",
            tagline = "Genki builder",
            skin = 0xFFFFD8C2,
            hair = 0xFFD96B2B,
            eyes = 0xFF7A3E1D,
            outfit = 0xFF31465E,
            accent = 0xFFF2A65A,
            accessory = "goggles",
            hairstyle = "spiky",
        ),
        CompanionCharacter(
            id = "momo",
            name = "Momo",
            tagline = "Quiet researcher",
            skin = 0xFFFFE0CC,
            hair = 0xFF3B6E6A,
            eyes = 0xFF2F5C52,
            outfit = 0xFF2F4F4A,
            accent = 0xFF7DD3FC,
            accessory = "glasses",
            hairstyle = "long",
        ),
        CompanionCharacter(
            id = "sora",
            name = "Sora",
            tagline = "Navigator",
            skin = 0xFFFFD9C4,
            hair = 0xFF4A6FA5,
            eyes = 0xFF2E4C78,
            outfit = 0xFF263B57,
            accent = 0xFF9CC7FF,
            accessory = "headset",
            hairstyle = "twin",
        ),
        CompanionCharacter(
            id = "rei",
            name = "Rei",
            tagline = "Night debugger",
            skin = 0xFFFFD6C4,
            hair = 0xFF5A3B57,
            eyes = 0xFF42304A,
            outfit = 0xFF332E3D,
            accent = 0xFFE0A6C8,
            accessory = "hood",
            hairstyle = "bob",
        ),
        CompanionCharacter(
            id = "yuki",
            name = "Yuki",
            tagline = "Calm tester",
            skin = 0xFFFFE3D3,
            hair = 0xFFD8D2C4,
            eyes = 0xFF5D6B72,
            outfit = 0xFF3E4A50,
            accent = 0xFFB7E4E8,
            accessory = "snowclip",
            hairstyle = "ponytail",
        ),
        CompanionCharacter(
            id = "hana",
            name = "Hana",
            tagline = "Bright support",
            skin = 0xFFFFDFC8,
            hair = 0xFFB05A7A,
            eyes = 0xFF7A354D,
            outfit = 0xFF4A3348,
            accent = 0xFFF2B8D0,
            accessory = "flower",
            hairstyle = "bob",
        ),
        CompanionCharacter(
            id = "ren",
            name = "Ren",
            tagline = "Focused runner",
            skin = 0xFFFFD7BF,
            hair = 0xFF2E3448,
            eyes = 0xFF39445C,
            outfit = 0xFF2E4152,
            accent = 0xFF8FD3C7,
            accessory = "bandana",
            hairstyle = "spiky",
        ),
    )

    fun byId(id: String): CompanionCharacter? = all.firstOrNull { it.id == id }

    fun candidates(workFocus: String): List<CompanionCharacter> = when (workFocus) {
        AgentSettings.WORK_BUILD -> listOf(byId("kira")!!, byId("ren")!!, byId("momo")!!)
        AgentSettings.WORK_STUDY -> listOf(byId("momo")!!, byId("yuki")!!)
        AgentSettings.WORK_REMOTE -> listOf(byId("sora")!!, byId("rei")!!)
        else -> listOf(byId("rei")!!, byId("kira")!!, byId("hana")!!, byId("yuki")!!)
    }

    /**
     * A manual id wins. Auto is stable for one project and work focus, so the
     * character belongs to the client, not to a recomposition.
     */
    fun resolve(selection: String, projectRoot: String?, workFocus: String): CompanionCharacter {
        byId(selection)?.let { return it }
        val pool = candidates(workFocus)
        val seed = (projectRoot.orEmpty().hashCode() * 31 + workFocus.hashCode()).absoluteValue
        return pool[seed % pool.size]
    }
}
