package com.hvkeyn.ceditneuro.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OutlineDialogTest {
    @Test
    fun findsClassesFunctionsAndHeadings() {
        val text = """
            # Title
            class Main {
                fun hello() {}
            }
            def work():
                pass
        """.trimIndent()
        val symbols = outlineSymbols(text)
        assertTrue(symbols.any { it.label == "Main" })
        assertTrue(symbols.any { it.label == "hello" })
        assertTrue(symbols.any { it.label == "work" })
        assertTrue(symbols.any { it.label.contains("Title") })
    }

    @Test
    fun keepsSourceLineNumbers() {
        val symbols = outlineSymbols("// a\nclass A\nfun b()")
        assertEquals(1, symbols.first { it.label == "A" }.line)
        assertEquals(2, symbols.first { it.label == "b" }.line)
    }

    @Test
    fun ignoresComments() {
        val symbols = outlineSymbols("// class Fake\n<!-- fun nope -->\nclass Real")
        assertEquals(listOf("Real"), symbols.map { it.label })
    }
}
