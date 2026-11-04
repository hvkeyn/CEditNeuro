package com.hvkeyn.ceditneuro.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileControlTest {
    private val xml = """
        <hierarchy rotation="0">
          <node class="android.widget.EditText" package="com.example" text="Search" content-desc="" resource-id="com.example:id/search" clickable="true" long-clickable="false" checkable="false" checked="false" scrollable="false" enabled="true" password="false" bounds="[10,20][210,90]" />
          <node class="android.widget.EditText" package="com.example" text="hunter2" content-desc="" resource-id="com.example:id/password" clickable="true" long-clickable="false" checkable="false" checked="false" scrollable="false" enabled="true" password="true" bounds="[10,100][210,170]" />
          <node class="android.widget.Button" package="com.example" text="Go" content-desc="" resource-id="com.example:id/go" clickable="true" long-clickable="false" checkable="false" checked="false" scrollable="false" enabled="true" password="false" bounds="[10,200][210,260]" />
        </hierarchy>
    """.trimIndent()

    @Test
    fun parsesRefsBoundsAndActions() {
        val snapshot = MobileUiParser.parse(xml, "abc")
        assertEquals(3, snapshot.nodes.size)
        val search = snapshot.nodes.first()
        assertTrue(search.ref.startsWith("@abc:e"))
        assertEquals("Search", search.label)
        assertTrue("set_text" in search.actions)
        assertTrue(search.cx == 110 && search.cy == 55)
        assertTrue(snapshot.render().contains(search.ref))
    }

    @Test
    fun passwordTextIsNeverExposed() {
        val snapshot = MobileUiParser.parse(xml, "abc")
        val password = snapshot.nodes.first { it.password }
        assertEquals("", password.text)
        assertTrue(snapshot.render().contains("password"))
        assertTrue(!snapshot.render().contains("hunter2"))
    }

    @Test
    fun collectorMergesOverlappingPagesWithoutDuplicating() {
        assertEquals(listOf("a", "b", "c"), MobileTextCollector.appendPage(listOf("a", "b"), listOf("b", "c")))
        assertEquals(listOf("a", "b", "c"), MobileTextCollector.prependPage(listOf("b", "c"), listOf("a", "b")))
        assertEquals(listOf("a", "b"), MobileTextCollector.appendPage(listOf("a", "b"), emptyList()))
    }

    @Test
    fun staleRefsAreRejected() {
        val store = MobileUiStore()
        val snapshot = MobileUiParser.parse(xml, "abc")
        store.store(snapshot)
        assertTrue(store.requireCurrent(snapshot.nodes.first().ref) != null)
        assertNull(store.requireCurrent("@old:e0"))
    }
}
