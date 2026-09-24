package com.hvkeyn.ceditneuro.tools

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class UiLayoutCompactorTest {
    @Test
    fun dropsEmptyLayoutsAndKeepsTheButtonCenter() {
        val xml = hierarchy(
            node(clazz = "android.widget.FrameLayout", bounds = "0,0,1080,2400"),
            node(clazz = "android.widget.Space", bounds = "0,0,1080,48"),
            node(clazz = "android.widget.LinearLayout", bounds = "40,400,1040,560", clickable = true),
            node(clazz = "android.widget.TextView", text = "Settings", bounds = "80,430,600,530"),
            node(clazz = "android.widget.TextView", text = "", bounds = "0,2200,1080,2400"),
        )
        val report = UiLayoutCompactor.compact(xml)
        assertTrue(report.text.contains("tap=540,480"))
        assertTrue(report.text.contains("\"Settings\""))
        assertTrue(report.text.contains("click"))
        assertFalse(report.text.contains("Space"))
        assertFalse(report.text.contains("FrameLayout"))
        assertTrue(report.kept < report.rawNodes)
    }

    @Test
    fun hidesPasswordTextAndDuplicateBounds() {
        val xml = hierarchy(
            node(
                clazz = "android.widget.EditText",
                text = "secret-value",
                bounds = "40,100,800,220",
                password = true,
            ),
            node(clazz = "android.widget.TextView", text = "Title", bounds = "40,300,400,380"),
            node(clazz = "android.view.View", text = "Title", bounds = "40,300,400,380"),
        )
        val report = UiLayoutCompactor.compact(xml)
        assertFalse(report.text.contains("secret-value"))
        assertTrue(report.text.contains("password"))
        assertTrue(report.text.lines().count { it.contains("\"Title\"") } == 1)
    }

    @Test
    fun shrinksALargeHierarchy() {
        val filler = buildString {
            repeat(700) { index ->
                append(node(clazz = "android.widget.FrameLayout", bounds = "0,$index,1080,${index + 1}"))
                append(node(clazz = "android.widget.Space", bounds = "0,${index + 2},20,${index + 3}"))
            }
        }
        val xml = hierarchy(
            filler,
            node(clazz = "android.widget.Button", text = "Save", bounds = "200,1800,880,1960", clickable = true),
            node(
                clazz = "androidx.recyclerview.widget.RecyclerView",
                bounds = "0,200,1080,1700",
                scrollable = true,
            ),
        )
        val report = UiLayoutCompactor.compact(xml)
        assertTrue("raw dump should be large", report.rawChars > 50_000)
        assertTrue("compact list should stay small: ${report.text.length}", report.text.length < 8_000)
        assertTrue(report.kept < 30)
        assertTrue(report.text.contains("\"Save\""))
        assertTrue(report.text.contains("scroll"))
        assertTrue(report.rawChars / report.text.length >= 10)
    }

    @Test
    fun compactsARealDumpWhenProvided() {
        val path = System.getenv("UI_DUMP") ?: return
        val xml = File(path).readText()
        val report = UiLayoutCompactor.compact(xml)
        println("REAL raw_chars=${report.rawChars} out_chars=${report.text.length} kept=${report.kept}/${report.rawNodes}")
        assertTrue(report.rawNodes > 0)
        assertTrue(report.text.length < report.rawChars / 5)
        assertTrue(report.kept < report.rawNodes)
    }

    private fun hierarchy(vararg parts: String): String =
        """<?xml version='1.0' encoding='UTF-8' standalone='yes' ?>
<hierarchy rotation="0">
${parts.joinToString("\n")}
</hierarchy>
"""

    private fun node(
        clazz: String,
        bounds: String,
        text: String = "",
        clickable: Boolean = false,
        password: Boolean = false,
        scrollable: Boolean = false,
    ): String {
        val box = bounds.split(',')
        val rendered = "[${box[0]},${box[1]}][${box[2]},${box[3]}]"
        return """<node text="$text" resource-id="" class="$clazz" package="com.example" content-desc="" checkable="false" checked="false" clickable="$clickable" enabled="true" focusable="false" focused="false" scrollable="$scrollable" long-clickable="false" password="$password" selected="false" bounds="$rendered" />"""
    }
}
