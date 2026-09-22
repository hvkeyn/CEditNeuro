package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.workspace.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.io.File

/** Walks the workspace, skipping ignored directories and oversized files. */
internal fun walkFiles(
    base: File,
    nameFilter: (String) -> Boolean = { true },
): Sequence<File> = sequence {
    val stack = ArrayDeque<File>()
    stack.addLast(base)
    while (stack.isNotEmpty()) {
        val dir = stack.removeLast()
        dir.listFiles().orEmpty().forEach { child ->
            when {
                child.isDirectory -> if (child.name !in Workspace.IGNORED_NAMES) stack.addLast(child)
                child.isFile -> if (child.length() <= Workspace.MAX_SEARCHABLE_FILE_BYTES && nameFilter(child.name)) {
                    yield(child)
                }
            }
        }
    }
}

/** Translates a glob (`**`, `*`, `?`) into a regex matched against workspace-relative paths. */
internal fun globToRegex(glob: String): Regex {
    val pattern = StringBuilder("^")
    var i = 0
    while (i < glob.length) {
        val c = glob[i]
        when (c) {
            '*' -> {
                if (i + 1 < glob.length && glob[i + 1] == '*') {
                    pattern.append(".*")
                    i++
                    if (i + 1 < glob.length && glob[i + 1] == '/') i++
                } else {
                    pattern.append("[^/]*")
                }
            }
            '?' -> pattern.append("[^/]")
            '.', '(', ')', '+', '|', '^', '$', '@', '%', '[', ']', '{', '}', '\\' ->
                pattern.append('\\').append(c)
            else -> pattern.append(c)
        }
        i++
    }
    pattern.append('$')
    return Regex(pattern.toString())
}

class ListDirTool(private val workspace: Workspace) : Tool {

    override val name = "list_dir"
    override val description = "List the direct children of a workspace directory."
    override val parameters = objectSchema(
        properties = mapOf(
            "path" to stringProp("Directory relative to the workspace root. Defaults to the root."),
        ),
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val path = args.stringArg("path").orEmpty()
        val entries = workspace.children(path)
        if (entries.isEmpty()) return@withContext ToolResult.ok("(empty)")
        val rendered = entries.joinToString("\n") { entry ->
            if (entry.isDirectory) "${entry.name}/" else "${entry.name}  (${entry.size} B)"
        }
        ToolResult.ok(rendered)
    }
}

class ReadFileTool(private val workspace: Workspace) : Tool {

    override val name = "read_file"
    override val description =
        "Read a text file from the workspace. Returns numbered lines. " +
            "Use offset and limit to page through large files."
    override val parameters = objectSchema(
        properties = mapOf(
            "path" to stringProp("File path relative to the workspace root."),
            "offset" to intProp("1-based first line to return. Defaults to 1."),
            "limit" to intProp("Maximum number of lines to return. Defaults to 400."),
        ),
        required = listOf("path"),
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val path = args.stringArg("path") ?: return@withContext ToolResult.error("Missing 'path'.")
        val offset = (args.intArg("offset") ?: 1).coerceAtLeast(1)
        val limit = (args.intArg("limit") ?: 400).coerceIn(1, 5_000)

        val file = workspace.resolve(path)
        if (!file.isFile) return@withContext ToolResult.error("Not a file: $path")

        val lines = file.readLines()
        val slice = lines.drop(offset - 1).take(limit)
        if (slice.isEmpty()) return@withContext ToolResult.ok("(no lines in the requested range)")

        val out = StringBuilder()
        slice.forEachIndexed { index, line ->
            out.append(offset + index).append('\t').append(line).append('\n')
        }
        if (offset - 1 + slice.size < lines.size) {
            out.append("... (file has ${lines.size} lines in total)")
        }
        ToolResult.ok(out.toString())
    }
}

class WriteFileTool(
    private val workspace: Workspace,
    private val onEdit: (path: String, before: String, after: String) -> Unit = { _, _, _ -> },
) : Tool {

    override val name = "write_file"
    override val description =
        "Create a file, or replace its entire contents. Prefer edit_file when changing " +
            "an existing file, so unrelated code is not lost."
    override val parameters = objectSchema(
        properties = mapOf(
            "path" to stringProp("File path relative to the workspace root."),
            "content" to stringProp("Full new contents of the file."),
        ),
        required = listOf("path", "content"),
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val path = args.stringArg("path") ?: return@withContext ToolResult.error("Missing 'path'.")
        val content = args.stringArg("content") ?: return@withContext ToolResult.error("Missing 'content'.")

        val file = workspace.resolve(path)
        if (file.isDirectory) return@withContext ToolResult.error("$path is a directory.")
        val before = if (file.isFile) file.readText() else ""

        workspace.write(path, content)
        onEdit(path, before, content)
        ToolResult.ok("Wrote ${content.length} characters to $path.")
    }
}

class EditFileTool(
    private val workspace: Workspace,
    private val onEdit: (path: String, before: String, after: String) -> Unit = { _, _, _ -> },
) : Tool {

    override val name = "edit_file"
    override val description =
        "Replace an exact substring in a workspace file. 'old_string' must match the file " +
            "byte for byte and must be unique unless replace_all is true. Include surrounding " +
            "lines to make the match unique."
    override val parameters = objectSchema(
        properties = mapOf(
            "path" to stringProp("File path relative to the workspace root."),
            "old_string" to stringProp("Exact existing text to replace."),
            "new_string" to stringProp("Replacement text. May be empty to delete."),
            "replace_all" to boolProp("Replace every occurrence. Defaults to false."),
        ),
        required = listOf("path", "old_string", "new_string"),
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val path = args.stringArg("path") ?: return@withContext ToolResult.error("Missing 'path'.")
        val oldString = args.stringArg("old_string") ?: return@withContext ToolResult.error("Missing 'old_string'.")
        val newString = args.stringArg("new_string") ?: return@withContext ToolResult.error("Missing 'new_string'.")
        val replaceAll = args.boolArg("replace_all") ?: false

        if (oldString.isEmpty()) return@withContext ToolResult.error("'old_string' must not be empty.")

        val file = workspace.resolve(path)
        if (!file.isFile) return@withContext ToolResult.error("Not a file: $path")

        val before = file.readText()
        val occurrences = countOccurrences(before, oldString)
        if (occurrences == 0) {
            return@withContext ToolResult.error("'old_string' was not found in $path. Read the file and retry.")
        }
        if (occurrences > 1 && !replaceAll) {
            return@withContext ToolResult.error(
                "'old_string' occurs $occurrences times in $path. Add more context to make it " +
                    "unique, or pass replace_all=true.",
            )
        }

        val after = if (replaceAll) before.replace(oldString, newString) else before.replaceFirst(oldString, newString)
        file.writeText(after)
        onEdit(path, before, after)

        val replaced = if (replaceAll) occurrences else 1
        val diff = TextDiff.unified(before, after, maxOutputLines = 60)
        ToolResult.ok(
            buildString {
                append("Applied $replaced replacement(s) in $path.\n")
                if (diff.isNotBlank()) append(diff)
            },
        )
    }

    private fun countOccurrences(haystack: String, needle: String): Int {
        var count = 0
        var index = haystack.indexOf(needle)
        while (index >= 0) {
            count++
            index = haystack.indexOf(needle, index + needle.length)
        }
        return count
    }
}

class GrepTool(private val workspace: Workspace) : Tool {

    override val name = "grep"
    override val description =
        "Search file contents in the workspace with a regular expression. " +
            "Returns 'path:line: text' matches."
    override val parameters = objectSchema(
        properties = mapOf(
            "pattern" to stringProp("Regular expression to search for."),
            "path" to stringProp("Subdirectory to search. Defaults to the workspace root."),
            "glob" to stringProp("Optional filename filter, for example *.kt."),
            "max_results" to intProp("Maximum matches to return. Defaults to 100."),
        ),
        required = listOf("pattern"),
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val patternText = args.stringArg("pattern") ?: return@withContext ToolResult.error("Missing 'pattern'.")
        val regex = runCatching { Regex(patternText) }.getOrElse {
            return@withContext ToolResult.error("Invalid regular expression: ${it.message}")
        }
        val maxResults = (args.intArg("max_results") ?: 100).coerceIn(1, 1_000)
        val base = runCatching { workspace.resolve(args.stringArg("path").orEmpty()) }.getOrElse {
            return@withContext ToolResult.error(it.message ?: "Invalid path.")
        }
        if (!base.exists()) return@withContext ToolResult.error("No such path.")

        val globMatcher = args.stringArg("glob")?.let { globToRegex(it) }

        val results = StringBuilder()
        var matches = 0
        var limitReached = false

        for (file in walkFiles(base)) {
            if (limitReached) break
            if (globMatcher != null && !globMatcher.matches(file.name)) continue
            val lines = runCatching { file.readLines() }.getOrNull() ?: continue
            val relative = workspace.relativize(file)

            for ((index, line) in lines.withIndex()) {
                if (matches >= maxResults) {
                    limitReached = true
                    break
                }
                if (regex.containsMatchIn(line)) {
                    results.append(relative)
                        .append(':')
                        .append(index + 1)
                        .append(": ")
                        .append(line.trim().take(240))
                        .append('\n')
                    matches++
                }
            }
        }

        if (matches == 0) ToolResult.ok("(no matches)") else ToolResult.ok(results.toString())
    }
}

class GlobTool(private val workspace: Workspace) : Tool {

    override val name = "glob"
    override val description =
        "Find files by path pattern, for example '**/*.kt' or 'src/**/*Test*'. " +
            "Returns workspace-relative paths."
    override val parameters = objectSchema(
        properties = mapOf(
            "pattern" to stringProp("Glob pattern matched against workspace-relative paths."),
            "max_results" to intProp("Maximum paths to return. Defaults to 200."),
        ),
        required = listOf("pattern"),
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val patternText = args.stringArg("pattern") ?: return@withContext ToolResult.error("Missing 'pattern'.")
        val maxResults = (args.intArg("max_results") ?: 200).coerceIn(1, 2_000)
        val matcher = globToRegex(patternText)

        val paths = ArrayList<String>()
        for (file in walkFiles(workspace.root)) {
            if (paths.size >= maxResults) break
            val relative = workspace.relativize(file)
            if (matcher.matches(relative)) paths.add(relative)
        }

        if (paths.isEmpty()) ToolResult.ok("(no files matched)") else ToolResult.ok(paths.joinToString("\n"))
    }
}
