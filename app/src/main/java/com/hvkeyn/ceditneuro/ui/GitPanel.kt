package com.hvkeyn.ceditneuro.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.io.File

data class GitFile(val path: String, val state: String)

data class GitSnapshot(
    val branch: String,
    val head: String,
    val files: List<GitFile>,
    val clean: Boolean,
)

private fun repositoryOf(root: File): org.eclipse.jgit.lib.Repository? {
    val gitDir = File(root, ".git")
    if (!gitDir.exists()) return null
    return runCatching {
        FileRepositoryBuilder()
            .setGitDir(gitDir)
            .setWorkTree(root)
            .setMustExist(true)
            .build()
    }.getOrNull()
}

private fun snapshotOf(root: File): GitSnapshot? {
    val repo = repositoryOf(root) ?: return null
    repo.use {
        Git.wrap(repo).use { git ->
            val status = git.status().call()
            val rows = buildList {
                status.added.forEach { add(GitFile(it, "added")) }
                status.changed.forEach { add(GitFile(it, "staged")) }
                status.modified.forEach { add(GitFile(it, "modified")) }
                status.missing.forEach { add(GitFile(it, "deleted")) }
                status.untracked.forEach { add(GitFile(it, "untracked")) }
                status.conflicting.forEach { add(GitFile(it, "conflict")) }
            }.distinctBy { it.path }.sortedBy { it.path }
            val head = repo.resolve("HEAD")?.let { id ->
                runCatching {
                    val commit = repo.parseCommit(id)
                    "${id.abbreviate(7).name()} ${commit.shortMessage}"
                }.getOrNull()
            }.orEmpty()
            return GitSnapshot(
                branch = repo.branch ?: "detached HEAD",
                head = head,
                files = rows,
                clean = status.isClean,
            )
        }
    }
}

private fun stageEverything(root: File): String? {
    val repo = repositoryOf(root) ?: return "Not a git repository."
    repo.use {
        Git.wrap(repo).use { git ->
            return runCatching {
                git.add().addFilepattern(".").call()
                git.add().setUpdate(true).addFilepattern(".").call()
                null
            }.getOrElse { error -> error.message ?: "Stage failed." }
        }
    }
}

private fun commitEverything(root: File, message: String): String? {
    if (message.isBlank()) return "Write a commit message first."
    val repo = repositoryOf(root) ?: return "Not a git repository."
    repo.use {
        Git.wrap(repo).use { git ->
            return runCatching {
                val config = repo.config
                val name = config.getString("user", null, "name")?.takeIf { it.isNotBlank() } ?: "CEditNeuro"
                val email = config.getString("user", null, "email")?.takeIf { it.isNotBlank() }
                    ?: "ceditneuro@localhost"
                val status = git.status().call()
                if (status.isClean) return "Nothing to commit."
                val commit = git.commit()
                    .setMessage(message.trim())
                    .setAuthor(name, email)
                    .setCommitter(name, email)
                    .call()
                "Committed ${commit.abbreviate(7).name()} on ${repo.branch ?: "detached HEAD"}."
            }.getOrElse { error -> error.message ?: "Commit failed." }
        }
    }
}

private fun pullOrigin(root: File, token: String): String {
    val repo = repositoryOf(root) ?: return "Not a git repository."
    repo.use {
        Git.wrap(repo).use { git ->
            return runCatching {
                val branch = repo.branch ?: return "Detached HEAD; checkout a branch first."
                val command = git.pull().setRemote("origin").setRemoteBranchName(branch)
                if (token.isNotBlank()) {
                    command.setCredentialsProvider(
                        org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider(
                            "x-access-token",
                            token.trim(),
                        ),
                    )
                }
                command.call()
                "Pulled origin/$branch."
            }.getOrElse { error -> error.message ?: "Pull failed." }
        }
    }
}

private fun pushOrigin(root: File, token: String): String {
    val repo = repositoryOf(root) ?: return "Not a git repository."
    repo.use {
        Git.wrap(repo).use { git ->
            return runCatching {
                val remote = repo.config.getString("remote", "origin", "url")
                if (remote.isNullOrBlank()) return "No origin remote configured."
                val command = git.push().setRemote("origin")
                if (token.isNotBlank()) {
                    command.setCredentialsProvider(
                        org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider(
                            "x-access-token",
                            token.trim(),
                        ),
                    )
                }
                val results = command.call()
                if (results.any { it.remoteUpdates.isNotEmpty() }) "Pushed to origin." else "Nothing to push."
            }.getOrElse { error -> error.message ?: "Push failed." }
        }
    }
}

@Composable
fun GitPanel(
    root: String?,
    onOpenFile: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (root.isNullOrBlank()) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Git") },
            text = { Text("Open a project first.") },
            confirmButton = { TextButton(onClick = onDismiss) { Text("Close") } },
        )
        return
    }
    val rootFile = remember(root) { File(root) }
    var reload by remember { mutableIntStateOf(0) }
    var snapshot by remember { mutableStateOf<GitSnapshot?>(null) }
    var message by remember { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf("") }
    var working by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(root, reload) {
        snapshot = withContext(Dispatchers.IO) { snapshotOf(rootFile) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Git")
                Text(
                    text = snapshot?.let { "${it.branch} · ${it.head.ifBlank { "no commits" }}" }
                        ?: "Reading repository…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if (snapshot == null) {
                    Text("No .git directory here, or the repository cannot be read.")
                } else {
                    val current = snapshot ?: return@Column
                    Text(
                        if (current.clean) "Working tree clean." else "${current.files.size} changed files",
                        style = MaterialTheme.typography.bodySmall,
                        color = if (current.clean) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
                    )
                    LazyColumn(modifier = Modifier.heightIn(max = 260.dp)) {
                        items(current.files, key = { it.path }) { file ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenFile(file.path) }
                                    .padding(vertical = 5.dp),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                Text(
                                    text = file.state,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = when (file.state) {
                                        "conflict" -> MaterialTheme.colorScheme.error
                                        "untracked", "added" -> MaterialTheme.colorScheme.tertiary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.width(66.dp),
                                )
                                Text(
                                    text = file.path,
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }
                    }
                    OutlinedTextField(
                        value = message,
                        onValueChange = { message = it },
                        label = { Text("Commit message") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = token,
                        onValueChange = { token = it },
                        label = { Text("GitHub token (optional, not saved)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                        TextButton(
                            enabled = !working && !current.clean,
                            onClick = {
                                working = true
                                scope.launch {
                                    val error = withContext(Dispatchers.IO) { stageEverything(rootFile) }
                                    notice = error ?: "Staged all changes."
                                    working = false
                                    reload++
                                }
                            },
                        ) { Text("Stage all") }
                        TextButton(
                            enabled = !working && !current.clean,
                            onClick = {
                                working = true
                                scope.launch {
                                    val error = withContext(Dispatchers.IO) {
                                        commitEverything(rootFile, message)
                                    }
                                    notice = error ?: "Committed."
                                    working = false
                                    message = ""
                                    reload++
                                }
                            },
                        ) { Text("Commit") }
                        TextButton(
                            enabled = !working,
                            onClick = {
                                working = true
                                scope.launch {
                                    notice = withContext(Dispatchers.IO) { pullOrigin(rootFile, token) }
                                    working = false
                                    reload++
                                }
                            },
                        ) { Text("Pull") }
                        TextButton(
                            enabled = !working,
                            onClick = {
                                working = true
                                scope.launch {
                                    notice = withContext(Dispatchers.IO) { pushOrigin(rootFile, token) }
                                    working = false
                                    reload++
                                }
                            },
                        ) { Text("Push") }
                        TextButton(onClick = { reload++ }) { Text("Refresh") }
                    }
                    if (notice.isNotBlank()) {
                        Text(
                            text = notice,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
