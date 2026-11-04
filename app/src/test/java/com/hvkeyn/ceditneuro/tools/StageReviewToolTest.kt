package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.workspace.Workspace
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class StageReviewToolTest {
    private fun review(html: String): String {
        val dir = Files.createTempDirectory("stage-review").toFile()
        java.io.File(dir, "stage.html").writeText(html, Charsets.UTF_8)
        return runBlocking {
            StageReviewTool(Workspace(dir)).execute(buildJsonObject { put("path", "stage.html") }).content
        }
    }

    @Test
    fun catchesGeneratedLookingFaults() {
        val text = review(
            """<html><style>
              .stage{background:linear-gradient(#8b5cf6,#2563eb);overflow:visible}
              .card{backdrop-filter:blur(10px)}.card2{backdrop-filter:blur(8px)}.card3{backdrop-filter:blur(6px)}
              .copy{font-size:9px}
            </style><body><div class="stage"><h1>Welcome, Empowering</h1>
              <div class="card"></div><div class="card"></div><div class="card"></div>
              <button>✨</button><a href="#">Open</a><p>Lorem ipsum</p></div></body></html>""",
        )
        listOf("purple-to-blue", "glass", "emoji", "under 11px", "placeholder", "dead href", "focus-visible", "overflow:hidden")
            .forEach { assertTrue("missing $it in:\n$text", text.contains(it, ignoreCase = true)) }
    }

    @Test
    fun aQuietStageHasNoBlocker() {
        val text = review(
            """<html><style>
              body{margin:0;background:#f5f1e8;color:#191714;font-family:Georgia,serif}
              .stage{width:720px;height:340px;overflow:hidden;background:#f5f1e8}
              .window{width:680px;height:300px;background:#fff;border:1px solid #ddd}
              .button:focus-visible{outline:2px solid #191714}
            </style><body><div class="stage"><div class="window">
              <p>New invoice</p><button class="button">Send</button>
            </div></div></body></html>""",
        )
        assertTrue(text.contains("No static design fault"))
    }
}
