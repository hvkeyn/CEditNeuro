package com.hvkeyn.ceditneuro.chat

/**
 * A small card the chat can draw. Plain lines become a list. HTML is kept only as markup:
 * scripts and handlers are removed. The picture stays in the chat.
 */
object LayoutCard {
    fun page(body: String): String? {
        val trimmed = body.trim()
        if (trimmed.isEmpty() || trimmed.length > 12_000) return null
        val html = if (trimmed.contains('<')) sanitize(trimmed) else listCard(trimmed)
        if (html.isNullOrBlank() || !html.contains('<')) return null
        return wrap(html)
    }

    fun fence(page: String): String = "```html\n$page\n```"

    private fun listCard(text: String): String {
        val items = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(40)
        val rows = items.joinToString("") { line ->
            val done = line.startsWith("[x]", true) || line.startsWith("- [x]", true)
            val label = line.removePrefix("-").removePrefix("[x]").removePrefix("[X]").removePrefix("[ ]").trim()
            val mark = if (done) "✓" else "•"
            "<li style=\"margin:10px 0;font-size:18px\">$mark ${escape(label)}</li>"
        }
        return "<ul style=\"list-style:none;margin:0;padding:16px 18px\">$rows</ul>"
    }

    private fun sanitize(html: String): String {
        var text = html
        text = Regex("""<script\b[^>]*>[\s\S]*?</script>""", RegexOption.IGNORE_CASE).replace(text, "")
        text = Regex("""<iframe\b[^>]*>[\s\S]*?</iframe>""", RegexOption.IGNORE_CASE).replace(text, "")
        text = Regex("""\s+on[a-z]+\s*=\s*("[^"]*"|'[^']*'|[^\s>]+)""", RegexOption.IGNORE_CASE).replace(text, "")
        text = Regex("""javascript:""", RegexOption.IGNORE_CASE).replace(text, "")
        return text
    }

    private fun wrap(html: String): String {
        if (html.contains("<html", ignoreCase = true)) return html
        return "<!doctype html><html><head><meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">" +
            "<style>html,body{margin:0;background:#fff;color:#1c1b1f;font-family:sans-serif}</style>" +
            "</head><body>$html</body></html>"
    }

    private fun escape(text: String): String = text
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
}
