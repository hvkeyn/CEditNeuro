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
    }
}
