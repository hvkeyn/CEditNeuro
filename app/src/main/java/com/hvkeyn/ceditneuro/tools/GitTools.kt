package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.workspace.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffAlgorithm
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.treewalk.TreeWalk
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
    override val description =
        "Show the unstaged (or staged) changes as a unified diff. Unstaged files are read from " +
            "disk, so a change does not have to be git-added first."
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
        val only = args.stringArg("path")?.trim()?.trimStart('/')?.replace('\\', '/')?.ifBlank { null }

        repository.use { repo ->
            Git.wrap(repo).use { git ->
                val status = git.status().call()
                val paths = (if (staged) {
                    status.added + status.changed + status.removed
                } else {
                    status.modified + status.missing
                }).sorted().filter { path ->
                    only == null || path == only || path.startsWith("$only/")
                }
                if (paths.isEmpty()) return@withContext ToolResult.ok("(no changes)")

                val buffer = ByteArrayOutputStream()
                DiffFormatter(buffer).use { formatter ->
                    val algorithm = DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM)
                    for (path in paths) {
                        if (buffer.size() >= MAX_DIFF_CHARS) break
                        val before = if (staged) blob(repo, repo.resolve("HEAD^{tree}"), path) else indexBlob(repo, path)
                        val after = if (staged) indexBlob(repo, path) else workTreeBytes(repo, path)
                        writeDiff(formatter, algorithm, buffer, path, before, after)
                    }
                }
                val diff = buffer.toString(Charsets.UTF_8)
                val shown = if (diff.length > MAX_DIFF_CHARS) diff.take(MAX_DIFF_CHARS) + "\n... (truncated)" else diff
                ToolResult.ok(shown.ifBlank { "(no changes)" })
            }
        }
    }

    private fun writeDiff(
        formatter: DiffFormatter,
        algorithm: DiffAlgorithm,
        buffer: ByteArrayOutputStream,
        path: String,
        before: ByteArray?,
        after: ByteArray?,
    ) {
        buffer.write("diff --git a/$path b/$path\n".toByteArray(Charsets.UTF_8))
        if (before == null && after == null) {
            buffer.write("Could not read $path\n".toByteArray(Charsets.UTF_8))
            return
        }
        if ((before != null && looksBinary(before)) || (after != null && looksBinary(after))) {
            buffer.write("Binary files differ\n".toByteArray(Charsets.UTF_8))
            return
        }
        val oldText = if (before == null) RawText.EMPTY_TEXT else RawText(before)
        val newText = if (after == null) RawText.EMPTY_TEXT else RawText(after)
        val edits = algorithm.diff(RawTextComparator.DEFAULT, oldText, newText)
        if (edits.isEmpty()) return
        val oldLabel = if (before == null) "/dev/null" else "a/$path"
        val newLabel = if (after == null) "/dev/null" else "b/$path"
        buffer.write("--- $oldLabel\n+++ $newLabel\n".toByteArray(Charsets.UTF_8))
        formatter.format(edits, oldText, newText)
    }

    private fun indexBlob(repo: Repository, path: String): ByteArray? {
        val entry = repo.readDirCache().getEntry(path) ?: return null
        return readBlob(repo, entry.objectId)
    }

    private fun blob(repo: Repository, tree: ObjectId?, path: String): ByteArray? {
        if (tree == null) return null
        TreeWalk.forPath(repo, path, tree)?.use { walk ->
            return readBlob(repo, walk.getObjectId(0))
        }
        return null
    }

    private fun readBlob(repo: Repository, id: ObjectId): ByteArray? {
        val loader = repo.open(id)
        if (loader.size > MAX_DIFF_FILE) return null
        return loader.bytes
    }

    private fun workTreeBytes(repo: Repository, path: String): ByteArray? {
        val file = File(repo.workTree, path)
        if (!file.isFile) return null
        if (file.length() > MAX_DIFF_FILE) return null
        return file.readBytes()
    }

    private fun looksBinary(bytes: ByteArray): Boolean {
        val limit = minOf(bytes.size, 8192)
        for (index in 0 until limit) {
            if (bytes[index] == 0.toByte()) return true
        }
        return false
    }

    private companion object {
        const val MAX_DIFF_CHARS = 30_000
        const val MAX_DIFF_FILE = 1_000_000
    }
}
