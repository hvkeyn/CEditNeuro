package com.hvkeyn.ceditneuro.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class ReaderNote(val page: Int, val text: String, val createdAt: Long)

@Serializable
data class ReaderRecord(
    val path: String,
    val page: Int = 0,
    val fraction: Float = 0f,
    val theme: String = "sepia",
    val fontSp: Int = 20,
    val bookmarks: List<Int> = emptyList(),
    val notes: List<ReaderNote> = emptyList(),
)

/** Reading position, bookmarks, and notes. Stored only on this phone, never in the project. */
class ReaderStore(context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file = File(context.filesDir, "reader-progress.json")
    private val records = load()

    fun read(path: String): ReaderRecord = records[path] ?: ReaderRecord(path)

    fun write(record: ReaderRecord) {
        records[record.path] = record.copy(notes = record.notes.takeLast(200), bookmarks = record.bookmarks.takeLast(200))
        file.writeText(json.encodeToString(records.values.toList()))
    }

    private fun load(): MutableMap<String, ReaderRecord> {
        if (!file.isFile) return linkedMapOf()
        val list = runCatching { json.decodeFromString<List<ReaderRecord>>(file.readText()) }.getOrDefault(emptyList())
        return list.associateByTo(linkedMapOf()) { it.path }
    }
}
