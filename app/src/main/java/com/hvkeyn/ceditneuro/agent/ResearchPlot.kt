package com.hvkeyn.ceditneuro.agent

import java.util.Locale

/** Draws a line or bar chart from numbers, so the model sends data instead of writing SVG. */
object ResearchPlot {
    private const val WIDTH = 520
    private const val HEIGHT = 320
    private const val LEFT = 56
    private const val RIGHT = 16
    private const val TOP = 36
    private const val BOTTOM = 48
    private val palette = listOf("#1565C0", "#E65100", "#2E7D32", "#6A1B9A")

    data class Series(val name: String, val values: List<Double>)

    /** "a: 1, 2, 3" per line. A line without a name is called "value". */
    fun parseSeries(text: String): List<Series> = text.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .map { line ->
            val name = if (line.contains(':')) line.substringBefore(':').trim() else "value"
            val numbers = line.substringAfter(':').split(Regex("[;\\s]+|,\\s+|,(?=[-\\d.])"))
                .map { it.trim() }
                .filter { it.isNotEmpty() }
                .map { it.toDoubleOrNull() ?: throw IllegalArgumentException("Not a number: '$it' in series $name.") }
            Series(name.ifBlank { "value" }, numbers)
        }

    fun svg(title: String, kind: String, labels: List<String>, series: List<Series>): String {
        require(series.isNotEmpty()) { "series is empty." }
        require(series.size <= palette.size) { "At most ${palette.size} series." }
        val points = series.maxOf { it.values.size }
        require(points in 1..60) { "Each series needs 1 to 60 values." }
        val bar = kind.trim().lowercase() == "bar"
        val all = series.flatMap { it.values }
        var low = all.min()
        var high = all.max()
        if (bar || low > 0) low = minOf(low, 0.0)
        if (high == low) high = low + 1
        val plotW = (WIDTH - LEFT - RIGHT).toDouble()
        val plotH = (HEIGHT - TOP - BOTTOM).toDouble()
        fun y(v: Double) = TOP + plotH - (v - low) / (high - low) * plotH
        val out = StringBuilder()
        out.append("""<svg xmlns="http://www.w3.org/2000/svg" width="$WIDTH" height="$HEIGHT" viewBox="0 0 $WIDTH $HEIGHT" font-family="sans-serif" font-size="11">""")
        out.append("""<rect width="$WIDTH" height="$HEIGHT" fill="#ffffff"/>""")
        out.append("""<text x="${WIDTH / 2}" y="20" text-anchor="middle" font-size="14" font-weight="bold">${esc(title)}</text>""")
        for (step in 0..4) {
            val v = low + (high - low) * step / 4
            val yy = y(v)
            out.append("""<line x1="$LEFT" y1="${f(yy)}" x2="${WIDTH - RIGHT}" y2="${f(yy)}" stroke="#e0e0e0"/>""")
            out.append("""<text x="${LEFT - 6}" y="${f(yy + 4)}" text-anchor="end" fill="#555">${esc(MathEval.format(round3(v)))}</text>""")
        }
        out.append("""<line x1="$LEFT" y1="${f(y(low))}" x2="${WIDTH - RIGHT}" y2="${f(y(low))}" stroke="#555"/>""")
        val slot = plotW / points
        val every = ((points + 11) / 12).coerceAtLeast(1)
        for (i in 0 until points) {
            if (i % every != 0) continue
            val label = labels.getOrNull(i) ?: (i + 1).toString()
            out.append("""<text x="${f(LEFT + slot * (i + 0.5))}" y="${HEIGHT - BOTTOM + 16}" text-anchor="middle" fill="#555">${esc(label.take(10))}</text>""")
        }
        series.forEachIndexed { s, one ->
            val color = palette[s]
            if (bar) {
                val width = slot * 0.8 / series.size
                one.values.forEachIndexed { i, v ->
                    val x = LEFT + slot * i + slot * 0.1 + width * s
                    val top = minOf(y(v), y(0.0))
                    val h = kotlin.math.abs(y(v) - y(0.0))
                    out.append("""<rect x="${f(x)}" y="${f(top)}" width="${f(width)}" height="${f(h)}" fill="$color"/>""")
                }
            } else {
                val path = one.values.mapIndexed { i, v -> "${f(LEFT + slot * (i + 0.5))},${f(y(v))}" }.joinToString(" ")
                out.append("""<polyline points="$path" fill="none" stroke="$color" stroke-width="2"/>""")
                one.values.forEachIndexed { i, v ->
                    out.append("""<circle cx="${f(LEFT + slot * (i + 0.5))}" cy="${f(y(v))}" r="3" fill="$color"/>""")
                }
            }
            val lx = LEFT + s * 120
            out.append("""<rect x="$lx" y="${HEIGHT - 18}" width="10" height="10" fill="$color"/>""")
            out.append("""<text x="${lx + 14}" y="${HEIGHT - 9}">${esc(one.name.take(16))}</text>""")
        }
        out.append("</svg>")
        return out.toString()
    }

    private fun round3(v: Double) = Math.round(v * 1000) / 1000.0
    private fun f(v: Double) = String.format(Locale.ROOT, "%.1f", v)
    private fun esc(text: String) = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
