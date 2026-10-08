package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.workspace.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject

/** The shared queue for every agent on the link. Claiming is first-come, not assigned. */
class CrewTool(
    private val workspace: Workspace,
    private val owner: () -> String,
    private val publish: (String) -> Unit,
) : Tool {
    override val name = "crew"
    override val description =
        "Coordinate with the other agents on this link. There is no lead agent. " +
            "action=list shows the queue and the notes. action=add queues one independent piece. " +
            "action=claim takes an open task; an earlier claim keeps it. " +
            "action=done finishes a task you hold. action=release puts it back. " +
            "action=post shares a FACT, a FAIL, or a DONE in one line. Do not assign work to a named phone."
    override val parameters = objectSchema(
        properties = mapOf(
            "action" to stringProp("list, add, claim, done, release, or post."),
            "text" to stringProp("The task, or the one-line note."),
            "id" to stringProp("The task id from the queue, such as t1."),
            "kind" to stringProp("FACT, FAIL, or DONE. For post."),
        ),
        required = listOf("action"),
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val name = owner().ifBlank { "phone" }
        val file = CrewBoard.file(workspace.root)
        val action = args.stringArg("action")?.trim()?.lowercase().orEmpty()
        val (_, note) = CrewBoard.update(file) { board ->
            when (action) {
                "list" -> board.show()
                "add" -> {
                    val result = board.add(args.stringArg("text").orEmpty())
                    val id = Regex("t[0-9]+").find(result)?.value
                    val stored = id?.let { board.taskText(it) }
                    if (result.startsWith("Added") && id != null && stored != null) publish("ADD $id $stored")
                    result
                }
                "claim" -> {
                    val id = args.stringArg("id").orEmpty().trim()
                    val stamp = CrewBoard.stamp(System.currentTimeMillis(), name)
                    val result = board.apply("CLAIM $id $name $stamp")
                    if (result.startsWith("Claimed")) publish("CLAIM $id $name $stamp")
                    result
                }
                "done" -> finish(board, name, args.stringArg("id").orEmpty().trim(), "DONE")
                "release" -> finish(board, name, args.stringArg("id").orEmpty().trim(), "OPEN")
                "post" -> {
                    val kind = args.stringArg("kind").orEmpty().trim().ifBlank { "FACT" }
                    val text = args.stringArg("text").orEmpty()
                    val result = board.gist(kind, name, text)
                    if (result.startsWith("Shared")) publish("GIST ${kind.uppercase()} $name ${text.replace('\n', ' ').trim().take(180)}")
                    result
                }
                else -> "action is list, add, claim, done, release, or post."
            }
        }
        val failed = note.startsWith("action is") || note.startsWith("No task") || note.contains(" is held ") ||
            note.startsWith("A task") || note.startsWith("A note") || note.startsWith("CLAIM") ||
            note.startsWith("ADD needs") || note.startsWith("That crew") || note.startsWith("Unknown") ||
            note.startsWith("Empty")
        return@withContext if (failed) ToolResult.error(note) else ToolResult.ok(note)
    }

    private fun finish(board: CrewBoard, name: String, id: String, verb: String): String {
        val result = board.apply("$verb $id $name")
        if (result.startsWith("Finished") || result.startsWith("Released")) publish("$verb $id $name")
        return result
    }
}
