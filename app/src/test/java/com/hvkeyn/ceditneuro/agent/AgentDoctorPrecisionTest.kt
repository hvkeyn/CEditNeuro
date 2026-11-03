package com.hvkeyn.ceditneuro.agent

import com.hvkeyn.ceditneuro.data.StoredSession
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentDoctorPrecisionTest {
    private fun session(vararg lines: String) = StoredSession(
        conversation = lines.mapIndexed { index, text ->
            ChatMessage(role = "tool", name = "tool-$index", content = text)
        },
    )

    @Test
    fun namesActionableToolInputFailures() {
        val report = AgentDoctor.report(
            mapOf(
                "/storage/p" to session(
                    "Search failed on DuckDuckGo, Bing, and Mojeek. Mojeek HTML: no results.",
                    "locator is a URL, a DOI, an arXiv id, or a project path that a tool printed.",
                    "data is required.",
                    "The reel does not cover its timeline. Fix the html, then render again.",
                    "HTTP 404 for https://example.test/missing",
                    "Connection reset",
                    "This exact http_request call already failed, so it was not run again.",
                    "The project is not a git repository.",
                    "Team is off in Settings. Solve alone, or turn Team on in the Skills menu.",
                ),
            ),
        )
        listOf(
            "search failed",
            "research input invalid",
            "chart input invalid",
            "The reel does not fill its declared timeline",
            "page not found",
            "connection failed",
            "duplicate failed call",
            "not a git repository",
            "team unavailable",
        ).forEach { assertTrue("missing $it", report.contains(it)) }
        assertTrue(report.contains("Change the query"))
        assertTrue(report.contains("measured rows"))
        assertTrue(report.contains("no .git"))
    }

    @Test
    fun repeatedCategoriesStayInDoctorSummary() {
        val repeated = AgentDoctor.measure(
            mapOf(
                "/storage/p" to StoredSession(
                    conversation = listOf(
                        ChatMessage(role = "tool", name = "http_request", content = "HTTP 404 for https://a.test/x"),
                        ChatMessage(role = "tool", name = "http_request", content = "HTTP 404 for https://b.test/y"),
                    ),
                ),
            ),
        )
        assertTrue(repeated.summary.contains("page not found"))
        assertTrue(repeated.repeated >= 1)
    }
}
