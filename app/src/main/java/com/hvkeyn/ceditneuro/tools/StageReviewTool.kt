package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.workspace.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File
import java.util.Locale

/**
 * Static review for a faux product UI. It catches the mechanical faults that make a
 * mockup look generated; it cannot judge composition from code. The agent still has
 * to open the frame and look at it.
 */
class StageReviewTool(private val workspace: Workspace) : Tool {
    override val name = "stage_review"
    override val description =
        "Check a faux product UI HTML for mechanical design faults: purple/blue gradients, glass on " +
            "every card, emoji icons, placeholder copy, tiny type, identical cards, dead controls, and a " +
            "missing focus state. It does not see the rendered frame; open the file and judge the crop yourself. " +
            "Use it before showing a feature card, bento, or product mockup."

    override val parameters = objectSchema(
        properties = mapOf(
            "path" to stringProp("The HTML file in the project."),
        ),
        required = listOf("path"),
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val path = args.stringArg("path")?.trim().orEmpty()
        if (path.isEmpty()) return@withContext ToolResult.error("path is required.")
        val file = runCatching { workspace.resolve(path) }.getOrElse {
            return@withContext ToolResult.error(it.message ?: "Bad path.")
        }
        if (!file.isFile) return@withContext ToolResult.error("No file at $path.")
        val html = runCatching { file.readText(Charsets.UTF_8) }.getOrElse {
            return@withContext ToolResult.error("Could not read $path.")
        }.take(400_000)
        val css = Regex("""<style[^>]*>(.*?)</style>""", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
            .findAll(html).joinToString("\n") { it.groupValues[1] }
        val body = html.replace(Regex("""<script[^>]*>.*?</script>""", RegexOption.DOT_MATCHES_ALL), " ")
            .replace(Regex("""<style[^>]*>.*?</style>""", RegexOption.DOT_MATCHES_ALL), " ")
        val findings = mutableListOf<String>()

        val palette = listOf("#6d28d9", "#7c3aed", "#8b5cf6", "#6366f1", "#4f46e5", "#2563eb", "#3b82f6")
        val purpleBlue = palette.any { html.contains(it, ignoreCase = true) } &&
            css.contains("linear-gradient", ignoreCase = true) &&
            (palette.take(5).any { html.contains(it, ignoreCase = true) } &&
                palette.drop(5).any { html.contains(it, ignoreCase = true) })
        if (purpleBlue) findings += "blocker: purple-to-blue gradient — replace it with one ink, one paper, one accent."

        val glass = Regex("""backdrop-filter\s*:""", RegexOption.IGNORE_CASE).findAll(css).count()
        if (glass > 2) findings += "blocker: glass on $glass surfaces — glass is a material, not the default card."

        if (Regex("""[\x{1F300}-\x{1FAFF}\x{2600}-\x{27BF}]""").containsMatchIn(body)) {
            findings += "blocker: emoji used as an icon — draw one family of glyphs, or drop the icon."
        }
        if (Regex("""lorem ipsum|placeholder text|your text here|welcome,? empowering""", RegexOption.IGNORE_CASE).containsMatchIn(body)) {
            findings += "blocker: placeholder or generic marketing copy — write the product's real words."
        }
        if (Regex("""font-size\s*:\s*(?:[0-9]|10)px""", RegexOption.IGNORE_CASE).containsMatchIn(css)) {
            findings += "blocker: type under 11px — set real UI type and scale the stage, never 7–10px."
        }
        val cards = Regex("""class=["'][^"']*\b(?:card|feature-card|tile)\b""", RegexOption.IGNORE_CASE).findAll(body).count()
        if (cards >= 3 && Regex("""\.(?:card|feature-card|tile)\s*\{""", RegexOption.IGNORE_CASE).findAll(css).count() <= 1) {
            findings += "warning: $cards identical cards — vary weight and content, or use one hero and fewer supporting tiles."
        }
        if (Regex("""href\s*=\s*["']#["']""", RegexOption.IGNORE_CASE).containsMatchIn(body)) {
            findings += "warning: a dead href=\"#\" control — remove it or make it do the one thing the mockup shows."
        }
        if (Regex("""<(?:button|input|select|textarea|a)\b""", RegexOption.IGNORE_CASE).containsMatchIn(body) &&
            !css.contains(":focus-visible", ignoreCase = true)
        ) {
            findings += "warning: controls lack :focus-visible — add a visible focus ring."
        }
        val stage = Regex("""class=["'][^"']*\bstage\b""", RegexOption.IGNORE_CASE).containsMatchIn(body)
        if (!stage) findings += "polish: no .stage element — the crop and the scale are part of the illustration."
        val overflow = Regex("""overflow\s*:\s*hidden""", RegexOption.IGNORE_CASE).containsMatchIn(css)
        if (stage && !overflow) findings += "warning: .stage has no overflow:hidden — the crop will leak."
        if (findings.isEmpty()) {
            return@withContext ToolResult.ok(
                "No static design fault found in $path.\n" +
                    "This is not a frame review: open the file, look at the 390px and wide layouts, then judge the crop, hierarchy, and motion.",
            )
        }
        ToolResult.ok(
            "stage_review: $path\n" + findings.joinToString("\n") + "\n\n" +
                "These are static checks, not a rendered frame. Fix the blockers, open the file, and inspect the two widths before showing it.",
        )
    }
}
