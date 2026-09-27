package com.hvkeyn.ceditneuro.agent

import java.io.File

/**
 * Numbers copied from a lab sheet or a report the user already has.
 * A row is outside the range only when that range was saved with it.
 */
object HealthRecord {
    private const val MAX_ROWS = 2_000
    private val header = "date\tperson\tname\tvalue\tunit\tlow\thigh\tsource\ttopic"

    data class Row(
        val date: String,
        val person: String,
        val name: String,
        val value: String,
        val unit: String,
        val low: String,
        val high: String,
        val source: String,
        val topic: String = "",
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
        topic: String = "",
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
            topic = clip(topic, 40),
        )
        if (row.person.isEmpty()) return SkillNote("person is required.", error = true)
        if (row.name.isEmpty()) return SkillNote("name is required.", error = true)
        if (row.value.isEmpty()) return SkillNote("value is required.", error = true)
        val line = listOf(row.date, row.person, row.name, row.value, row.unit, row.low, row.high, row.source, row.topic)
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
            val topic = if (row.topic.isEmpty()) "" else " topic ${row.topic}"
            "- ${row.date.ifEmpty { "no date" }} ${row.person} ${row.name} ${row.value}$unit $range $mark$from$topic"
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

    /** Min, max, mean, and direction for one named test. Numbers come only from saved rows. */
    fun trend(root: File, person: String, name: String, outsideOnly: Boolean = false): SkillNote {
        val wanted = name.trim()
        if (wanted.isEmpty()) return SkillNote("name is required.", error = true)
        val people = readRows(root).filter { person.isBlank() || it.person.equals(person.trim(), ignoreCase = true) }
        val exact = people.filter { it.name.equals(wanted, ignoreCase = true) }
        if (exact.isEmpty()) {
            val close = people.map { it.name }.distinct().filter { it.contains(wanted, ignoreCase = true) }
            val hint = if (close.isEmpty()) "No saved test named $wanted." else "No exact test named $wanted. Saved names: ${close.joinToString(", ")}."
            return SkillNote(hint)
        }
        val owners = exact.map { it.person }.distinct()
        if (person.isBlank() && owners.size > 1) {
            return SkillNote("That test is saved for ${owners.joinToString(", ")}. Pass person.")
        }
        val ordered = exact.sortedBy { it.date }
        val shown = if (outsideOnly) ordered.filter { mark(it) == "below" || mark(it) == "above" } else ordered
        if (shown.isEmpty()) return SkillNote("No saved readings of $wanted are outside the printed range.")
        val numbers = shown.mapNotNull { number(it.value) }
        val points = shown.joinToString("\n") { row ->
            val unit = if (row.unit.isEmpty()) "" else " ${row.unit}"
            "- ${row.date.ifEmpty { "no date" }} ${row.value}$unit ${mark(row)}"
        }
        val stats = when {
            numbers.isEmpty() -> "No numeric values."
            numbers.size == 1 -> "One number: ${format(numbers.first())}. No direction."
            else -> {
                val direction = when {
                    numbers.last() > numbers.first() -> "The last number is higher than the first."
                    numbers.last() < numbers.first() -> "The last number is lower than the first."
                    else -> "The last number matches the first."
                }
                "min ${format(numbers.min())}. max ${format(numbers.max())}. mean ${format(numbers.average())}. $direction"
            }
        }
        val who = shown.first().person
        val count = if (shown.size == 1) "1 reading" else "${shown.size} readings"
        return SkillNote(
            "$wanted for $who: $count.\n$points\n$stats\n" +
                "These marks use only the range saved with each row. They are not a diagnosis.",
        )
    }

    /** One line per saved test: how many readings, and the latest one. */
    fun index(root: File, person: String = "", topic: String = ""): SkillNote {
        val rows = readRows(root).filter { row ->
            (person.isBlank() || row.person.equals(person.trim(), ignoreCase = true)) &&
                (topic.isBlank() || row.topic.equals(topic.trim(), ignoreCase = true))
        }
        if (rows.isEmpty()) return SkillNote("No readings saved.")
        val lines = rows.groupBy { it.person }.toSortedMap(String.CASE_INSENSITIVE_ORDER).flatMap { (who, personRows) ->
            val tests = personRows.groupBy { it.name }.toSortedMap(String.CASE_INSENSITIVE_ORDER).map { (test, items) ->
                val last = items.maxBy { it.date }
                val topicNote = if (last.topic.isEmpty()) "" else ", topic ${last.topic}"
                "- $test: ${items.size}, last ${last.date.ifEmpty { "no date" }} ${last.value} ${mark(last)}$topicNote"
            }
            listOf(who) + tests
        }
        return SkillNote(lines.joinToString("\n"))
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
            if (line.isBlank() || line.startsWith("date\tperson\tname\t")) return@mapNotNull null
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
                topic = parts.getOrElse(8) { "" },
            )
        }
    }

    private fun clip(raw: String, limit: Int): String =
        raw.trim().replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').take(limit)

    private fun format(value: Double): String =
        String.format(java.util.Locale.US, "%.4f", value).trimEnd('0').trimEnd('.')
}
