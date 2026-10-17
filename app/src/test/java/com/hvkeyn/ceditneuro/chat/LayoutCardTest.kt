package com.hvkeyn.ceditneuro.chat

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LayoutCardTest {
    @Test
    fun aPlainListBecomesACard() {
        val page = LayoutCard.page("[x] milk\n[ ] bread")
        requireNotNull(page)
        assertTrue(page.contains("milk"))
        assertTrue(page.contains("✓"))
        assertTrue(page.contains("<html"))
    }

    @Test
    fun aScriptAndAHandlerAreRemoved() {
        val page = LayoutCard.page("<div onclick=\"alert(1)\"><script>bad</script>Hi</div>")
        requireNotNull(page)
        assertTrue(page.contains("Hi"))
        assertFalse(page.contains("script"))
        assertFalse(page.contains("onclick"))
        assertFalse(page.contains("alert"))
    }
}
