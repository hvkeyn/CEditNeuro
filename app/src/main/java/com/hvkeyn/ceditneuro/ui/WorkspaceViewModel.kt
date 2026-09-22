package com.hvkeyn.ceditneuro.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hvkeyn.ceditneuro.agent.AgentEvent
import com.hvkeyn.ceditneuro.agent.AgentLoop
import com.hvkeyn.ceditneuro.agent.ChatMessage
import com.hvkeyn.ceditneuro.agent.buildSystemPrompt
import com.hvkeyn.ceditneuro.agent.deepseek.DeepSeekBackend
import com.hvkeyn.ceditneuro.data.AgentSettings
import com.hvkeyn.ceditneuro.data.RemoteServer
import com.hvkeyn.ceditneuro.data.ProjectLibrary
import com.hvkeyn.ceditneuro.data.SettingsStore
import com.hvkeyn.ceditneuro.data.StoredChat
import com.hvkeyn.ceditneuro.data.StoredSession
import com.hvkeyn.ceditneuro.data.StoredShell
import com.hvkeyn.ceditneuro.net.AgentNet
import com.hvkeyn.ceditneuro.net.RemoteClient
import com.hvkeyn.ceditneuro.shell.DeviceShell
import com.hvkeyn.ceditneuro.shell.ProgramRun
import com.hvkeyn.ceditneuro.tools.BrowsePageTool
import com.hvkeyn.ceditneuro.tools.EditFileTool
import com.hvkeyn.ceditneuro.tools.HttpRequestTool
import com.hvkeyn.ceditneuro.tools.InstallModuleTool
import com.hvkeyn.ceditneuro.tools.InstallJdkTool
import com.hvkeyn.ceditneuro.tools.InstallProgramTool
import com.hvkeyn.ceditneuro.tools.GitDiffTool
import com.hvkeyn.ceditneuro.tools.GitStatusTool
import com.hvkeyn.ceditneuro.tools.GlobTool
import com.hvkeyn.ceditneuro.tools.GrepTool
import com.hvkeyn.ceditneuro.tools.ListDirTool
import com.hvkeyn.ceditneuro.tools.ReadFileTool
import com.hvkeyn.ceditneuro.tools.RemoteConnectTool
import com.hvkeyn.ceditneuro.tools.RemoteGetTool
import com.hvkeyn.ceditneuro.tools.RemoteListTool
import com.hvkeyn.ceditneuro.tools.RemotePutTool
import com.hvkeyn.ceditneuro.tools.RemoteReadTool
import com.hvkeyn.ceditneuro.tools.RemoteWriteTool
import com.hvkeyn.ceditneuro.tools.SshExecTool
import com.hvkeyn.ceditneuro.tools.ShellTool
import com.hvkeyn.ceditneuro.tools.Tool
import com.hvkeyn.ceditneuro.tools.ToolRegistry
import com.hvkeyn.ceditneuro.tools.WriteFileTool
import com.hvkeyn.ceditneuro.workspace.FileEntry
import com.hvkeyn.ceditneuro.workspace.Workspace
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** A file open in the editor. [onDisk] is the last known saved content. */
data class OpenFile(val path: String, val onDisk: String)

enum class ChatRole { User, Assistant, Reasoning, Tool, Error }

data class ChatEntry(
    val id: Long,
    val role: ChatRole,
    val text: String,
    val toolName: String? = null,
    val streaming: Boolean = false,
)

data class ShellLine(
    val id: Long,
    val command: String,
    val output: String,
    val running: Boolean = false,
)

data class HostTrustPrompt(val serverId: String, val host: String, val fingerprint: String)

data class WorkspaceUiState(
    val projectRoot: String? = null,
    val recentProjects: List<String> = emptyList(),
    val rootEntries: List<FileEntry> = emptyList(),
    val dirContents: Map<String, List<FileEntry>> = emptyMap(),
    val expandedDirs: Set<String> = emptySet(),
    val openFiles: List<OpenFile> = emptyList(),
    val activePath: String? = null,
    val dirtyPaths: Set<String> = emptySet(),
    val reloadCounter: Long = 0L,
    val chat: List<ChatEntry> = emptyList(),
    val chatVisible: Boolean = false,
    val shellVisible: Boolean = false,
    val shellRunning: Boolean = false,
    val shellLines: List<ShellLine> = emptyList(),
    val agentRunning: Boolean = false,
    val message: String? = null,
    val execPrompt: String? = null,
    val hostPrompt: HostTrustPrompt? = null,
    val webVisible: Boolean = false,
    val webUrl: String = "",
    val webGeneration: Int = 0,
) {
    val projectName: String?
        get() = projectRoot?.let { File(it).name.ifBlank { it } }
}

class WorkspaceViewModel(
    private val appContext: Context,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _state = MutableStateFlow(WorkspaceUiState())
    val state: StateFlow<WorkspaceUiState> = _state.asStateFlow()

    val settings: StateFlow<AgentSettings> = settingsStore.settings

    private var workspace: Workspace? = null
    private val deviceShell = DeviceShell(appContext) { settingsStore.current.networkEnabled }
    private val agentNet = AgentNet()
    private val execMutex = Mutex()
    private var execWaiter: CompletableDeferred<Boolean>? = null
    private val hostMutex = Mutex()
    private var hostWaiter: CompletableDeferred<Boolean>? = null
    private var browseWaiter: CompletableDeferred<String>? = null
    private var browseReportJob: Job? = null
    private val remoteClient = RemoteClient(::ensureHostTrusted)
    private val library = ProjectLibrary(appContext)

    /** Live editor text per open file, including unsaved changes. */
    private val buffers = mutableMapOf<String, String>()

    /** Conversation handed back to the model on the next request. */
    private val conversation = mutableListOf<ChatMessage>()

    private var agentJob: Job? = null
    private var persistJob: Job? = null
    private val idGenerator = AtomicLong(0)
    private val treeRefresh = AtomicLong(0)
    private val sessionEpoch = AtomicLong(0)

    /**
     * Opens the last folder after process death, once storage access is granted.
     * Missing folders are dropped from the list. Chat and shell stay with their project.
     */
    fun restoreLastProject(storageGranted: Boolean) {
        if (!storageGranted) return
        if (workspace != null) {
            _state.update { it.copy(recentProjects = library.roots()) }
            return
        }
        val live = library.roots().filter { path -> File(path).isDirectory }
        library.replaceRoots(live)
        _state.update { it.copy(recentProjects = live) }
        live.firstOrNull()?.let { openProject(File(it)) }
    }

    fun openProject(root: File) {
        val canonical = runCatching { root.canonicalFile }.getOrElse { error ->
            showMessage("Cannot open project: ${error.message}")
            return
        }
        if (!canonical.isDirectory) {
            library.forget(canonical.path)
            _state.update { it.copy(recentProjects = library.roots()) }
            showMessage("Folder is gone: ${canonical.path}")
            return
        }
        if (workspace?.root?.canonicalPath == canonical.path) {
            library.save(canonical.path, snapshotSession())
            _state.update { it.copy(projectRoot = canonical.path, recentProjects = library.roots()) }
            refreshProjectTree()
            return
        }

        sessionEpoch.incrementAndGet()
        agentJob?.cancel()
        agentJob = null
        persistJob?.cancel()
        persistNow()

        val opened = runCatching { Workspace(canonical) }.getOrElse { error ->
            showMessage("Cannot open project: ${error.message}")
            return
        }
        val session = library.load(canonical.path)
        workspace = opened
        buffers.clear()
        conversation.clear()
        conversation += session.conversation
        idGenerator.set(session.nextId)
        library.save(canonical.path, session)

        _state.value = WorkspaceUiState(
            projectRoot = canonical.path,
            recentProjects = library.roots(),
            rootEntries = opened.children(),
            chat = session.chat.map { entry ->
                ChatEntry(
                    id = entry.id,
                    role = runCatching { ChatRole.valueOf(entry.role) }.getOrDefault(ChatRole.Assistant),
                    text = entry.text,
                    toolName = entry.toolName,
                )
            },
            shellLines = session.shell.map { line ->
                ShellLine(id = line.id, command = line.command, output = line.output)
            },
            chatVisible = _state.value.chatVisible,
            shellVisible = _state.value.shellVisible,
        )
    }

    fun forgetProject(path: String) {
        val canonical = runCatching { File(path).canonicalPath }.getOrDefault(path)
        val leavingCurrent = workspace?.root?.canonicalPath == canonical
        if (leavingCurrent) {
            sessionEpoch.incrementAndGet()
            agentJob?.cancel()
            agentJob = null
            persistJob?.cancel()
            workspace = null
            buffers.clear()
            conversation.clear()
        }
        library.forget(canonical)
        val remaining = library.roots()
        if (!leavingCurrent) {
            _state.update { it.copy(recentProjects = remaining) }
            return
        }
        _state.value = WorkspaceUiState(
            recentProjects = remaining,
            chatVisible = _state.value.chatVisible,
            shellVisible = _state.value.shellVisible,
        )
        remaining.firstOrNull()?.let { openProject(File(it)) }
    }

    fun toggleDirectory(path: String) {
        val ws = workspace ?: return
        _state.update { current ->
            if (path in current.expandedDirs) {
                current.copy(expandedDirs = current.expandedDirs - path)
            } else {
                current.copy(
                    expandedDirs = current.expandedDirs + path,
                    dirContents = current.dirContents + (path to ws.children(path)),
                )
            }
        }
    }

    fun openFile(path: String) {
        val ws = workspace ?: return
        if (_state.value.openFiles.any { it.path == path }) {
            _state.update { it.copy(activePath = path) }
            return
        }
        val content = runCatching { ws.read(path) }.getOrElse { error ->
            showMessage("Cannot read $path: ${error.message}")
            return
        }
        buffers[path] = content
        _state.update {
            it.copy(openFiles = it.openFiles + OpenFile(path, content), activePath = path)
        }
    }

    fun closeFile(path: String) {
        buffers.remove(path)
        _state.update { current ->
            val remaining = current.openFiles.filterNot { it.path == path }
            val nextActive = if (current.activePath == path) {
                remaining.lastOrNull()?.path
            } else {
                current.activePath
            }
            current.copy(
                openFiles = remaining,
                activePath = nextActive,
                dirtyPaths = current.dirtyPaths - path,
            )
        }
    }

    fun setActiveFile(path: String) {
        _state.update { it.copy(activePath = path) }
    }

    /** Current editor text for [path], preferring unsaved buffer content. */
    fun contentFor(path: String): String =
        buffers[path]
            ?: _state.value.openFiles.firstOrNull { it.path == path }?.onDisk
            ?: ""

    fun onEditorTextChanged(path: String, text: String) {
        if (buffers[path] == text) return
        buffers[path] = text
        if (path !in _state.value.dirtyPaths) {
            _state.update { it.copy(dirtyPaths = it.dirtyPaths + path) }
        }
    }

    fun saveActiveFile() {
        _state.value.activePath?.let(::saveFile)
    }

    private fun saveFile(path: String) {
        val ws = workspace ?: return
        val text = buffers[path] ?: return
        runCatching { ws.write(path, text) }
            .onSuccess {
                _state.update { current ->
                    current.copy(
                        dirtyPaths = current.dirtyPaths - path,
                        openFiles = current.openFiles.map {
                            if (it.path == path) it.copy(onDisk = text) else it
                        },
                    )
                }
            }
            .onFailure { showMessage("Save failed: ${it.message}") }
    }

    fun toggleChat() {
        _state.update { it.copy(chatVisible = !it.chatVisible, shellVisible = false, webVisible = false) }
    }

    fun toggleShell() {
        _state.update { it.copy(shellVisible = !it.shellVisible, chatVisible = false, webVisible = false) }
    }

    fun toggleWeb() {
        if (_state.value.webVisible) {
            finishBrowse("The user closed the browser before the page finished.")
            _state.update { it.copy(webVisible = false) }
            return
        }
        val url = activeRemote()?.webUrl.orEmpty()
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            showMessage("Set an http or https site URL on the selected server.")
            return
        }
        _state.update {
            it.copy(
                webVisible = true,
                webUrl = url,
                webGeneration = it.webGeneration + 1,
                chatVisible = false,
                shellVisible = false,
            )
        }
    }

    fun onBrowseLoaded(title: String, text: String) {
        browseReportJob?.cancel()
        browseReportJob = viewModelScope.launch {
            delay(400)
            val waiter = browseWaiter ?: return@launch
            if (waiter.isCompleted) return@launch
            val url = _state.value.webUrl
            val body = text.trim().take(6000)
            waiter.complete(
                buildString {
                    append("URL: ").append(url)
                    if (title.isNotBlank()) append("\nTitle: ").append(title)
                    append("\n\n")
                    append(if (body.isBlank()) "The page loaded with no visible text." else body)
                },
            )
        }
    }

    suspend fun browsePage(url: String): String {
        val trimmed = url.trim()
        if (!trimmed.startsWith("http://") && !trimmed.startsWith("https://")) {
            return "Only http and https pages can be opened in the browser."
        }
        finishBrowse("Superseded by a newer page.")
        val waiter = CompletableDeferred<String>()
        browseWaiter = waiter
        _state.update {
            it.copy(
                webVisible = true,
                webUrl = trimmed,
                webGeneration = it.webGeneration + 1,
                shellVisible = false,
            )
        }
        return try {
            withTimeout(25_000) { waiter.await() }
        } catch (error: TimeoutCancellationException) {
            "The browser did not finish loading $trimmed."
        }
    }

    private fun finishBrowse(message: String) {
        browseReportJob?.cancel()
        browseWaiter?.let { waiter ->
            if (!waiter.isCompleted) waiter.complete(message)
        }
    }

    fun testRemote(server: RemoteServer) {
        viewModelScope.launch {
            val message = runCatching { remoteClient.probe(server) }
                .fold({ it }, { error -> error.message ?: "Connection failed." })
            showMessage(message)
        }
    }

    fun runShellCommand(command: String) {
        val root = workspace?.root ?: run {
            showMessage("Open a project folder first.")
            return
        }
        val trimmed = command.trim()
        if (trimmed.isEmpty() || _state.value.shellRunning) return
        val epoch = sessionEpoch.get()
        val id = nextId()
        _state.update {
            it.copy(
                shellVisible = true,
                shellRunning = true,
                shellLines = it.shellLines + ShellLine(id, trimmed, "", running = true),
            )
        }
        schedulePersist()
        viewModelScope.launch {
            val rendered = runCatching {
                if (ProgramRun.needsConsent(trimmed) && !ensureExecAllowed(
                        "The command runs a program outside the system shell.",
                    )
                ) {
                    "exit=1\nRunning installed programs is not allowed. Turn it on in Settings to run compilers."
                } else {
                    withContext(Dispatchers.IO) {
                        deviceShell.run(trimmed, root, timeoutSeconds = 120).render()
                    }
                }
            }.getOrElse { error -> "exit=1\n${error.message}" }
            if (sessionEpoch.get() != epoch) {
                rememberFinishedCommand(root.canonicalPath, trimmed, rendered, id)
                return@launch
            }
            _state.update { current ->
                current.copy(
                    shellRunning = false,
                    shellLines = current.shellLines.map { line ->
                        if (line.id == id) line.copy(output = rendered, running = false) else line
                    },
                )
            }
            refreshProjectTree()
            persistNow()
        }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    fun updateSettings(transform: (AgentSettings) -> AgentSettings) {
        val before = settingsStore.current.execAllowed
        settingsStore.update(transform)
        val after = settingsStore.current.execAllowed
        if (before != after && after != null) {
            _state.update { it.copy(execPrompt = null) }
            execWaiter?.complete(after)
        }
    }

    fun answerHostPrompt(allow: Boolean) {
        val prompt = _state.value.hostPrompt
        if (allow && prompt != null) {
            settingsStore.update { settings ->
                settings.copy(
                    remotes = settings.remotes.map { server ->
                        if (server.id == prompt.serverId) server.copy(trustedFingerprint = prompt.fingerprint) else server
                    },
                )
            }
        }
        _state.update { it.copy(hostPrompt = null) }
        hostWaiter?.complete(allow)
    }

    suspend fun ensureHostTrusted(serverId: String, host: String, fingerprint: String): Boolean {
        val known = settingsStore.current.remotes.find { it.id == serverId }
        if (known?.trustedFingerprint == fingerprint && fingerprint.isNotBlank()) return true
        return hostMutex.withLock {
            val again = settingsStore.current.remotes.find { it.id == serverId }
            if (again?.trustedFingerprint == fingerprint && fingerprint.isNotBlank()) return@withLock true
            val waiter = CompletableDeferred<Boolean>()
            hostWaiter = waiter
            _state.update { it.copy(hostPrompt = HostTrustPrompt(serverId, host, fingerprint)) }
            try {
                waiter.await()
            } finally {
                if (hostWaiter === waiter) {
                    hostWaiter = null
                    _state.update { it.copy(hostPrompt = null) }
                }
            }
        }
    }

    private fun activeRemote(): RemoteServer? =
        settingsStore.current.remotes.find { it.id == settingsStore.current.activeRemoteId }

    fun adoptRemote(server: RemoteServer): RemoteServer {
        val settings = settingsStore.current
        val existing = settings.remotes.find {
            it.protocol == server.protocol &&
                it.host.equals(server.host, ignoreCase = true) &&
                it.port == server.port &&
                it.username == server.username
        }
        val saved = if (existing == null) {
            server
        } else {
            server.copy(
                id = existing.id,
                name = existing.name.ifBlank { server.name },
                trustedFingerprint = existing.trustedFingerprint,
            )
        }
        settingsStore.update {
            val remotes = if (existing == null) {
                it.remotes + saved
            } else {
                it.remotes.map { current -> if (current.id == saved.id) saved else current }
            }
            it.copy(remotes = remotes, activeRemoteId = saved.id)
        }
        return saved
    }

    fun answerExecPrompt(allow: Boolean) {
        settingsStore.update { it.copy(execAllowed = allow) }
        _state.update { it.copy(execPrompt = null) }
        execWaiter?.complete(allow)
    }

    /**
     * Shows the one-time allow dialog. A settings switch can answer it later without asking again.
     */
    suspend fun ensureExecAllowed(reason: String): Boolean {
        settingsStore.current.execAllowed?.let { return it }
        return execMutex.withLock {
            settingsStore.current.execAllowed?.let { return@withLock it }
            val waiter = CompletableDeferred<Boolean>()
            execWaiter = waiter
            _state.update { it.copy(execPrompt = reason) }
            try {
                waiter.await()
            } finally {
                if (execWaiter === waiter) {
                    execWaiter = null
                    _state.update { it.copy(execPrompt = null) }
                }
            }
        }
    }

    fun sendPrompt(prompt: String) {
        val ws = workspace ?: run {
            showMessage("Open a project folder first.")
            return
        }
        if (prompt.isBlank() || _state.value.agentRunning) return

        appendChat(ChatRole.User, prompt)
        val history = conversation.toList()
        conversation += ChatMessage.user(prompt)
        val epoch = sessionEpoch.get()

        val loop = buildAgentLoop(ws)
        val finalText = StringBuilder()

        agentJob = viewModelScope.launch {
            _state.update { it.copy(agentRunning = true, chatVisible = true, shellVisible = false, message = null) }

            runCatching {
                loop.run(history, prompt).collect { event ->
                    if (sessionEpoch.get() == epoch) handleEvent(event, finalText)
                }
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (sessionEpoch.get() == epoch) {
                    appendChat(ChatRole.Error, error.message ?: error.toString())
                }
            }

            if (sessionEpoch.get() != epoch) return@launch
            if (finalText.isNotBlank()) {
                conversation += ChatMessage.assistant(finalText.toString())
            }
            _state.update { it.copy(agentRunning = false) }
            persistNow()
        }
    }

    fun cancelAgent() {
        agentJob?.cancel()
        agentJob = null
        _state.update { it.copy(agentRunning = false) }
        appendChat(ChatRole.Error, "Stopped by the user.")
    }

    private fun handleEvent(event: AgentEvent, finalText: StringBuilder) {
        when (event) {
            is AgentEvent.AssistantText -> {
                finalText.append(event.text)
                appendStreaming(ChatRole.Assistant, event.text)
            }

            is AgentEvent.Reasoning -> appendStreaming(ChatRole.Reasoning, event.text)

            is AgentEvent.ToolStarted -> appendChat(
                role = ChatRole.Tool,
                text = visibleToolArguments(event.name, event.arguments),
                toolName = "${event.name} →",
            )

            is AgentEvent.ToolFinished -> appendChat(
                role = if (event.result.isError) ChatRole.Error else ChatRole.Tool,
                text = event.result.content,
                toolName = "${event.name} ←",
            )

            is AgentEvent.TurnFinished -> Unit

            is AgentEvent.Failed -> appendChat(ChatRole.Error, event.message)
        }
    }

    private fun visibleToolArguments(name: String, arguments: String): String {
        val shown = if (name == "remote_connect") {
            arguments
                .replace(PASSWORD_FIELD, "\"password\":\"***\"")
                .replace(URL_PASSWORD, "$1:***@")
        } else {
            arguments
        }
        return shown.ifBlank { "(no arguments)" }
    }

    private fun appendChat(role: ChatRole, text: String, toolName: String? = null) {
        _state.update { current ->
            val sealed = current.chat.map { if (it.streaming) it.copy(streaming = false) else it }
            current.copy(chat = sealed + ChatEntry(nextId(), role, text, toolName))
        }
        schedulePersist()
    }

    private fun appendStreaming(role: ChatRole, delta: String) {
        _state.update { current ->
            val last = current.chat.lastOrNull()
            if (last != null && last.role == role && last.streaming) {
                current.copy(
                    chat = current.chat.dropLast(1) + last.copy(text = last.text + delta),
                )
            } else {
                val sealed = current.chat.map { if (it.streaming) it.copy(streaming = false) else it }
                current.copy(chat = sealed + ChatEntry(nextId(), role, delta, streaming = true))
            }
        }
        schedulePersist()
    }

    /** Called from file tools after the agent creates or edits a file. */
    private fun onAgentEditedFile(path: String) {
        refreshOpenFile(path)
        refreshProjectTree(path)
    }

    private fun refreshOpenFile(path: String) {
        val ws = workspace ?: return
        if (_state.value.openFiles.none { it.path == path }) return
        if (path in _state.value.dirtyPaths) {
            showMessage("The agent changed $path, but you have unsaved edits. Save or revert, then reopen it.")
            return
        }
        val fresh = runCatching { ws.read(path) }.getOrNull() ?: return
        buffers[path] = fresh
        _state.update { current ->
            current.copy(
                openFiles = current.openFiles.map {
                    if (it.path == path) it.copy(onDisk = fresh) else it
                },
                reloadCounter = current.reloadCounter + 1,
            )
        }
    }

    /**
     * Reloads the visible tree from disk. [revealPath] expands every parent directory
     * so a file the agent just created shows up without reopening the project.
     */
    private fun refreshProjectTree(revealPath: String? = null) {
        val ws = workspace ?: return
        val generation = treeRefresh.incrementAndGet()
        val parents = ancestorDirs(revealPath)
        viewModelScope.launch(Dispatchers.IO) {
            val expandedNow = _state.value.expandedDirs + parents
            val rootEntries = runCatching { ws.children() }.getOrDefault(emptyList())
            val contents = expandedNow.associateWith { dir ->
                runCatching { ws.children(dir) }.getOrDefault(emptyList())
            }
            if (generation != treeRefresh.get()) return@launch
            _state.update { current ->
                if (generation != treeRefresh.get()) {
                    current
                } else {
                    current.copy(
                        rootEntries = rootEntries,
                        expandedDirs = current.expandedDirs + parents,
                        dirContents = current.dirContents + contents,
                    )
                }
            }
        }
    }

    private fun ancestorDirs(relativePath: String?): Set<String> {
        val parent = relativePath
            ?.replace('\\', '/')
            ?.trim('/')
            ?.substringBeforeLast('/', "")
            .orEmpty()
        if (parent.isEmpty()) return emptySet()
        val parts = parent.split('/')
        return parts.indices.map { index -> parts.take(index + 1).joinToString("/") }.toSet()
    }

    private fun buildAgentLoop(ws: Workspace): AgentLoop {
        val epochAtBuild = sessionEpoch.get()
        val changeListener: (String, String, String) -> Unit = { path, _, _ ->
            if (sessionEpoch.get() == epochAtBuild) onAgentEditedFile(path)
        }

        val tools = mutableListOf<Tool>(
            ListDirTool(ws),
            ReadFileTool(ws),
            WriteFileTool(ws, changeListener),
            EditFileTool(ws, changeListener),
            GrepTool(ws),
            GlobTool(ws),
            GitStatusTool(ws),
            GitDiffTool(ws),
            ShellTool(deviceShell, ws.root, this::ensureExecAllowed) { command, rendered ->
                if (sessionEpoch.get() != epochAtBuild) {
                    rememberFinishedCommand(ws.root.canonicalPath, command, rendered)
                    return@ShellTool
                }
                appendShellLine(command, rendered)
                refreshProjectTree()
            },
            HttpRequestTool(ws, agentNet, { settingsStore.current.networkEnabled }) { path ->
                if (sessionEpoch.get() == epochAtBuild) onAgentEditedFile(path)
            },
            InstallModuleTool(ws, agentNet, { settingsStore.current.networkEnabled }) { path ->
                if (sessionEpoch.get() == epochAtBuild) onAgentEditedFile(path)
            },
            InstallJdkTool(
                toolchain = deviceShell.toolchain,
                net = agentNet,
                networkAllowed = { settingsStore.current.networkEnabled },
                ensureExec = this::ensureExecAllowed,
            ),
            InstallProgramTool(
                workspace = ws,
                filesDir = appContext.filesDir,
                toolchain = deviceShell.toolchain,
                net = agentNet,
                networkAllowed = { settingsStore.current.networkEnabled },
                ensureExec = this::ensureExecAllowed,
            ),
            RemoteConnectTool(remoteClient, this::adoptRemote) { settingsStore.current.networkEnabled },
            BrowsePageTool({ settingsStore.current.networkEnabled }, this::browsePage),
            RemoteListTool(::activeRemote, remoteClient),
            RemoteReadTool(::activeRemote, remoteClient),
            RemoteWriteTool(::activeRemote, remoteClient),
            RemotePutTool(::activeRemote, remoteClient, ws),
            RemoteGetTool(::activeRemote, remoteClient, ws) { path ->
                if (sessionEpoch.get() == epochAtBuild) onAgentEditedFile(path)
            },
            SshExecTool(::activeRemote, remoteClient),
        )

        val remote = activeRemote()
        val remoteSummary = if (remote == null) {
            "No remote is connected yet. When the user gives a host, login, and password, call remote_connect."
        } else {
            "A remote is already connected: ${remote.protocol}://${remote.username}@${remote.host}:${remote.port}, " +
                "start path ${remote.startPath}." +
                remote.webUrl.takeIf { it.isNotBlank() }?.let { " Site URL: $it." }.orEmpty() +
                " Call remote_connect again if the user gives a different server."
        }

        return AgentLoop(
            backend = DeepSeekBackend { settingsStore.current },
            toolRegistry = ToolRegistry(tools),
            systemPrompt = buildSystemPrompt(
                ws.root.absolutePath,
                deviceShell.toolchainBin.absolutePath,
                remoteSummary,
            ),
        )
    }

    private fun appendShellLine(command: String, rendered: String) {
        _state.update {
            it.copy(
                shellLines = it.shellLines + ShellLine(nextId(), command, rendered),
            )
        }
        schedulePersist()
    }

    private fun rememberFinishedCommand(
        rootPath: String,
        command: String,
        rendered: String,
        id: Long? = null,
    ) {
        val existing = library.load(rootPath)
        val lineId = id ?: (existing.nextId + 1)
        library.updateSession(
            rootPath,
            existing.copy(
                shell = (existing.shell + StoredShell(lineId, command, rendered)).takeLast(MAX_STORED_SHELL),
                nextId = maxOf(existing.nextId, lineId),
            ),
        )
    }

    private fun snapshotSession(): StoredSession {
        val current = _state.value
        return StoredSession(
            chat = current.chat.takeLast(MAX_STORED_CHAT).map { entry ->
                StoredChat(
                    id = entry.id,
                    role = entry.role.name,
                    text = entry.text,
                    toolName = entry.toolName,
                )
            },
            conversation = conversation.takeLast(MAX_STORED_CONVERSATION).toList(),
            shell = current.shellLines.filterNot { it.running }.takeLast(MAX_STORED_SHELL).map { line ->
                StoredShell(id = line.id, command = line.command, output = line.output)
            },
            nextId = idGenerator.get(),
        )
    }

    private fun schedulePersist() {
        persistJob?.cancel()
        persistJob = viewModelScope.launch {
            delay(400)
            persistNow()
        }
    }

    private fun persistNow() {
        val root = workspace?.root?.canonicalPath ?: return
        library.save(root, snapshotSession())
    }

    private fun nextId(): Long = idGenerator.incrementAndGet()

    private fun showMessage(text: String) {
        _state.update { it.copy(message = text) }
    }

    override fun onCleared() {
        persistJob?.cancel()
        persistNow()
        agentJob?.cancel()
        super.onCleared()
    }

    companion object {
        private val PASSWORD_FIELD = Regex(""""password"\s*:\s*"(?:\\.|[^"\\])*"""")
        private val URL_PASSWORD = Regex("""((?:sftp|ftps|ftp|ssh)://[^:/\s"]+):([^@"\s]+)@""")
        private const val MAX_STORED_CHAT = 400
        private const val MAX_STORED_SHELL = 200
        private const val MAX_STORED_CONVERSATION = 80

        fun factory(context: Context, settingsStore: SettingsStore): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    WorkspaceViewModel(appContext, settingsStore) as T
            }
        }
    }
}
