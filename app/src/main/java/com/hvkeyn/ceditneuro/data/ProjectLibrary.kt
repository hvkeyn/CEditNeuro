package com.hvkeyn.ceditneuro.data

import android.content.Context
import com.hvkeyn.ceditneuro.agent.ChatMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/**
 * Remembers project folders and, for each one, the chat and shell the user already saw.
 * Stored in the app's private files dir. Paths only — no API keys.
 */
class ProjectLibrary(context: Context) {

    var afterChange: () -> Unit = {}

    private val dir = File(context.filesDir, "projects").apply { mkdirs() }
    private val indexFile = File(dir, "index.json")
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }
    private val lock = Any()

    fun roots(): List<String> = synchronized(lock) { readRoots() }

    fun load(root: String): StoredSession = synchronized(lock) {
        val file = sessionFile(root)
        if (!file.isFile) return StoredSession()
        runCatching { json.decodeFromString<StoredSession>(file.readText()) }.getOrDefault(StoredSession())
    }

    /** Moves [root] to the front of the list and writes its chat and shell. */
    fun save(root: String, session: StoredSession) = synchronized(lock) {
        val roots = readRoots().toMutableList()
        roots.removeAll { it == root }
        roots.add(0, root)
        writeRoots(roots)
        sessionFile(root).writeText(json.encodeToString(session))
        afterChange()
    }

    /** Writes a session without moving [root] to the front of the list. */
    fun updateSession(root: String, session: StoredSession) = synchronized(lock) {
        if (root !in readRoots()) return
        sessionFile(root).writeText(json.encodeToString(session))
        afterChange()
    }

    fun forget(root: String) = synchronized(lock) {
        writeRoots(readRoots().filterNot { it == root })
        sessionFile(root).delete()
        afterChange()
    }

    fun replaceRoots(roots: List<String>) = synchronized(lock) {
        writeRoots(roots.distinct())
        afterChange()
    }

    fun snapshot(): Pair<List<String>, Map<String, StoredSession>> = synchronized(lock) {
        val roots = readRoots()
        roots to roots.associateWith { root -> readSession(root) }
    }

    /** Replaces the saved project list. Session files for roots that are gone are removed. */
    fun replaceAll(roots: List<String>, sessions: Map<String, StoredSession>) = synchronized(lock) {
        val kept = roots.distinct()
        dir.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".json") && file.name != "index.json") file.delete()
        }
        writeRoots(kept)
        kept.forEach { root ->
            sessionFile(root).writeText(json.encodeToString(sessions[root] ?: StoredSession()))
        }
        afterChange()
    }

    private fun readRoots(): List<String> {
        if (!indexFile.isFile) return emptyList()
        return runCatching { json.decodeFromString<ProjectIndex>(indexFile.readText()).roots }
            .getOrDefault(emptyList())
    }

    private fun writeRoots(roots: List<String>) {
        indexFile.writeText(json.encodeToString(ProjectIndex(roots)))
    }

    private fun readSession(root: String): StoredSession {
        val file = sessionFile(root)
        if (!file.isFile) return StoredSession()
        return runCatching { json.decodeFromString<StoredSession>(file.readText()) }.getOrDefault(StoredSession())
    }

    private fun sessionFile(root: String): File = File(dir, "${sha256(root)}.json")

    private fun sha256(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { byte -> "%02x".format(byte) }
    }
}

@Serializable
data class ProjectIndex(val roots: List<String> = emptyList())

@Serializable
data class StoredChat(
    val id: Long,
    val role: String,
    val text: String,
    val toolName: String? = null,
)

@Serializable
data class StoredShell(
    val id: Long,
    val command: String,
    val output: String,
)

@Serializable
data class StoredSession(
    val chat: List<StoredChat> = emptyList(),
    val conversation: List<ChatMessage> = emptyList(),
    val shell: List<StoredShell> = emptyList(),
    val nextId: Long = 0,
)
