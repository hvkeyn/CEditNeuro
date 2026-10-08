package com.hvkeyn.ceditneuro.tools

import java.io.File

/**
 * Shared queue and memory for every agent on one link.
 * Adapted from DeLM (arXiv:2606.10662): no agent assigns the work.
 * The earlier claim stamp keeps a task. Notes are appended, not relayed.
 */
class CrewBoard {
    data class Task(
        val id: String,
        val state: String,
        val owner: String,
        val stamp: String,
        val text: String,
    )

    private val tasks = ArrayList<Task>()
    private val gists = ArrayList<Triple<String, String, String>>()

    fun add(text: String): String {
        val clean = oneLine(text)
        if (clean.isEmpty()) return "A task needs text."
        val existing = tasks.firstOrNull { it.text == clean && it.state != "done" }
        if (existing != null) return "Already queued ${existing.id}: ${existing.state}."
        val id = nextId()
        tasks += Task(id, "open", "-", "-", clean)
        return "Added $id. It is open."
    }

    fun claim(id: String, owner: String, now: Long): String =
        apply("CLAIM $id $owner ${stamp(now, owner)}")

    fun done(id: String, owner: String): String = apply("DONE $id $owner")

    fun release(id: String, owner: String): String = apply("OPEN $id $owner")

    fun gist(kind: String, owner: String, text: String): String =
        apply("GIST $kind $owner ${oneLine(text)}")

    fun apply(line: String): String {
        val trimmed = line.trim()
        if (trimmed.startsWith("SNAP ")) {
            val encoded = trimmed.removePrefix("SNAP ").trim()
            val text = runCatching {
                String(java.util.Base64.getDecoder().decode(encoded), Charsets.UTF_8)
            }.getOrNull() ?: return "The queue snapshot could not be read."
            merge(parse(text))
            return "Merged the queue."
        }
        val parts = trimmed.split(' ', limit = 4)
        if (parts.isEmpty() || parts[0].isEmpty()) return "Empty crew line."
        return when (parts[0]) {
            "CLAIM" -> claimLine(parts)
            "DONE" -> finishLine(parts, "done")
            "OPEN" -> finishLine(parts, "open")
            "GIST" -> gistLine(trimmed)
            "ADD" -> addLine(trimmed)
            else -> "Unknown crew line."
        }
    }

    fun heldBy(owner: String): List<String> =
        tasks.filter { it.state == "claimed" && it.owner == owner }.map { it.id }

    fun hasOpen(): Boolean = tasks.any { it.state == "open" }

    fun render(): String = buildString {
        tasks.forEach { task ->
            append("T ").append(task.id).append(' ').append(task.state).append(' ')
            append(task.owner).append(' ').append(task.stamp).append(' ').append(task.text).append('\n')
        }
        gists.forEach { (kind, owner, text) ->
            append("G ").append(kind).append(' ').append(owner).append(' ').append(text).append('\n')
        }
    }.trimEnd()

    fun show(): String {
        if (tasks.isEmpty() && gists.isEmpty()) return "The queue is empty. Add an independent piece of the work."
        return buildString {
            append("Queue. Claim an open task. Do not take one that is claimed.\n")
            tasks.forEach { task ->
                append(task.id).append(' ').append(task.state)
                if (task.owner != "-") append(" by ").append(task.owner)
                append(": ").append(task.text).append('\n')
            }
            if (gists.isNotEmpty()) {
                append("Notes:\n")
                gists.takeLast(12).forEach { (kind, owner, text) ->
                    append(kind).append(' ').append(owner).append(": ").append(text).append('\n')
                }
            }
        }.trimEnd()
    }

    fun merge(other: CrewBoard) {
        other.tasks.forEach { incoming ->
            val current = tasks.indexOfFirst { it.id == incoming.id }
            if (current < 0) {
                tasks += incoming
                return@forEach
            }
            tasks[current] = prefer(tasks[current], incoming)
        }
        other.gists.forEach { gist ->
            if (gist !in gists) gists += gist
        }
        while (gists.size > GIST_CAP) gists.removeAt(0)
    }

    private fun addLine(line: String): String {
        val body = line.removePrefix("ADD ").trim()
        val id = body.substringBefore(' ').trim()
        val text = oneLine(body.substringAfter(' ', ""))
        if (!ID.matches(id) || text.isEmpty()) return "ADD needs an id and text."
        if (tasks.any { it.id == id }) return "Already queued $id."
        tasks += Task(id, "open", "-", "-", text)
        return "Added $id. It is open."
    }

    private fun claimLine(parts: List<String>): String {
        if (parts.size < 4) return "CLAIM needs an id, an owner, and a stamp."
        val id = parts[1]
        val owner = parts[2]
        val stamp = parts[3].substringBefore(' ')
        val task = tasks.find { it.id == id } ?: return "No task $id."
        if (task.state == "done") return "$id is already done."
        if (task.state == "claimed" && task.owner == owner) return "You already hold $id."
        if (task.state == "claimed" && task.stamp <= stamp) {
            return "$id is held by ${task.owner}. Claim a different open task."
        }
        replace(task.copy(state = "claimed", owner = owner, stamp = stamp))
        return "Claimed $id."
    }

    private fun finishLine(parts: List<String>, state: String): String {
        if (parts.size < 3) return "That crew line needs an id and an owner."
        val task = tasks.find { it.id == parts[1] } ?: return "No task ${parts[1]}."
        if (task.owner != parts[2]) return "${parts[1]} is held by ${task.owner}."
        if (state == "open") {
            replace(task.copy(state = "open", owner = "-", stamp = "-"))
            return "Released ${task.id}."
        }
        replace(task.copy(state = "done"))
        return "Finished ${task.id}."
    }

    private fun gistLine(line: String): String {
        val body = line.removePrefix("GIST ").trim()
        val kind = body.substringBefore(' ').uppercase()
        if (kind !in KINDS) return "A note is FACT, FAIL, or DONE."
        val rest = body.substringAfter(' ', "")
        val owner = rest.substringBefore(' ')
        val text = oneLine(rest.substringAfter(' ', ""))
        if (owner.isBlank() || text.isEmpty()) return "A note needs an owner and text."
        val entry = Triple(kind, owner, text.take(180))
        if (entry in gists) return "That note is already shared."
        gists += entry
        while (gists.size > GIST_CAP) gists.removeAt(0)
        return "Shared $kind."
    }

    private fun prefer(left: Task, right: Task): Task {
        if (left.state == "done" && right.state != "done") return left
        if (right.state == "done" && left.state != "done") return right
        if (left.state == "open") return right
        if (right.state == "open") return left
        return if (left.stamp <= right.stamp) left else right
    }

    private fun replace(task: Task) {
        val index = tasks.indexOfFirst { it.id == task.id }
        if (index >= 0) tasks[index] = task
    }

    private fun nextId(): String {
        val used = tasks.mapNotNull { it.id.removePrefix("t").toIntOrNull() }.maxOrNull() ?: 0
        return "t${used + 1}"
    }

    companion object {
        const val GIST_CAP = 30
        private val KINDS = setOf("FACT", "FAIL", "DONE")
        private val ID = Regex("^t[0-9]{1,6}$")
        private val gate = Any()

        fun stamp(now: Long, owner: String): String = "%013d-%s".format(now.coerceAtLeast(0), owner)

        fun parse(text: String): CrewBoard {
            val board = CrewBoard()
            text.lineSequence().forEach { line ->
                when {
                    line.startsWith("T ") -> board.readTask(line.removePrefix("T "))
                    line.startsWith("G ") -> board.readGist(line.removePrefix("G "))
                }
            }
            return board
        }

        fun file(root: File): File = File(root, ".ceditneuro/crew/board.txt")

        fun load(file: File): CrewBoard {
            val board = CrewBoard()
            if (!file.isFile) return board
            file.readLines(Charsets.UTF_8).forEach { line ->
                when {
                    line.startsWith("T ") -> board.readTask(line.removePrefix("T "))
                    line.startsWith("G ") -> board.readGist(line.removePrefix("G "))
                }
            }
            return board
        }

        fun update(file: File, block: (CrewBoard) -> String): Pair<CrewBoard, String> = synchronized(gate) {
            val board = load(file)
            val note = block(board)
            board.save(file)
            board to note
        }

        private fun oneLine(text: String): String =
            text.replace('\n', ' ').replace('\t', ' ').trim().take(180)
    }

    private fun readTask(body: String) {
        val parts = body.split(' ', limit = 5)
        if (parts.size < 5) return
        if (tasks.any { it.id == parts[0] }) return
        tasks += Task(parts[0], parts[1], parts[2], parts[3], parts[4])
    }

    private fun readGist(body: String) {
        val parts = body.split(' ', limit = 3)
        if (parts.size < 3) return
        val entry = Triple(parts[0], parts[1], parts[2])
        if (entry !in gists) gists += entry
    }

    fun taskText(id: String): String? = tasks.find { it.id == id }?.text

    fun save(file: File) {
        file.parentFile?.mkdirs()
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(render() + "\n", Charsets.UTF_8)
        if (!temp.renameTo(file)) {
            file.writeText(temp.readText(Charsets.UTF_8), Charsets.UTF_8)
            temp.delete()
        }
    }
}
