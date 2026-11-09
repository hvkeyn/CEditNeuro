package com.hvkeyn.ceditneuro.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

@Serializable
data class StoredThread(
    val id: String,
    val title: String,
    val archived: Boolean = false,
    val createdAt: Long = 0L,
    val session: StoredSession = StoredSession(),
)

@Serializable
data class StoredThreadIndex(
    val activeId: String = "main",
    val threads: List<StoredThread> = listOf(
        StoredThread(id = "main", title = "Main chat", createdAt = 0L),
    ),
)

/** Chat threads for one project folder. A thread has its own conversation, job, and UI chat. */
class ChatThreadStore(context: Context) {
    private val dir = File(context.filesDir, "threads").apply { mkdirs() }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }
    private val lock = Any()

    fun load(root: String): StoredThreadIndex = synchronized(lock) {
        val file = fileFor(root)
        if (!file.isFile) return StoredThreadIndex()
        runCatching { json.decodeFromString<StoredThreadIndex>(file.readText()) }
            .getOrDefault(StoredThreadIndex())
    }

    fun save(root: String, index: StoredThreadIndex) = synchronized(lock) {
        fileFor(root).writeText(json.encodeToString(index))
    }

    fun forget(root: String) = synchronized(lock) { fileFor(root).delete() }

    fun ensureMigrated(root: String, legacy: StoredSession): StoredThreadIndex = synchronized(lock) {
        val file = fileFor(root)
        if (file.isFile) return load(root)
        val index = StoredThreadIndex(
            activeId = "main",
            threads = listOf(StoredThread(id = "main", title = "Main chat", session = legacy)),
        )
        file.writeText(json.encodeToString(index))
        index
    }

    private fun fileFor(root: String): File {
        val digest = MessageDigest.getInstance("SHA-256").digest(root.toByteArray(Charsets.UTF_8))
        return File(dir, digest.joinToString("") { "%02x".format(it) } + ".json")
    }
}
