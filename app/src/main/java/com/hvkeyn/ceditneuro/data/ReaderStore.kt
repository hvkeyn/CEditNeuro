package com.hvkeyn.ceditneuro.data

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class ReaderNote(val page: Int, val text: String, val createdAt: Long)

@Serializable
data class InkPoint(val x: Float, val y: Float)

@Serializable
data class PageInk(
    val id: String,
    val page: Int,
    val color: Long,
    val width: Float,
    val points: List<InkPoint>,
)

@Serializable
data class PageLabel(
    val id: String,
    val page: Int,
    val text: String,
    val x: Float,
    val y: Float,
    val scale: Float = 1f,
    val rotation: Float = 0f,
    val color: Long = 0xFF8C2F2F,
)

@Serializable
data class ReaderRecord(
    val path: String,
    val page: Int = 0,
    val fraction: Float = 0f,
    val theme: String = "sepia",
    val fontSp: Int = 20,
    val fontName: String = "serif",
    val spacing: Float = 1.45f,
    val bookmarks: List<Int> = emptyList(),
    val notes: List<ReaderNote> = emptyList(),
    val ink: List<PageInk> = emptyList(),
    val labels: List<PageLabel> = emptyList(),
)

/** Reading position, bookmarks, and notes. Stored only on this phone, never in the project. */
class ReaderStore(context: Context) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val file = File(context.filesDir, "reader-progress.json")
    private val records = load()

    fun read(path: String): ReaderRecord = records[path] ?: ReaderRecord(path)

    fun write(record: ReaderRecord) {
        records[record.path] = record.copy(
            notes = record.notes.takeLast(200),
            bookmarks = record.bookmarks.takeLast(200),
            ink = record.ink.takeLast(400),
            labels = record.labels.takeLast(200),
        )
        file.writeText(json.encodeToString(records.values.toList()))
    }

    private fun load(): MutableMap<String, ReaderRecord> {
        if (!file.isFile) return linkedMapOf()
        val list = runCatching { json.decodeFromString<List<ReaderRecord>>(file.readText()) }.getOrDefault(emptyList())
        return list.associateByTo(linkedMapOf()) { it.path }
    }
}
