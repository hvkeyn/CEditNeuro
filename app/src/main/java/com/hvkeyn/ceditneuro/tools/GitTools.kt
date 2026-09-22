package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.workspace.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.filter.PathFilter
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Opens the repository that contains the workspace root. Returns null when the project is
 * not under version control, which the agent is expected to handle gracefully.
 */
internal fun openRepository(workspace: Workspace): Repository? {
    val gitDir = File(workspace.root, ".git")
    if (!gitDir.exists()) return null
    return runCatching {
        FileRepositoryBuilder()
            .setGitDir(gitDir)
            .setWorkTree(workspace.root)
            .setMustExist(true)
            .build()
    }.getOrNull()
}

class GitStatusTool(private val workspace: Workspace) : Tool {

    override val name = "git_status"
    override val description = "Show the branch and the working tree status of the project repository."
    override val parameters = objectSchema(properties = emptyMap())

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val repository = openRepository(workspace)
            ?: return@withContext ToolResult.error("The project is not a git repository.")

        repository.use { repo ->
            Git.wrap(repo).use { git ->
                val status = git.status().call()
                val text = buildString {
                    appendLine("branch: ${repo.branch ?: "(detached HEAD)"}")
                    appendIfNotEmpty("added", status.added)
                    appendIfNotEmpty("changed", status.changed)
                    appendIfNotEmpty("modified", status.modified)
                    appendIfNotEmpty("deleted", status.missing)
                    appendIfNotEmpty("untracked", status.untracked)
                    appendIfNotEmpty("conflicting", status.conflicting)
                    if (status.isClean) appendLine("working tree clean")
                }
                ToolResult.ok(text)
            }
        }
    }

    private fun StringBuilder.appendIfNotEmpty(label: String, values: Set<String>) {
        if (values.isNotEmpty()) appendLine("$label: ${values.sorted().joinToString(", ")}")
    }
}

class GitDiffTool(private val workspace: Workspace) : Tool {

    override val name = "git_diff"
    override val description = "Show the unstaged (or staged) changes as a unified diff."
    override val parameters = objectSchema(
        properties = mapOf(
            "staged" to boolProp("Show the staged index diff instead of the working tree. Defaults to false."),
            "path" to stringProp("Limit the diff to this workspace-relative path."),
        ),
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        val repository = openRepository(workspace)
            ?: return@withContext ToolResult.error("The project is not a git repository.")
        val staged = args.boolArg("staged") ?: false

        repository.use { repo ->
            Git.wrap(repo).use { git ->
                val command = git.diff().setCached(staged)
                args.stringArg("path")?.let { command.setPathFilter(PathFilter.create(it)) }

                val entries = command.call()
                if (entries.isEmpty()) return@withContext ToolResult.ok("(no changes)")

                val buffer = ByteArrayOutputStream()
                DiffFormatter(buffer).use { formatter ->
                    formatter.setRepository(repo)
                    formatter.setDiffComparator(RawTextComparator.DEFAULT)
                    formatter.isDetectRenames = true
                    formatter.format(entries)
                }
                val diff = buffer.toString("UTF-8")
                ToolResult.ok(if (diff.length > 30_000) diff.take(30_000) + "\n... (truncated)" else diff)
            }
        }
    }
}
