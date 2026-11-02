package com.hvkeyn.ceditneuro.agent

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

@Serializable
data class UsageRecord(
    val timestamp: Long,
    val project: String,
    val provider: String,
    val model: String,
    val role: String = "main",
    val focus: String = "",
    val promptTokens: Int,
    val completionTokens: Int,
    val cachedTokens: Int = 0,
    val latencyMs: Long = 0L,
) {
    val totalTokens: Int get() = promptTokens + completionTokens
}

data class UsageTotals(
    val requests: Int = 0,
    val promptTokens: Int = 0,
    val completionTokens: Int = 0,
    val cachedTokens: Int = 0,
    val latencyMs: Long = 0L,
    val cost: Double = 0.0,
) {
    val tokens: Int get() = promptTokens + completionTokens
    val cacheRate: Double get() = if (promptTokens == 0) 0.0 else cachedTokens.toDouble() / promptTokens
    val averageLatencyMs: Long get() = if (requests == 0) 0 else latencyMs / requests
}

/** JSON-lines ledger in app storage. No prompts, code, or keys are stored. */
class UsageLedger(private val file: File) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    @Synchronized
    fun append(record: UsageRecord) {
        file.parentFile?.mkdirs()
        file.appendText(json.encodeToString(UsageRecord.serializer(), record) + "\n")
        if (file.length() > 1_500_000L) compact()
    }

    @Synchronized
    fun load(): List<UsageRecord> {
        if (!file.isFile) return emptyList()
        return file.useLines { lines ->
            lines.mapNotNull { line ->
                runCatching { json.decodeFromString(UsageRecord.serializer(), line) }.getOrNull()
            }.toList()
        }.sortedBy { it.timestamp }
    }

    @Synchronized
    private fun compact() {
        val kept = load().takeLast(4000)
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(kept.joinToString("\n") { json.encodeToString(UsageRecord.serializer(), it) } + "\n")
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText())
            temp.delete()
        }
    }

    fun totals(
        records: List<UsageRecord>,
        since: Long,
        inputRate: Double,
        outputRate: Double,
        cachedRate: Double,
    ): UsageTotals {
        val selected = records.filter { it.timestamp >= since }
        return UsageTotals(
            requests = selected.size,
            promptTokens = selected.sumOf { it.promptTokens },
            completionTokens = selected.sumOf { it.completionTokens },
            cachedTokens = selected.sumOf { it.cachedTokens },
            latencyMs = selected.sumOf { it.latencyMs },
            cost = selected.sumOf {
                costOf(it, inputRate, outputRate, cachedRate)
            },
        )
    }

    fun costOf(record: UsageRecord, inputRate: Double, outputRate: Double, cachedRate: Double): Double {
        val uncached = (record.promptTokens - record.cachedTokens).coerceAtLeast(0)
        return (uncached / 1_000_000.0) * inputRate +
            (record.cachedTokens / 1_000_000.0) * cachedRate +
            (record.completionTokens / 1_000_000.0) * outputRate
    }

    companion object {
        fun file(filesDir: File): File = File(filesDir, "usage-ledger.jsonl")
    }
}
