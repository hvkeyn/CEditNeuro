package com.hvkeyn.ceditneuro.tools

import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
    fun findReturnsOnlyMatchingSnapshotRefs() = runBlocking {
        val store = MobileUiStore()
        val node = MobileNode(
            ref = "@find:e1",
            role = "Button",
            text = "Save changes",
            desc = "",
            id = "save",
            x1 = 10,
            y1 = 20,
            x2 = 110,
            y2 = 80,
            clickable = true,
            longClickable = false,
            editable = false,
            scrollable = false,
            checkable = false,
            checked = false,
            enabled = true,
            password = false,
        )
        store.store(MobileSnapshot("find", 1080, 2400, "com.example", listOf(node)))
        val found = UiFindTool(store).execute(buildJsonObject { put("text", "save") })
        assertTrue(found.content.contains("@find:e1"))
        assertTrue(found.content.contains("matches=1"))
        val none = UiFindTool(store).execute(buildJsonObject { put("text", "delete") })
        assertTrue(none.content.contains("No match"))
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
