package com.hvkeyn.ceditneuro.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em

/**
 * Renders the Markdown the agent already writes: headings, lists, inline code,
 * fenced blocks, quotes, tables, and links. A single newline stays a space, so a
 * reply wrapped for a narrow column reads as a paragraph.
 */
@Composable
fun MarkdownText(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
) {
    val blocks = remember(text) { parseMarkdown(text) }
    val linkColor = MaterialTheme.colorScheme.primary
    val codeColor = MaterialTheme.colorScheme.tertiary
    val codeBackground = MaterialTheme.colorScheme.background
    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (block in blocks) {
            when (block) {
                is MdBlock.Paragraph -> Text(
                    text = block.inlines.toAnnotated(color, linkColor, codeColor, codeBackground),
                    style = MaterialTheme.typography.bodyMedium,
                    color = color,
                )
                is MdBlock.Heading -> Text(
                    text = block.inlines.toAnnotated(color, linkColor, codeColor, codeBackground),
                    style = when (block.level) {
                        1 -> MaterialTheme.typography.titleMedium
                        2 -> MaterialTheme.typography.titleSmall
                        else -> MaterialTheme.typography.labelLarge
                    },
                    color = color,
                )
                is MdBlock.Bullets -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    block.items.forEach { item ->
                        ListRow("•", item, color, linkColor, codeColor, codeBackground)
                    }
                }
                is MdBlock.Numbered -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    block.items.forEachIndexed { index, item ->
                        ListRow("${index + 1}.", item, color, linkColor, codeColor, codeBackground)
                    }
                }
                is MdBlock.Code -> CodeBlock(block.lang, block.text, color)
                is MdBlock.Quote -> Row(modifier = Modifier.height(IntrinsicSize.Min).fillMaxWidth()) {
                    Box(
                        modifier = Modifier
                            .padding(vertical = 2.dp)
                            .width(3.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
                    )
                    Text(
                        text = block.inlines.toAnnotated(color, linkColor, codeColor, codeBackground),
                        style = MaterialTheme.typography.bodyMedium,
                        color = color,
                        modifier = Modifier.padding(start = 8.dp).weight(1f),
                    )
                }
                MdBlock.Rule -> HorizontalDivider(color = MaterialTheme.colorScheme.outline)
                is MdBlock.Table -> TableBlock(block, color, linkColor, codeColor, codeBackground)
            }
        }
    }
}

@Composable
private fun ListRow(
    marker: String,
    item: List<MdInline>,
    color: Color,
    linkColor: Color,
    codeColor: Color,
    codeBackground: Color,
) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) {
        Text(
            text = marker,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.width(if (marker == "•") 16.dp else 24.dp),
        )
        Text(
            text = item.toAnnotated(color, linkColor, codeColor, codeBackground),
            style = MaterialTheme.typography.bodyMedium,
            color = color,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun CodeBlock(lang: String, text: String, color: Color) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.background, RoundedCornerShape(8.dp))
            .padding(horizontal = 8.dp, vertical = 6.dp),
    ) {
        if (lang.isNotBlank()) {
            Text(
                text = lang,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.outline,
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        Text(
            text = text.ifEmpty { " " },
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            color = color,
            softWrap = false,
            modifier = Modifier.horizontalScroll(rememberScrollState()),
        )
    }
}

@Composable
private fun TableBlock(
    table: MdBlock.Table,
    color: Color,
    linkColor: Color,
    codeColor: Color,
    codeBackground: Color,
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
    ) {
        TableRow(table.header, color, linkColor, codeColor, codeBackground, header = true)
        HorizontalDivider(color = MaterialTheme.colorScheme.outline)
        table.rows.forEach { row ->
            TableRow(row, color, linkColor, codeColor, codeBackground, header = false)
        }
    }
}

@Composable
private fun TableRow(
    cells: List<List<MdInline>>,
    color: Color,
    linkColor: Color,
    codeColor: Color,
    codeBackground: Color,
    header: Boolean,
) {
    Row(modifier = Modifier.padding(vertical = 3.dp)) {
        cells.forEach { cell ->
            Text(
                text = cell.toAnnotated(color, linkColor, codeColor, codeBackground),
                style = MaterialTheme.typography.bodySmall.copy(
                    fontWeight = if (header) FontWeight.SemiBold else FontWeight.Normal,
                ),
                color = color,
                modifier = Modifier.width(140.dp).padding(end = 8.dp),
            )
        }
    }
}

private fun List<MdInline>.toAnnotated(
    color: Color,
    linkColor: Color,
    codeColor: Color,
    codeBackground: Color,
): AnnotatedString = buildAnnotatedString {
    fun write(nodes: List<MdInline>, bold: Boolean, italic: Boolean) {
        for (node in nodes) {
            when (node) {
                is MdInline.Text -> withStyle(
                    SpanStyle(
                        color = color,
                        fontWeight = if (bold) FontWeight.SemiBold else null,
                        fontStyle = if (italic) FontStyle.Italic else null,
                    ),
                ) { append(node.value) }
                is MdInline.Code -> withStyle(
                    SpanStyle(
                        color = codeColor,
                        background = codeBackground,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 0.92.em,
                    ),
                ) { append(node.value) }
                is MdInline.Bold -> write(node.children, bold = true, italic = italic)
                is MdInline.Italic -> write(node.children, bold = bold, italic = true)
                is MdInline.Link -> withLink(
                    LinkAnnotation.Url(
                        node.url,
                        TextLinkStyles(
                            style = SpanStyle(
                                color = linkColor,
                                textDecoration = TextDecoration.Underline,
                                fontWeight = if (bold) FontWeight.SemiBold else null,
                                fontStyle = if (italic) FontStyle.Italic else null,
                            ),
                        ),
                    ),
                ) { write(node.children, bold, italic) }
            }
        }
    }
    write(this@toAnnotated, bold = false, italic = false)
}

private sealed class MdBlock {
    data class Paragraph(val inlines: List<MdInline>) : MdBlock()
    data class Heading(val level: Int, val inlines: List<MdInline>) : MdBlock()
    data class Bullets(val items: List<List<MdInline>>) : MdBlock()
    data class Numbered(val items: List<List<MdInline>>) : MdBlock()
    data class Code(val lang: String, val text: String) : MdBlock()
    data class Quote(val inlines: List<MdInline>) : MdBlock()
    data object Rule : MdBlock()
    data class Table(val header: List<List<MdInline>>, val rows: List<List<List<MdInline>>>) : MdBlock()
}

private sealed class MdInline {
    data class Text(val value: String) : MdInline()
    data class Code(val value: String) : MdInline()
    data class Bold(val children: List<MdInline>) : MdInline()
    data class Italic(val children: List<MdInline>) : MdInline()
    data class Link(val children: List<MdInline>, val url: String) : MdInline()
}

private fun parseMarkdown(text: String): List<MdBlock> {
    val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
    val blocks = ArrayList<MdBlock>()
    var i = 0
    while (i < lines.size) {
        val trimmed = lines[i].trim()
        if (trimmed.isEmpty()) {
            i++
            continue
        }
        if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) {
            val fence = if (trimmed.startsWith("```")) "```" else "~~~"
            val lang = trimmed.removePrefix(fence).trim()
            val body = StringBuilder()
            i++
            while (i < lines.size && !lines[i].trimStart().startsWith(fence)) {
                if (body.isNotEmpty()) body.append('\n')
                body.append(lines[i])
                i++
            }
            if (i < lines.size) i++
            blocks += MdBlock.Code(lang, body.toString().trimEnd())
            continue
        }
        val heading = headingLevel(trimmed)
        if (heading != null) {
            blocks += MdBlock.Heading(heading, inlines(trimmed.drop(heading).trim()))
            i++
            continue
        }
        if (trimmed == "---" || trimmed == "***" || trimmed == "___") {
            blocks += MdBlock.Rule
            i++
            continue
        }
        if (trimmed.startsWith(">")) {
            val quote = StringBuilder()
            while (i < lines.size && lines[i].trimStart().startsWith(">")) {
                val piece = lines[i].trimStart().removePrefix(">").trim()
                if (quote.isNotEmpty() && piece.isNotEmpty()) quote.append(' ')
                quote.append(piece)
                i++
            }
            blocks += MdBlock.Quote(inlines(quote.toString()))
            continue
        }
        if (trimmed.contains('|') && i + 1 < lines.size && isTableSep(lines[i + 1])) {
            val header = splitRow(trimmed).map { inlines(it) }
            i += 2
            val rows = ArrayList<List<List<MdInline>>>()
            while (i < lines.size && lines[i].contains('|') && lines[i].isNotBlank()) {
                rows += splitRow(lines[i]).map { inlines(it) }
                i++
            }
            blocks += MdBlock.Table(header, rows)
            continue
        }
        val marker = listMarker(trimmed)
        if (marker != null) {
            val ordered = marker.second
            val items = ArrayList<String>()
            while (i < lines.size) {
                val current = lines[i].trim()
                val next = listMarker(current)
                if (next != null && next.second == ordered) {
                    items += next.first
                    i++
                    continue
                }
                if (items.isNotEmpty() && current.isNotEmpty() &&
                    (lines[i].startsWith("  ") || lines[i].startsWith("\t")) &&
                    next == null
                ) {
                    items[items.lastIndex] = items.last() + " " + current
                    i++
                    continue
                }
                break
            }
            val parsed = items.map { inlines(it) }
            blocks += if (ordered) MdBlock.Numbered(parsed) else MdBlock.Bullets(parsed)
            continue
        }
        val paragraph = StringBuilder()
        while (i < lines.size && !isBlockStart(lines, i)) {
            val raw = lines[i]
            val piece = raw.trim()
            if (piece.isEmpty()) break
            val hardBreak = raw.endsWith("  ") || raw.trimEnd().endsWith("\\")
            val content = if (hardBreak) piece.removeSuffix("\\").trimEnd() else piece
            if (paragraph.isNotEmpty() && !paragraph.endsWith('\n')) paragraph.append(' ')
            paragraph.append(content)
            if (hardBreak) paragraph.append('\n')
            i++
        }
        if (paragraph.isNotEmpty()) blocks += MdBlock.Paragraph(inlines(paragraph.toString()))
        else i++
    }
    return blocks.ifEmpty { listOf(MdBlock.Paragraph(inlines(text))) }
}

private fun isBlockStart(lines: List<String>, index: Int): Boolean {
    val trimmed = lines[index].trim()
    if (trimmed.isEmpty()) return true
    if (trimmed.startsWith("```") || trimmed.startsWith("~~~")) return true
    if (headingLevel(trimmed) != null) return true
    if (trimmed == "---" || trimmed == "***" || trimmed == "___") return true
    if (trimmed.startsWith(">")) return true
    if (listMarker(trimmed) != null) return true
    return trimmed.contains('|') && index + 1 < lines.size && isTableSep(lines[index + 1])
}

private fun headingLevel(trimmed: String): Int? {
    val level = trimmed.takeWhile { it == '#' }.length
    if (level !in 1..6) return null
    if (trimmed.length == level || trimmed[level] == ' ') return level
    return null
}

private fun listMarker(trimmed: String): Pair<String, Boolean>? {
    if (trimmed.startsWith("- ") || trimmed.startsWith("* ") || trimmed.startsWith("+ ")) {
        return trimmed.drop(2) to false
    }
    val dot = trimmed.indexOf(". ")
    if (dot in 1..3 && trimmed.substring(0, dot).all { it.isDigit() }) {
        return trimmed.substring(dot + 2) to true
    }
    return null
}

private fun isTableSep(line: String): Boolean {
    val trimmed = line.trim()
    if (!trimmed.contains('-') || !trimmed.contains('|')) return false
    return trimmed.all { it == '|' || it == '-' || it == ':' || it == ' ' }
}

private fun splitRow(line: String): List<String> =
    line.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }

private fun inlines(src: String): List<MdInline> = inlines(src, 0, src.length)

private fun inlines(src: String, start: Int, end: Int): List<MdInline> {
    val out = ArrayList<MdInline>()
    val plain = StringBuilder()
    fun flush() {
        if (plain.isNotEmpty()) {
            out += MdInline.Text(plain.toString())
            plain.clear()
        }
    }
    var i = start
    while (i < end) {
        val code = takeWrap(src, i, end, "`")
        if (code != null && code.content.isNotEmpty()) {
            flush()
            out += MdInline.Code(code.content)
            i = code.next
            continue
        }
        val both = takeWrap(src, i, end, "***") ?: takeWrap(src, i, end, "___")
        if (both != null && both.content.isNotEmpty()) {
            flush()
            out += MdInline.Bold(listOf(MdInline.Italic(inlines(src, i + both.marker.length, both.next - both.marker.length))))
            i = both.next
            continue
        }
        val bold = takeWrap(src, i, end, "**") ?: takeWrap(src, i, end, "__")
        if (bold != null && bold.content.isNotEmpty()) {
            flush()
            out += MdInline.Bold(inlines(src, i + bold.marker.length, bold.next - bold.marker.length))
            i = bold.next
            continue
        }
        if (src[i] == '*' || (src[i] == '_' && (i == start || !src[i - 1].isLetterOrDigit()))) {
            val marker = src[i]
            var close = -1
            var j = i + 1
            while (j < end) {
                val wordAfter = marker == '_' && j + 1 < end && src[j + 1].isLetterOrDigit()
                if (src[j] == marker && !wordAfter && j > i + 1) {
                    close = j
                    break
                }
                j++
            }
            if (close > i) {
                flush()
                out += MdInline.Italic(inlines(src, i + 1, close))
                i = close + 1
                continue
            }
        }
        if (src[i] == '[') {
            val labelEnd = src.indexOf(']', i + 1).takeIf { it in (i + 1) until end } ?: -1
            if (labelEnd > 0 && labelEnd + 1 < end && src[labelEnd + 1] == '(') {
                val urlEnd = src.indexOf(')', labelEnd + 2).takeIf { it in (labelEnd + 2)..end } ?: -1
                if (urlEnd > 0) {
                    flush()
                    val label = inlines(src, i + 1, labelEnd).ifEmpty {
                        listOf(MdInline.Text(src.substring(labelEnd + 2, urlEnd)))
                    }
                    out += MdInline.Link(label, src.substring(labelEnd + 2, urlEnd))
                    i = urlEnd + 1
                    continue
                }
            }
        }
        plain.append(src[i])
        i++
    }
    flush()
    return out
}

private class Wrap(val marker: String, val content: String, val next: Int)

private fun takeWrap(src: String, index: Int, end: Int, marker: String): Wrap? {
    if (index + marker.length > end || !src.startsWith(marker, index)) return null
    val from = index + marker.length
    val close = src.indexOf(marker, from).takeIf { it >= 0 && it + marker.length <= end } ?: return null
    return Wrap(marker, src.substring(from, close), close + marker.length)
}
