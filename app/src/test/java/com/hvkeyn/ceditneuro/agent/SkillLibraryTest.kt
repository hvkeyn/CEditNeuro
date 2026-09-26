package com.hvkeyn.ceditneuro.agent

import com.hvkeyn.ceditneuro.data.StoredChat
import com.hvkeyn.ceditneuro.data.StoredSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class SkillLibraryTest {
    @Test
    fun saveReadAppendAndDelete() {
        val root = Files.createTempDirectory("skills-project").toFile()
        val phone = Files.createTempDirectory("skills-phone").toFile()
        val skills = SkillLibrary(root, phone)
        val saved = skills.save("Check mail", "List unread mail, then summarize it.", "app")
        assertFalse(saved.error)
        assertEquals("List unread mail, then summarize it.", skills.read("check-mail", "phone").text)
        val extended = skills.append("check-mail", "Do not send a reply.", "app")
        assertFalse(extended.error)
        assertTrue(skills.read("check-mail", "app").text.contains("Do not send a reply."))
        assertTrue(skills.catalog().contains("check-mail (app):"))
        assertFalse(skills.delete("check-mail", "app").error)
        assertTrue(skills.read("check-mail", "app").error)
        root.deleteRecursively()
        phone.deleteRecursively()
    }

    @Test
    fun rejectsAKeyAndKeepsProjectMemoryShort() {
        val root = Files.createTempDirectory("memory-project").toFile()
        val skills = SkillLibrary(root, Files.createTempDirectory("memory-phone").toFile())
        assertTrue(skills.save("notes", "token sk-abcdef1234567890", "project").error)
        assertTrue(ProjectMemory.store(root, "The folder holds sample books.", append = true).text.contains("Saved"))
        assertTrue(ProjectMemory.store(root, "Use the sample file.", append = true).text.contains("Saved"))
        assertTrue(ProjectMemory.read(root).contains("sample books"))
        assertTrue(ProjectMemory.read(root).contains("sample file"))
        val huge = "x".repeat(ProjectMemory.MAX_CHARS + 50)
        ProjectMemory.store(root, huge, append = false)
        assertEquals(ProjectMemory.MAX_CHARS, ProjectMemory.read(root).length)
        root.deleteRecursively()
    }

    @Test
    fun searchRedactsSecretsAndStaysInThisProject() {
        val session = StoredSession(
            chat = listOf(
                StoredChat(1, "User", "Convert the sample file again."),
                StoredChat(2, "Assistant", """password is "password":"hidden-value" and key sk-abcdef1234567890"""),
            ),
        )
        val found = SessionSearch.query(session, "sample file")
        assertTrue(found.contains("User:"))
        assertTrue(found.contains("sample file"))
        val secret = SessionSearch.query(session, "password")
        assertFalse(secret.contains("hidden-value"))
        assertFalse(secret.contains("sk-abcdef"))
        assertEquals(
            "No earlier message in this project contains that text.",
            SessionSearch.query(session, "other-project-only"),
        )
    }
}
