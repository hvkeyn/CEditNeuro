package com.hvkeyn.ceditneuro.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrailMatchTest {
    private val trails = """
        evil.example loader
        evil.example/gate dropper
        203.0.113.50 scanner
        203.0.113.50:4444 beacon
        ua:BadAgent implant
        !good.example
    """.trimIndent()

    @Test
    fun aChildNameMatchesTheListedParent() {
        val hits = TrailMatch.scan(
            TrailMatch.parse(trails),
            listOf(TrailMatch.Observation(dns = "www.sub.evil.example")),
            heuristics = false,
        )
        assertEquals("DNS", hits.single().type)
        assertEquals("evil.example", hits.single().trail)
        assertEquals("loader", hits.single().info)
    }

    @Test
    fun aUrlIsCheckedBeforeTheHostAlone() {
        val hits = TrailMatch.scan(
            TrailMatch.parse(trails),
            listOf(TrailMatch.Observation(host = "evil.example", path = "/gate/x")),
            heuristics = false,
        )
        assertEquals("URL", hits.first().type)
        assertEquals("evil.example/gate", hits.first().trail)
    }

    @Test
    fun anAddressAndAPortAndAnAgentMatch() {
        val book = TrailMatch.parse(trails)
        val ip = TrailMatch.scan(book, listOf(TrailMatch.Observation(dst = "203.0.113.50")), heuristics = false)
        assertEquals("IP", ip.single().type)
        val port = TrailMatch.scan(
            book,
            listOf(TrailMatch.Observation(dst = "203.0.113.50", dport = 4444)),
            heuristics = false,
        )
        assertTrue(port.any { it.type == "IPORT" && it.trail == "203.0.113.50:4444" })
        val agent = TrailMatch.scan(
            book,
            listOf(TrailMatch.Observation(agent = "BadAgent/1.2")),
            heuristics = false,
        )
        assertEquals("UA", agent.single().type)
    }

    @Test
    fun aWhitelistDropsTheChildName() {
        val hits = TrailMatch.scan(
            TrailMatch.parse(trails),
            listOf(TrailMatch.Observation(dns = "a.good.example")),
            heuristics = false,
        )
        assertTrue(hits.isEmpty())
    }

    @Test
    fun aRandomLabelIsAHeuristicAndAWordIsNot() {
        val random = TrailMatch.scan(
            TrailMatch.parse(""),
            listOf(TrailMatch.Observation(dns = "xkqzvwpbmnrt.example")),
        )
        assertTrue(random.any { it.info == "dga-like" })
        val word = TrailMatch.scan(
            TrailMatch.parse(""),
            listOf(TrailMatch.Observation(dns = "documentation.example")),
        )
        assertFalse(word.any { it.info == "dga-like" })
    }

    @Test
    fun manyPortsFromOneAddressAreAScanShape() {
        val rows = (1..12).map { TrailMatch.Observation(src = "203.0.113.2", dst = "198.51.100.9", dport = 1000 + it) }
        val hits = TrailMatch.scan(TrailMatch.parse(""), rows)
        assertTrue(hits.any { it.info.startsWith("scan-ports") })
    }
}
