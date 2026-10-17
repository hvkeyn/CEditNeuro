package com.hvkeyn.ceditneuro.chat

/**
 * A chart the chat can draw from a stream of numbers.
 * Rows are whitespace or commas. A header row and a label column are optional.
 * The idea follows Chartli (plain numbers in, a chart out) without that CLI.
 */
object ChartSheet {
    val types = setOf("line", "spark", "bars", "columns", "heatmap")

    /** Chartli's names, drawn by this chat. ascii, unicode, and braille become a line. */
    fun canonicalType(asked: String): String? = when (asked.lowercase()) {
        "line", "ascii", "unicode", "braille" -> "line"
        "spark" -> "spark"
        "bars", "bar", "barh" -> "bars"
        "columns", "column", "col" -> "columns"
        "heatmap", "heat" -> "heatmap"
        else -> null
    }
    private val keys = setOf("type", "title", "x", "y", "series")
    private const val MAX_POINTS = 240
    private const val MAX_SERIES = 8

    data class Sheet(
        val type: String,
        val title: String,
        val xLabel: String,
        val yLabel: String,
        val series: List<String>,
        val xLabels: List<String>,
        val columns: List<List<Double>>,
    ) {
        val points: Int get() = columns.firstOrNull()?.size ?: 0
    }

    fun parse(text: String): Sheet? {
        val lines = text.replace("\r\n", "\n").lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .toList()
        if (lines.isEmpty()) return null
        var type = "line"
        var title = ""
        var xLabel = ""
        var yLabel = ""
        val named = ArrayList<String>()
        val data = ArrayList<String>()
        for (line in lines) {
            val key = directive(line)
            if (key != null && data.isEmpty()) {
                when (key.first) {
                    "type" -> type = canonicalType(key.second) ?: return null
                    "title" -> title = key.second.take(80)
                    "x" -> xLabel = key.second.take(40)
                    "y" -> yLabel = key.second.take(40)
                    "series" -> named += key.second.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                }
            } else {
                data += line
            }
        }
        if (data.isEmpty()) return null
        val rows = data.map { tokenize(it) }
        if (rows.any { it.isEmpty() }) return null
        var headers = emptyList<String>()
        var body = rows
        if (rows.size >= 2 && rows[0].any { number(it) == null }) {
            headers = rows[0]
            body = rows.drop(1)
        }
        if (body.isEmpty()) return null
        val width = body.minOf { it.size }
        if (width == 0) return null
        val labeled = body.all { number(it[0]) == null }
        if (body.size == 1 && !labeled && body[0].size > 1) {
            val values = body[0].map { number(it) ?: return null }
            if (values.size > MAX_POINTS) return null
            val name = (named.firstOrNull() ?: yLabel).ifBlank { "value" }.take(24)
            return Sheet(type, title, xLabel, yLabel, listOf(name), emptyList(), listOf(values))
        }
        val xLabels = if (labeled) body.map { it[0].take(24) } else emptyList()
        val start = if (labeled) 1 else 0
        val count = width - start
        if (count !in 1..MAX_SERIES) return null
        val columns = List(count) { ArrayList<Double>() }
        for (row in body) {
            for (series in 0 until count) {
                val value = number(row[start + series]) ?: return null
                columns[series] += value
            }
        }
        if (columns[0].size !in 1..MAX_POINTS) return null
        val names = when {
            named.isNotEmpty() -> named
            headers.size >= start + count -> headers.drop(start)
            count == 1 -> listOf(yLabel.ifBlank { "value" })
            else -> List(count) { "s${it + 1}" }
        }.map { it.take(24) }
        return Sheet(type, title, xLabel, yLabel, names.take(count), xLabels, columns)
    }

    /**
     * A markdown table whose cells are numbers, or a label plus numbers.
     * Returns the chart text when there is enough to draw, and null when a cell is a sentence.
     */
    fun fromTable(header: List<String>, rows: List<List<String>>): String? {
        if (rows.size !in 2..MAX_POINTS) return null
        val width = rows.first().size
        if (width !in 1..MAX_SERIES + 1) return null
        if (rows.any { it.size != width }) return null
        val cells = if (header.size == width) header + rows.flatten() else rows.flatten()
        if (cells.any { cell -> cell.isBlank() || cell.any { it.isWhitespace() } }) return null
        val text = buildString {
            append("type: columns\n")
            if (header.size == width) append(header.joinToString(" ")).append('\n')
            rows.forEach { append(it.joinToString(" ")).append('\n') }
        }
        val sheet = parse(text) ?: return null
        if (sheet.points < 2) return null
        return text
    }

    /** One row of block glyphs, the compact chart a terminal would print. */
    fun spark(values: List<Double>): String {
        if (values.isEmpty()) return ""
        val low = values.min()
        val span = (values.max() - low).takeIf { it > 0.0 } ?: 1.0
        val blocks = "▁▂▃▄▅▆▇█"
        return values.joinToString("") { value ->
            val index = (((value - low) / span) * (blocks.length - 1)).toInt()
                .coerceIn(0, blocks.length - 1)
            blocks[index].toString()
        }
    }

    /** The fenced block the chat renders. */
    fun fence(sheet: Sheet, rawRows: String): String = buildString {
        append("```chart\n")
        append("type: ").append(sheet.type).append('\n')
        if (sheet.title.isNotEmpty()) append("title: ").append(sheet.title).append('\n')
        if (sheet.xLabel.isNotEmpty()) append("x: ").append(sheet.xLabel).append('\n')
        if (sheet.yLabel.isNotEmpty()) append("y: ").append(sheet.yLabel).append('\n')
        if (sheet.series.size > 1) append("series: ").append(sheet.series.joinToString(", ")).append('\n')
        append(rawRows.trim()).append("\n```")
    }

    private fun directive(line: String): Pair<String, String>? {
        val cut = line.indexOf(':')
        if (cut !in 1..12) return null
        val key = line.substring(0, cut).trim().lowercase()
        if (key !in keys) return null
        return key to line.substring(cut + 1).trim()
    }

    private fun tokenize(line: String): List<String> {
        val spaced = line.split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (spaced.size == 1 && spaced[0].contains(',')) {
            val parts = spaced[0].split(',').map { it.trim() }.filter { it.isNotEmpty() }
            val wholeNumbers = parts.size >= 2 && parts.all { it.matches(Regex("""\d+""")) }
            if (wholeNumbers || parts.size >= 3) return parts
        }
        return spaced
    }

    private fun number(token: String): Double? {
        val cleaned = token.trim().removeSuffix("%")
        cleaned.toDoubleOrNull()?.let { return it.takeIf { value -> value.isFinite() } }
        if (cleaned.count { it == ',' } == 1 && cleaned.none { it == '.' }) {
            return cleaned.replace(',', '.').toDoubleOrNull()?.takeIf { it.isFinite() }
        }
        return null
    }
}
