package com.hvkeyn.ceditneuro.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class UsageLedgerTest {
    @Test
    fun recordsRoundTripAndPriceCachedTokens() {
        val dir = Files.createTempDirectory("usage").toFile()
        try {
            val ledger = UsageLedger(UsageLedger.file(dir))
            val row = UsageRecord(
                timestamp = 1_000,
                project = "Vdo",
                provider = "DeepSeek",
                model = "flash",
                role = "main",
                focus = "edit",
                promptTokens = 1_000_000,
                completionTokens = 100_000,
                cachedTokens = 400_000,
                latencyMs = 1_200,
            )
            ledger.append(row)
            val loaded = ledger.load()
            assertEquals(1, loaded.size)
            assertEquals(row, loaded.first())
            val cost = ledger.costOf(row, inputRate = 1.0, outputRate = 2.0, cachedRate = 0.25)
            assertEquals(0.6 + 0.1 + 0.2, cost, 0.0001)
            val totals = ledger.totals(loaded, 0, 1.0, 2.0, 0.25)
            assertEquals(0.4, totals.cacheRate, 0.0001)
            assertEquals(1, totals.requests)
            assertTrue(totals.tokens == 1_100_000)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun malformedLinesAreSkipped() {
        val dir = Files.createTempDirectory("usage-bad").toFile()
        try {
            val file = UsageLedger.file(dir)
            file.writeText("{not json}\n")
            assertTrue(UsageLedger(file).load().isEmpty())
        } finally {
            dir.deleteRecursively()
        }
    }
}
