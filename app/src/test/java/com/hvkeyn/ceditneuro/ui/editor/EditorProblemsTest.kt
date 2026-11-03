package com.hvkeyn.ceditneuro.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EditorProblemsTest {
    @Test
    fun acceptsBalancedCodeAndIgnoresStringsAndComments() {
        val text = """
            class A {
                fun f() {
                    val s = "(not a problem"
                    // ] ignored
                }
            }
        """.trimIndent()
        assertTrue(findProblems(text).isEmpty())
    }

    @Test
    fun reportsUnclosedAndUnmatchedBrackets() {
        val text = "fun f() {\n  val x = [1, 2\n}\n"
        val problems = findProblems(text)
        assertTrue(problems.any { it.message == "Unclosed [" })
        assertTrue(problems.any { it.message == "Unmatched }" })
    }

    @Test
    fun markupUsesMergeMarkersOnly() {
        assertTrue(findProblems("<div>{</div>", "index.html").isEmpty())
        assertEquals(1, findProblems("<<<<<<< HEAD\na\n", "index.html").size)
    }

    @Test
    fun reportsMergeMarkersWithTheirLine() {
        val problems = findProblems("a\n<<<<<<< HEAD\nb\n=======\nc\n>>>>>>> other\n")
        assertEquals(3, problems.count { it.message == "Unresolved merge marker" })
    }
}
