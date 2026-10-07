package com.hvkeyn.ceditneuro.agent

import com.hvkeyn.ceditneuro.tools.PageCheck
import com.hvkeyn.ceditneuro.tools.ToolGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WebReachTest {
    @Test
    fun aCheckPageIsNotContent() {
        val reader = """
            Title: Just a moment...

            URL Source: https://example.org/a

            Warning: This page maybe requiring CAPTCHA, please make sure you are authorized to access this page.

            Markdown Content:
            ## Performing security verification
        """.trimIndent()
        assertTrue(PageCheck.isChallenge(reader))
        val cloudflare = "<html><head><title>Attention Required! | Cloudflare</title></head><body>Ray ID: 1</body></html>"
        assertTrue(PageCheck.isChallenge(cloudflare))
        val challenge = "<html><head><title>Just a moment...</title></head><script src=\"/cdn-cgi/challenge-platform/h/b\"></script>"
        assertTrue(PageCheck.isChallenge(challenge))
        assertFalse(PageCheck.isChallenge("Title: Example Domain\n\nMarkdown Content:\nThis domain is for use in documentation examples."))
        assertFalse(PageCheck.isChallenge("An article that explains what Just a moment... means in a story."))
    }

    @Test
    fun theReaderSaysWhenTheSiteRefusedIt() {
        val refused = "Title: \n\nURL Source: https://www.reddit.com/r/Android/\n\nWarning: Target URL returned error 403: Forbidden\n\nMarkdown Content:\nblocked"
        assertEquals(403, PageCheck.readerRefusal(refused))
        assertNull(PageCheck.readerRefusal("Title: Example\n\nWarning: This is a cached snapshot of the original page."))
    }

    @Test
    fun theReaderGetsOnlyAPublicPage() {
        assertTrue(PageCheck.isPublic("https://example.com/a?b=1"))
        assertTrue(PageCheck.isPublic("http://8.8.8.8/"))
        listOf(
            "http://127.0.0.1:8791/",
            "http://localhost/",
            "http://192.168.1.20/",
            "http://10.0.0.5/",
            "http://172.20.1.1/",
            "http://169.254.169.254/latest/meta-data",
            "http://[::1]/",
            "http://printer.local/",
            "http://router.lan/",
            "http://metadata.google.internal/",
            "https://user:secret@example.com/",
            "file:///sdcard/a.html",
            "javascript:alert(1)",
            "http://intranet/",
        ).forEach { assertFalse(it, PageCheck.isPublic(it)) }
        assertEquals("https://r.jina.ai/https://example.com/", PageCheck.readerUrl("https://example.com/"))
        assertNull(PageCheck.readerUrl("http://192.168.1.1/"))
        assertEquals("http://10.0.0.1/", PageCheck.readerTarget("https://r.jina.ai/http://10.0.0.1/"))
        assertNull(PageCheck.readerTarget("https://example.com/"))
    }

    @Test
    fun theSkillKeepsTheRulesAndNoLogin() {
        val skill = StarterSkills.skills.getValue("web-reach")
        listOf(
            "Panniantong/agent-reach (MIT)",
            "Say which route served each page",
            "is not content",
            "Stop at the first real content",
            "r.jina.ai",
            "Do not log in for the user",
            "Do not read browser cookies",
            "web_search",
            "video_brief",
            "Reddit has no route without a login",
        ).forEach { assertTrue("web-reach lacks $it", skill.contains(it)) }
        assertFalse(skill.contains("curl"))
        assertFalse(skill.contains("yt-dlp"))
        assertFalse(skill.contains("opencli"))
        assertFalse(SkillAudit.check(skill, "web-reach").blocked)
        assertTrue(buildSystemPrompt().contains("use_skill web-reach"))
        assertTrue("web_search" in ToolGroups.groups.getValue("study"))
        assertTrue("web_search" in ToolGroups.groups.getValue("video"))
    }
}
