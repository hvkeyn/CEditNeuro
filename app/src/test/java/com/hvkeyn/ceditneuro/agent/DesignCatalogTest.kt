package com.hvkeyn.ceditneuro.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DesignCatalogTest {
    @Test
    fun namedSystemsResolveToTheGallery() {
        assertTrue(DesignCatalog.systems.size >= 100)
        assertEquals("material-design", DesignCatalog.find("Material 3").single().slug)
        assertEquals("apple-hig", DesignCatalog.find("Apple").single().slug)
        val polaris = DesignCatalog.find("Polaris").single()
        assertEquals("Shopify", polaris.vendor)
        assertTrue(polaris.swatches.contains("#d72c0d"))
        val text = DesignCatalog.format("Polaris", listOf(polaris))
        assertTrue(text.contains("https://www.designsystems.one/design-systems/polaris"))
        assertTrue(text.contains("Do not invent a hex"))
        assertTrue(DesignCatalog.format("not-a-system-zz", emptyList()).contains("designsystems.one/design-systems"))
    }

    @Test
    fun frontendSkillIsNotBlocked() {
        val text = StarterSkills.skills.getValue("frontend-design")
        val audit = SkillAudit.check(text, "frontend-design")
        assertFalse(audit.blocked)
        assertTrue(text.contains("design_system"))
        assertTrue(text.contains("styles.refero.design"))
        assertTrue(text.contains("48dp"))
        assertTrue(text.contains("44pt"))
        assertTrue(text.contains("ai-ui-design-google-ai-studio"))
        assertTrue(text.contains("Do not mix them"))
        val video = StarterSkills.skills.getValue("hyperframes")
        assertFalse(SkillAudit.check(video, "hyperframes").blocked)
        assertTrue(video.contains("skills/hyperframes-core/SKILL.md"))
        assertTrue(video.contains("was not encoded"))
        assertTrue(video.contains("If the registry was missing"))
    }

    @Test
    fun anOldDesignSkillGainsTheSurfaces() {
        val dir = java.nio.file.Files.createTempDirectory("design-skill").toFile()
        val file = java.io.File(dir, "frontend-design.md")
        file.writeText(
            "# Design a page\n2. call design_system with that name.\n" +
                "If load_tools says the group has no tools, http_request that system's public page instead.\n",
        )
        StarterSkills.ensure(dir)
        val saved = file.readText()
        assertTrue(saved.startsWith("# Design a page"))
        assertTrue(saved.contains("styles.refero.design"))
        assertTrue(saved.contains("component.gallery"))
        assertTrue(saved.contains("no HyperFrames"))
        assertTrue(saved.contains("use_skill hyperframes"))
        assertTrue(saved.contains("ai-ui-design-google-ai-studio"))
    }

    @Test
    fun anOldHyperframesSkillLearnsToPlay() {
        val dir = java.nio.file.Files.createTempDirectory("hyperframes-skill").toFile()
        val file = java.io.File(dir, "hyperframes.md")
        file.writeText("# Make a video as HTML\ndata-composition-id\nwindow.__timelines\n")
        java.io.File(dir, ".starter-written").writeText("hyperframes\n")
        StarterSkills.ensure(dir)
        val saved = file.readText()
        assertTrue(saved.contains("If the registry was missing"))
        assertTrue(saved.contains("render_video"))
        assertTrue(saved.contains("was not encoded"))
        assertTrue(saved.contains("make_music"))
        assertTrue(saved.contains("sound_effect"))
        assertTrue(saved.contains("voiceover"))
        val rendered = "# Make a video as HTML\ndata-composition-id\nrender_video\n"
        file.writeText(rendered)
        StarterSkills.ensure(dir)
        assertTrue(file.readText().contains("make_music"))
    }
}
