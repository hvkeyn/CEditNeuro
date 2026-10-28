package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.chat.LayoutCard
import kotlinx.serialization.json.JsonObject

/** Small deterministic ASCII studio: shark/ocean scenes and block-letter banners. Shark and ocean templates are adapted from ascii-sharks (MIT). */
class AsciiArtTool : Tool {
    override val name = "ascii_art"

    override val description =
        "Draw ASCII art in the chat. subject is shark, ocean, fish, scene, or text. " +
            "For text, pass the words; uppercase letters, digits, and spaces are supported. " +
            "frames 1 to 8 makes a short loop of shifted frames for an animation. " +
            "The result is a compact monospace card. For an ASCII video, use the ascii-studio skill and render_video."

    override val parameters = objectSchema(
        properties = mapOf(
            "subject" to stringProp("shark, ocean, fish, scene, or text. Default scene."),
            "text" to stringProp("Words for subject=text, at most 24 characters."),
            "frames" to intProp("1 to 8 frames. Default 1."),
        ),
        required = listOf("subject"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val subject = args.stringArg("subject")?.trim()?.lowercase().orEmpty().ifEmpty { "scene" }
        if (subject !in setOf("shark", "ocean", "fish", "scene", "text")) {
            return ToolResult.error("subject is shark, ocean, fish, scene, or text.")
        }
        val frames = (args.intArg("frames") ?: 1).coerceIn(1, 8)
        if (subject == "text") {
            val text = args.stringArg("text")?.trim()?.uppercase().orEmpty()
            if (text.isEmpty()) return ToolResult.error("text is required for subject=text.")
            if (text.length > 24) return ToolResult.error("Keep the banner under 24 characters.")
            val banner = banner(text) ?: return ToolResult.error("Use A-Z, 0-9, spaces, and - _ ! ? in the banner.")
            return card(banner)
        }
        val base = when (subject) {
            "shark" -> SHARK
            "ocean" -> OCEAN
            "fish" -> FISH
            else -> SCENE
        }
        val out = buildString {
            for (i in 0 until frames) {
                if (frames > 1) append("frame ").append(i + 1).append('/').append(frames).append('\n')
                append(shift(base, i * 3))
                if (i < frames - 1) append("\n\n")
            }
        }
        return card(out)
    }

    private fun card(ascii: String): ToolResult {
        val body = buildString {
            append("<div style=\"background:#080c09;color:#d8ffd0;border-radius:10px;padding:12px;overflow:auto\">")
            append("<pre style=\"margin:0;font:10px/1.15 ui-monospace,monospace;white-space:pre\">")
            append(esc(ascii))
            append("</pre></div>")
            append("<div style=\"font:12px -apple-system,system-ui,sans-serif;color:#5f6368;margin-top:6px\">ASCII · monospace · original characters</div>")
        }
        val page = LayoutCard.page(body) ?: return ToolResult.error("The ASCII card is empty or too large.")
        return ToolResult.ok(LayoutCard.fence(page))
    }

    private fun esc(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun shift(scene: String, by: Int): String {
        if (by == 0) return scene
        val pad = " ".repeat(by)
        return scene.lines().joinToString("\n") { line -> (pad + line).take(100) }
    }

    private fun banner(text: String): String? {
        val glyphs = text.map { ch -> FONT[ch] ?: return null }
        if (glyphs.isEmpty()) return null
        val rows = (0 until 7).map { row ->
            glyphs.joinToString(" ") { glyph ->
                glyph[row].map { bit -> if (bit == '1') '#' else ' ' }.joinToString("")
            }
        }
        return rows.joinToString("\n") { row -> row.trimEnd() }
    }

    private companion object {
        val SHARK = """
                            ;'
                             %%'
                              .t;.               .oooo
                               S;:S%              %8S8.
                                ;:tt%t '..        XX%%S
t'                            ''XSSS8SSX%XX8#.    %XSX%
 :;'               ;.''X8%8XX%%S%%S%SStt%tSX8S#X8't%ttt.
  ::%'       ''8XXXSSt%Stttttt%8@%%@%8@8%Sttt%%%%SSSSS%tt.
  'SXXtt%t%%%S%:;StS%ttSS%tSXSt88@@%8%@8%tStS%%X%SS%%S%;;t
 .@##88#@...'@@@@#@8@88X88#XXXSX8SX88SX8%SS%S%%S88St%t%%%.
  %;%t             :.    '.'##@###@X#888XX##8@@@#.X%XS%.
 .%t                            ;%%%#@@##@@@.     SXS%%
 t.                            .%;%%%.            %%XXS
                              '%t:t              .SXSS'
                             '::.                ooooo
                            .S'
                            .
        """.trimIndent()

        val OCEAN = """
     .-.
   .'   `-.
  /  ~     \      .-.
 |  ~   ~   |   .'   `-.
  \   ~   ~ /  /  ~     \
   `-.____.-'  |  ~   ~   |
       |       \   ~   ~ /
       |        `-.____.-'
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        """.trimIndent()

        val FISH = """
             .
            /|.
  .-~~~~~-. / | \  .-~~~~-.
 /  o   o  /  |  \/  o  o  \
|     ^    |  |  |    ^     |
 \  \___/  \  |  / \  \___/ /
  '-.....-'  \|/   '-.....-'
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        """.trimIndent()

        val SCENE = """
                     .-.
                   .'   `-.
                  /  ~     \        .-.
                 |  ~   ~   |     .'   `-.
                  \   ~   ~ /     /  ~     \
                   `-.____.-'    |  ~   ~   |
                       |          \   ~   ~ /
                       |           `-.____.-'
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        .-~~~~-.       .-~~~~-.
       /  o  o  \     /  o  o  \
      |    ^     |   |    ^     |
       \  \___/ /     \  \___/ /
        '-.....-'       '-.....-'
~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~
        """.trimIndent()

        val FONT: Map<Char, List<String>> = mapOf(
            'A' to listOf("01110", "10001", "10001", "11111", "10001", "10001", "10001"),
            'B' to listOf("11110", "10001", "10001", "11110", "10001", "10001", "11110"),
            'C' to listOf("01110", "10001", "10000", "10000", "10000", "10001", "01110"),
            'D' to listOf("11110", "10001", "10001", "10001", "10001", "10001", "11110"),
            'E' to listOf("11111", "10000", "10000", "11110", "10000", "10000", "11111"),
            'F' to listOf("11111", "10000", "10000", "11110", "10000", "10000", "10000"),
            'G' to listOf("01110", "10001", "10000", "10111", "10001", "10001", "01110"),
            'H' to listOf("10001", "10001", "10001", "11111", "10001", "10001", "10001"),
            'I' to listOf("11111", "00100", "00100", "00100", "00100", "00100", "11111"),
            'J' to listOf("00111", "00010", "00010", "00010", "00010", "10010", "01100"),
            'K' to listOf("10001", "10010", "10100", "11000", "10100", "10010", "10001"),
            'L' to listOf("10000", "10000", "10000", "10000", "10000", "10000", "11111"),
            'M' to listOf("10001", "11011", "10101", "10101", "10001", "10001", "10001"),
            'N' to listOf("10001", "11001", "10101", "10011", "10001", "10001", "10001"),
            'O' to listOf("01110", "10001", "10001", "10001", "10001", "10001", "01110"),
            'P' to listOf("11110", "10001", "10001", "11110", "10000", "10000", "10000"),
            'Q' to listOf("01110", "10001", "10001", "10001", "10101", "10010", "01101"),
            'R' to listOf("11110", "10001", "10001", "11110", "10100", "10010", "10001"),
            'S' to listOf("01111", "10000", "10000", "01110", "00001", "00001", "11110"),
            'T' to listOf("11111", "00100", "00100", "00100", "00100", "00100", "00100"),
            'U' to listOf("10001", "10001", "10001", "10001", "10001", "10001", "01110"),
            'V' to listOf("10001", "10001", "10001", "10001", "10001", "01010", "00100"),
            'W' to listOf("10001", "10001", "10001", "10101", "10101", "11011", "10001"),
            'X' to listOf("10001", "10001", "01010", "00100", "01010", "10001", "10001"),
            'Y' to listOf("10001", "10001", "01010", "00100", "00100", "00100", "00100"),
            'Z' to listOf("11111", "00001", "00010", "00100", "01000", "10000", "11111"),
            '0' to listOf("01110", "10001", "10011", "10101", "11001", "10001", "01110"),
            '1' to listOf("00100", "01100", "00100", "00100", "00100", "00100", "01110"),
            '2' to listOf("01110", "10001", "00001", "00010", "00100", "01000", "11111"),
            '3' to listOf("11110", "00001", "00001", "01110", "00001", "00001", "11110"),
            '4' to listOf("00010", "00110", "01010", "10010", "11111", "00010", "00010"),
            '5' to listOf("11111", "10000", "10000", "11110", "00001", "00001", "11110"),
            '6' to listOf("01110", "10000", "10000", "11110", "10001", "10001", "01110"),
            '7' to listOf("11111", "00001", "00010", "00100", "01000", "01000", "01000"),
            '8' to listOf("01110", "10001", "10001", "01110", "10001", "10001", "01110"),
            '9' to listOf("01110", "10001", "10001", "01111", "00001", "00001", "01110"),
            ' ' to listOf("00000", "00000", "00000", "00000", "00000", "00000", "00000"),
            '-' to listOf("00000", "00000", "00000", "11111", "00000", "00000", "00000"),
            '_' to listOf("00000", "00000", "00000", "00000", "00000", "00000", "11111"),
            '!' to listOf("00100", "00100", "00100", "00100", "00100", "00000", "00100"),
            '?' to listOf("01110", "10001", "00001", "00010", "00100", "00000", "00100"),
            '.' to listOf("00000", "00000", "00000", "00000", "00000", "00000", "00100"),
        )
    }
}
