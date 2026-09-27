package com.hvkeyn.ceditneuro.agent

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class DebugCaseTest {
    private val check = "gradlew test"

    @Test
    fun aFixNeedsAFailedCheckAFactAndASupportingTest() {
        val root = Files.createTempDirectory("debug-case").toFile()
        assertTrue(step(root, "cause", mapOf("text" to "the map is empty")).error)
        assertTrue(step(root, "open", mapOf("symptom" to "the test fails", "steps" to "run the unit test")).text.contains("Do not propose a fix"))
        assertTrue(step(root, "hypothesis", mapOf("text" to "the map is empty")).error)
        val seen = step(root, "reproduce", mapOf("check" to check, "result" to "fail"))
        assertTrue(seen.text.contains("seen: fail"))
        assertFalse(step(root, "fact", mapOf("text" to "line 12 returns null", "source" to "read_file Foo.kt")).error)
        assertFalse(step(root, "hypothesis", mapOf("text" to "the map is empty")).error)
        assertTrue(step(root, "cause", mapOf("text" to "the map is empty")).error)
        val ruled = step(root, "test", mapOf("id" to "1", "check" to "grep map", "result" to "rules-out"))
        assertTrue(ruled.text.contains("ruled out"))
        assertTrue(step(root, "cause", mapOf("text" to "the map is empty")).error)
        assertFalse(step(root, "hypothesis", mapOf("text" to "the key is never set")).error)
        assertFalse(step(root, "test", mapOf("id" to "2", "check" to "read_file Bar.kt", "result" to "supports")).error)
        assertFalse(step(root, "cause", mapOf("text" to "Bar never sets the key")).error)
        assertTrue(step(root, "verify", mapOf("check" to check, "result" to "pass")).error)
    }

    @Test
    fun onlyTheSameCheckCanPassTheCase() {
        val root = Files.createTempDirectory("debug-case-pass").toFile()
        openThroughCause(root)
        assertFalse(step(root, "fix", mapOf("text" to "set the key in Bar")).error)
        val swapped = step(root, "verify", mapOf("check" to "a different test", "result" to "pass"))
        assertTrue(swapped.error)
        assertTrue(swapped.text.contains(check))
        val passed = step(root, "verify", mapOf("check" to check, "result" to "pass"))
        assertTrue(passed.text.contains("pass: yes"))
        assertTrue(DebugCase.file(root).readText().contains("pass: yes"))
    }

    @Test
    fun threeFailedFixesStopAFourth() {
        val root = Files.createTempDirectory("debug-case-stop").toFile()
        openThroughCause(root)
        repeat(3) {
            assertFalse(step(root, "fix", mapOf("text" to "try $it")).error)
            val failed = step(root, "verify", mapOf("check" to check, "result" to "fail"))
            assertTrue(failed.text.contains("still fails"))
        }
        val fourth = step(root, "fix", mapOf("text" to "try again"))
        assertTrue(fourth.error)
        assertTrue(fourth.text.contains("Do not try a fourth fix"))
    }

    @Test
    fun aPassingRunIsNotABugAndAKeyIsRejected() {
        val root = Files.createTempDirectory("debug-case-guard").toFile()
        step(root, "open", mapOf("symptom" to "the test fails", "steps" to "run it"))
        val fine = step(root, "reproduce", mapOf("check" to check, "result" to "pass"))
        assertTrue(fine.text.contains("Do not change the code"))
        val key = "sk-" + "abcdef1234567890"
        val stored = step(root, "open", mapOf("symptom" to key, "steps" to "run it"))
        assertTrue(stored.error)
        assertFalse(DebugCase.file(root).readText().contains(key))
    }

    private fun openThroughCause(root: java.io.File) {
        step(root, "open", mapOf("symptom" to "the test fails", "steps" to "run the unit test"))
        step(root, "reproduce", mapOf("check" to check, "result" to "fail"))
        step(root, "fact", mapOf("text" to "line 12 returns null", "source" to "read_file Foo.kt"))
        step(root, "hypothesis", mapOf("text" to "the key is never set"))
        step(root, "test", mapOf("id" to "1", "check" to "read_file Bar.kt", "result" to "supports"))
        step(root, "cause", mapOf("text" to "Bar never sets the key"))
    }

    private fun step(root: java.io.File, action: String, fields: Map<String, String>): SkillNote =
        DebugCase.apply(root, action, fields)
}
