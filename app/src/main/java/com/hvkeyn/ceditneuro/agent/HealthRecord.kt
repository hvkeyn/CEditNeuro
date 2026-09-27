package com.hvkeyn.ceditneuro.agent

import java.io.File

/**
 * Numbers copied from a lab sheet or a report the user already has.
 * A row is outside the range only when that range was saved with it.
 */
object HealthRecord {
    private const val MAX_ROWS = 2_000
    private val header = "date\tperson\tname\tvalue\tunit\tlow\thigh\tsource"

    data class Row(
        val date: String,
        val person: String,
        val name: String,
        val value: String,
        val unit: String,
        val low: String,
        val high: String,
        val source: String,
    )

    fun file(root: File): File = File(root, ".ceditneuro/health/readings.tsv")

    fun add(
        root: File,
        person: String,
        name: String,
        value: String,
        date: String = "",
        unit: String = "",
        low: String = "",
        high: String = "",
        source: String = "",
    ): SkillNote {
        val row = Row(
            date = clip(date, 32),
            person = clip(person, 40),
            name = clip(name, 80),
            value = clip(value, 40),
            unit = clip(unit, 24),
            low = clip(low, 24),
            high = clip(high, 24),
            source = clip(source, 180),
        )
        if (row.person.isEmpty()) return SkillNote("person is required.", error = true)
        if (row.name.isEmpty()) return SkillNote("name is required.", error = true)
        if (row.value.isEmpty()) return SkillNote("value is required.", error = true)
        val line = listOf(row.date, row.person, row.name, row.value, row.unit, row.low, row.high, row.source)
            .joinToString("\t")
        SecretText.reject(line)?.let { return SkillNote(it, error = true) }
        val existing = readRows(root)
        if (existing.size >= MAX_ROWS) return SkillNote("The health record already has $MAX_ROWS rows.", error = true)
        val stored = file(root)
        stored.parentFile?.mkdirs()
        if (!stored.isFile) stored.writeText(header + "\n", Charsets.UTF_8)
        stored.appendText(line + "\n", Charsets.UTF_8)
        return SkillNote("Saved ${row.person} / ${row.name} = ${row.value} ${row.unit}".trim() + ".")
    }

    fun panel(root: File, person: String = ""): SkillNote {
        val wanted = person.trim()
        val rows = readRows(root).filter { wanted.isEmpty() || it.person.equals(wanted, ignoreCase = true) }
        if (rows.isEmpty()) {
            val why = if (wanted.isEmpty()) "No readings saved." else "No readings saved for $wanted."
            return SkillNote("$why Log the numbers from the file first.")
        }
        val lines = rows.map { row ->
            val mark = mark(row)
            val range = when {
                row.low.isEmpty() && row.high.isEmpty() -> "no range on the report"
                else -> "range ${row.low.ifEmpty { "—" }}–${row.high.ifEmpty { "—" }}"
            }
            val unit = if (row.unit.isEmpty()) "" else " ${row.unit}"
            val from = if (row.source.isEmpty()) "" else " source ${row.source}"
            "- ${row.date.ifEmpty { "no date" }} ${row.person} ${row.name} ${row.value}$unit $range $mark$from"
        }
        val counts = rows.groupingBy { mark(it) }.eachCount()
        val summary = "Below: ${counts["below"] ?: 0}. Above: ${counts["above"] ?: 0}. " +
            "Inside: ${counts["inside"] ?: 0}. No range: ${counts["no range"] ?: 0}. " +
            "Not a number: ${counts["not a number"] ?: 0}."
        return SkillNote(
            lines.joinToString("\n") +
                "\n$summary\nThese marks use only the range saved with each row. They are not a diagnosis.",
        )
    }

    fun mark(row: Row): String {
        val value = number(row.value) ?: return "not a number"
        val low = number(row.low)
        val high = number(row.high)
        if (low == null && high == null) return "no range"
        if (low != null && value < low) return "below"
        if (high != null && value > high) return "above"
        return "inside"
    }

    fun number(raw: String): Double? {
        val text = raw.trim().replace(',', '.').replace(" ", "")
        if (text.isEmpty()) return null
        return text.toDoubleOrNull()
    }

    private fun readRows(root: File): List<Row> {
        val stored = file(root)
        if (!stored.isFile) return emptyList()
        return stored.readLines(Charsets.UTF_8).mapNotNull { line ->
            if (line.isBlank() || line == header) return@mapNotNull null
            val parts = line.split('\t')
            if (parts.size < 4) return@mapNotNull null
            Row(
                date = parts.getOrElse(0) { "" },
                person = parts.getOrElse(1) { "" },
                name = parts.getOrElse(2) { "" },
                value = parts.getOrElse(3) { "" },
                unit = parts.getOrElse(4) { "" },
                low = parts.getOrElse(5) { "" },
                high = parts.getOrElse(6) { "" },
                source = parts.getOrElse(7) { "" },
            )
        }
    }

    private fun clip(raw: String, limit: Int): String =
        raw.trim().replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').take(limit)
}
