package com.hvkeyn.ceditneuro.ui

import android.content.Context
import android.content.pm.PackageManager
import com.hvkeyn.ceditneuro.CEditNeuroApp
import com.hvkeyn.ceditneuro.agent.AgentNotifications
import com.hvkeyn.ceditneuro.agent.AgentStatus
import com.hvkeyn.ceditneuro.update.AppUpdate
import com.hvkeyn.ceditneuro.update.AppUpdater
import com.hvkeyn.ceditneuro.update.UpdateBus
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.hvkeyn.ceditneuro.agent.AgentEvent
import com.hvkeyn.ceditneuro.agent.AgentLoop
import com.hvkeyn.ceditneuro.agent.ChatMessage
import com.hvkeyn.ceditneuro.agent.ToolTranscript
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
import com.hvkeyn.ceditneuro.shizuku.ShizukuShell
import com.hvkeyn.ceditneuro.shell.ProgramRun
import com.hvkeyn.ceditneuro.tools.BrowsePageTool
import com.hvkeyn.ceditneuro.tools.EditFileTool
import com.hvkeyn.ceditneuro.tools.HttpRequestTool
import com.hvkeyn.ceditneuro.tools.InstallModuleTool
import com.hvkeyn.ceditneuro.tools.InstallAndroidSdkTool
import com.hvkeyn.ceditneuro.tools.InstallJdkTool
import com.hvkeyn.ceditneuro.tools.InstallProgramTool
import com.hvkeyn.ceditneuro.tools.InstallRuntimeTool
import com.hvkeyn.ceditneuro.tools.ShizukuExecTool
import com.hvkeyn.ceditneuro.tools.ZipPathsTool
import com.hvkeyn.ceditneuro.tools.GitDiffTool
import com.hvkeyn.ceditneuro.tools.GitStatusTool
import com.hvkeyn.ceditneuro.tools.DeletePathTool
import com.hvkeyn.ceditneuro.tools.GlobTool
import com.hvkeyn.ceditneuro.tools.MkdirTool
import com.hvkeyn.ceditneuro.tools.MovePathTool
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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import rikka.shizuku.Shizuku
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
    val agentActivity: AgentActivity? = null,
    val message: String? = null,
    val execPrompt: String? = null,
    val hostPrompt: HostTrustPrompt? = null,
    val webVisible: Boolean = false,
    val webUrl: String = "",
    val webGeneration: Int = 0,
    val appUpdate: AppUpdate? = null,
) {
    val projectName: String?
        get() = projectRoot?.let { File(it).name.ifBlank { it } }
}

/** Live line shown while the agent is waiting, thinking, or running a tool. */
data class AgentActivity(
    val phase: String,
    val context: String,
    val focus: String,
    val startedAt: Long,
)

class WorkspaceViewModel(
    private val appContext: Context,
    private val settingsStore: SettingsStore,
    private val agentScope: CoroutineScope,
) : ViewModel() {

    private val _state = MutableStateFlow(WorkspaceUiState())
    val state: StateFlow<WorkspaceUiState> = _state.asStateFlow()

    val settings: StateFlow<AgentSettings> = settingsStore.settings

    private var workspace: Workspace? = null
    private val deviceShell = DeviceShell(appContext) { settingsStore.current.networkEnabled }
    private val shizukuShell = ShizukuShell(appContext)
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
    private val liveAnswer = StringBuilder()
    private var runContext: List<ChatMessage>? = null
    private var runOutcome: String? = null
    private var thoughtTail = ""
    private var lastThoughtUi = 0L
    private var lastNoticeAt = 0L
    private var lastNoticePhase = ""
    private var updateChecked = false
    private var updateJob: Job? = null
    private val activityJson = Json { ignoreUnknownKeys = true }
    private var persistJob: Job? = null
    private val idGenerator = AtomicLong(0)
    private val treeRefresh = AtomicLong(0)
    private val sessionEpoch = AtomicLong(0)

    init {
        viewModelScope.launch {
            UpdateBus.failures.collect { message ->
                _state.update { current ->
                    val shown = current.appUpdate ?: return@update current
                    current.copy(
                        appUpdate = shown.copy(
                            downloading = false,
                            installing = false,
                            error = message,
                        ),
                    )
                }
            }
        }
    }

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
        abandonAgent(announce = false)
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
        conversation += ToolTranscript.seal(session.conversation)
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
            abandonAgent(announce = false)
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
        _state.update { it.copy(chatVisible = !it.chatVisible, shellVisible = false) }
    }

    fun toggleShell() {
        _state.update { it.copy(shellVisible = !it.shellVisible, chatVisible = false) }
    }

    fun toggleWeb() {
        val current = _state.value
        if (current.webVisible) {
            if (browseWaiter?.isCompleted == false) {
                finishBrowse("The browser panel was hidden before the page finished.")
            }
            _state.update { it.copy(webVisible = false) }
            return
        }
        val url = current.webUrl.ifBlank { activeRemote()?.webUrl.orEmpty() }
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            showMessage("Set an http or https site URL on the selected server, or ask the agent to open a page.")
            return
        }
        val reload = current.webUrl != url
        _state.update {
            it.copy(
                webVisible = true,
                webUrl = url,
                webGeneration = if (reload) it.webGeneration + 1 else it.webGeneration,
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

    suspend fun browsePage(url: String, timeoutSec: Int = 25): String {
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
        val waitMs = timeoutSec.coerceIn(5, 90) * 1000L
        return try {
            withTimeout(waitMs) { waiter.await() }
        } catch (error: TimeoutCancellationException) {
            "The browser did not finish loading $trimmed within ${waitMs / 1000} seconds."
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
        runContext = null
        conversation += ChatMessage.user(prompt)
        val epoch = sessionEpoch.get()
        val openFile = _state.value.activePath?.substringAfterLast('/').orEmpty()
        val preparedChars = history.sumOf { it.content?.length ?: 0 } + prompt.length

        val loop = buildAgentLoop(ws)
        val finalText = StringBuilder()
        liveAnswer.clear()
        runOutcome = null
        thoughtTail = ""
        _state.update {
            it.copy(
                agentRunning = true,
                chatVisible = true,
                shellVisible = false,
                message = null,
                agentActivity = AgentActivity(
                    phase = "Preparing the request",
                    context = formatActivityContext(history.size + 1, preparedChars),
                    focus = if (openFile.isBlank()) "" else "open $openFile",
                    startedAt = System.currentTimeMillis(),
                ),
            )
        }
        publishStatus(force = true)

        agentJob = agentScope.launch {
            runCatching {
                loop.run(history, prompt).collect { event ->
                    if (sessionEpoch.get() == epoch) handleEvent(event, finalText)
                }
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (sessionEpoch.get() == epoch) {
                    val message = error.message?.lineSequence()?.firstOrNull { it.isNotBlank() }?.take(180)
                        ?: "The agent stopped."
                    appendChat(ChatRole.Error, message)
                    runOutcome = message
                }
            }

            if (sessionEpoch.get() != epoch) return@launch
            val text = finalText.toString()
            liveAnswer.clear()
            commitRunContext(text)
            val outcome = runOutcome
            val started = _state.value.agentActivity?.startedAt ?: System.currentTimeMillis()
            _state.update { it.copy(agentRunning = false, agentActivity = null) }
            if (outcome == null) {
                AgentNotifications.dismiss(appContext)
            } else {
                AgentNotifications.settle(
                    appContext,
                    AgentStatus(
                        phase = outcome,
                        detail = workLine(),
                        focus = "",
                        startedAt = started,
                        workLine = workLine(),
                    ),
                )
            }
            persistNow()
        }
    }

    /** Sends a short follow-up so a stopped task can pick up from the chat history. */
    fun continueAgent() {
        if (_state.value.agentRunning) return
        if (workspace == null) {
            showMessage("Open a project folder first.")
            return
        }
        if (conversation.isEmpty() && _state.value.chat.isEmpty()) {
            showMessage("Nothing to continue yet.")
            return
        }
        sendPrompt("Continue the previous task from where it stopped. Do not repeat steps that already finished.")
    }

    fun cancelAgent() {
        abandonAgent(announce = true)
    }

    fun checkForUpdate() {
        if (updateChecked || _state.value.appUpdate != null) return
        updateChecked = true
        viewModelScope.launch {
            val offer = withContext(Dispatchers.IO) {
                val local = AppUpdater.localVersion(appContext)
                runCatching { AppUpdater.latestNewerThan(local) }.getOrNull()
            } ?: return@launch
            _state.update { it.copy(appUpdate = offer) }
        }
    }

    /** Starts download and install after the user confirms the offered version. */
    fun confirmUpdate() {
        val offer = _state.value.appUpdate ?: return
        startUpdate(offer.copy(error = null, downloading = false, installing = false))
    }

    /** Called when the activity is back, so a permission grant can continue the install. */
    fun onAppResume() {
        val offer = _state.value.appUpdate ?: return
        if (!offer.needsInstallPermission || offer.downloading || offer.installing) return
        if (!AppUpdater.canInstallPackages(appContext)) return
        startUpdate(offer.copy(needsInstallPermission = false, error = null))
    }

    fun dismissUpdate() {
        updateJob?.cancel()
        updateJob = null
        _state.update { it.copy(appUpdate = null) }
    }

    fun retryUpdate() {
        val offer = _state.value.appUpdate ?: return
        startUpdate(offer.copy(error = null, downloading = false, installing = false))
    }

    private fun startUpdate(offer: AppUpdate) {
        if (offer.downloading || offer.installing) return
        if (!AppUpdater.canInstallPackages(appContext)) {
            _state.update {
                it.copy(
                    appUpdate = offer.copy(
                        downloading = false,
                        installing = false,
                        needsInstallPermission = true,
                        error = "Allow this app to install updates, then come back.",
                    ),
                )
            }
            AppUpdater.requestInstallPermission(appContext)
            return
        }
        _state.update {
            it.copy(
                appUpdate = offer.copy(
                    downloading = true,
                    installing = false,
                    needsInstallPermission = false,
                    error = null,
                ),
            )
        }
        updateJob?.cancel()
        updateJob = viewModelScope.launch {
            val downloaded = runCatching {
                withContext(Dispatchers.IO) {
                    AppUpdater.download(appContext, offer.apkUrl) { received, total ->
                        viewModelScope.launch {
                            _state.update { current ->
                                val shown = current.appUpdate ?: return@update current
                                current.copy(
                                    appUpdate = shown.copy(
                                        received = received,
                                        total = total,
                                        downloading = true,
                                    ),
                                )
                            }
                        }
                    }
                }
            }
            downloaded.onSuccess { apk ->
                _state.update { current ->
                    val shown = current.appUpdate ?: offer
                    current.copy(
                        appUpdate = shown.copy(downloading = false, installing = true, error = null),
                    )
                }
                val installed = withContext(Dispatchers.IO) { runCatching { AppUpdater.install(appContext, apk) } }
                if (installed.isFailure) {
                    _state.update { current ->
                        val shown = current.appUpdate ?: offer
                        current.copy(
                            appUpdate = shown.copy(
                                downloading = false,
                                installing = false,
                                error = installed.exceptionOrNull()?.message ?: "Could not start the installer.",
                            ),
                        )
                    }
                }
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                _state.update { current ->
                    val shown = current.appUpdate ?: offer
                    current.copy(
                        appUpdate = shown.copy(
                            downloading = false,
                            installing = false,
                            error = error.message ?: "Download failed.",
                        ),
                    )
                }
            }
        }
    }

    /**
     * Keeps tool calls and tool results for the next Continue. The chat bubbles
     * are already on screen; this is the transcript the model actually sees.
     */
    private fun commitRunContext(finalText: String) {
        val saved = runContext
        runContext = null
        if (saved != null) {
            conversation.clear()
            conversation.addAll(ToolTranscript.seal(saved))
            val alreadyThere = conversation.lastOrNull()?.content == finalText
            if (finalText.isNotBlank() && !alreadyThere) {
                conversation += ChatMessage.assistant(finalText)
            }
            return
        }
        if (finalText.isNotBlank()) {
            conversation += ChatMessage.assistant(finalText)
        }
    }

    private fun abandonAgent(announce: Boolean) {
        val wasRunning = agentJob?.isActive == true || _state.value.agentRunning
        val partial = liveAnswer.toString()
        liveAnswer.clear()
        commitRunContext(partial)
        agentJob?.cancel()
        agentJob = null
        thoughtTail = ""
        lastNoticePhase = ""
        val started = _state.value.agentActivity?.startedAt ?: System.currentTimeMillis()
        _state.update { it.copy(agentRunning = false, agentActivity = null) }
        if (announce && wasRunning) {
            val note = "Stopped by the user."
            appendChat(ChatRole.Error, note)
            runOutcome = note
            AgentNotifications.settle(
                appContext,
                AgentStatus(
                    phase = note,
                    detail = "Continue resumes, or swipe this away.",
                    focus = "",
                    startedAt = started,
                    workLine = workLine(),
                ),
            )
        } else {
            AgentNotifications.dismiss(appContext)
        }
    }

    private fun handleEvent(event: AgentEvent, finalText: StringBuilder) {
        when (event) {
            is AgentEvent.AssistantText -> {
                finalText.append(event.text)
                liveAnswer.append(event.text)
                appendStreaming(ChatRole.Assistant, event.text)
                if (_state.value.agentActivity?.phase != "Writing the answer") {
                    setActivity(phase = "Writing the answer", focus = "")
                }
            }

            is AgentEvent.Reasoning -> {
                appendStreaming(ChatRole.Reasoning, event.text)
                noteThought(event.text)
            }

            is AgentEvent.Activity -> {
                thoughtTail = ""
                val openFile = _state.value.activePath?.substringAfterLast('/').orEmpty()
                setActivity(
                    phase = event.phase,
                    context = formatActivityContext(event.messages, event.chars),
                    focus = if (openFile.isBlank()) "" else "open $openFile",
                )
            }

            is AgentEvent.ToolStarted -> {
                appendChat(
                    role = ChatRole.Tool,
                    text = visibleToolArguments(event.name, event.arguments),
                    toolName = "${event.name} →",
                )
                setActivity(phase = event.name, focus = toolFocus(event.name, event.arguments))
            }

            is AgentEvent.ToolFinished -> {
                appendChat(
                    role = if (event.result.isError) ChatRole.Error else ChatRole.Tool,
                    text = event.result.content,
                    toolName = "${event.name} ←",
                )
                val line = event.result.content
                    .lineSequence()
                    .firstOrNull { it.isNotBlank() }
                    .orEmpty()
                    .take(140)
                val phase = if (event.result.isError) "${event.name} failed" else "${event.name} done"
                setActivity(phase = phase, focus = line)
            }

            is AgentEvent.TurnFinished -> {
                val note = when (event.reason) {
                    "length" -> "Stopped: the model hit its output limit. Continue resumes from here."
                    "connection" -> "Stopped: the connection dropped. Continue resumes from here."
                    "max_tool_rounds" -> "Stopped: this run reached its step limit. Continue keeps going."
                    "empty" -> "Stopped: the model returned an empty reply."
                    else -> null
                }
                if (note != null) {
                    appendChat(ChatRole.Error, note)
                    runOutcome = note
                }
            }

            is AgentEvent.Context -> runContext = event.messages
            is AgentEvent.Failed -> appendChat(ChatRole.Error, event.message)
        }
    }

    private suspend fun prepareShizuku(): String? {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            return "Shizuku is not running. Open the Shizuku app, start it, and allow CEditNeuro."
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) return null
        return withContext(Dispatchers.Main) {
            val waiter = CompletableDeferred<Boolean>()
            val listener = Shizuku.OnRequestPermissionResultListener { _, result ->
                if (!waiter.isCompleted) waiter.complete(result == PackageManager.PERMISSION_GRANTED)
            }
            Shizuku.addRequestPermissionResultListener(listener)
            try {
                Shizuku.requestPermission(SHIZUKU_REQUEST)
                val granted = withTimeoutOrNull(60_000) { waiter.await() } == true
                if (granted) null else "Shizuku permission was not granted."
            } finally {
                Shizuku.removeRequestPermissionResultListener(listener)
            }
        }
    }

    private fun formatActivityContext(messages: Int, chars: Int): String {
        val model = settingsStore.current.modelLabel()
        val size = if (chars >= 1024) "${chars / 1024} KB" else "$chars B"
        val project = _state.value.projectName ?: "no project"
        return "$model · $messages messages · $size · $project"
    }

    private fun setActivity(phase: String, context: String? = null, focus: String? = null) {
        _state.update { current ->
            val previous = current.agentActivity
            val started = previous?.startedAt ?: System.currentTimeMillis()
            current.copy(
                agentActivity = AgentActivity(
                    phase = phase,
                    context = context ?: previous?.context.orEmpty(),
                    focus = focus ?: previous?.focus.orEmpty(),
                    startedAt = started,
                ),
            )
        }
        publishStatus()
    }

    private fun publishStatus(force: Boolean = false) {
        val activity = _state.value.agentActivity
        if (!_state.value.agentRunning || activity == null) return
        val now = System.currentTimeMillis()
        if (!force && activity.phase == lastNoticePhase && now - lastNoticeAt < 800) return
        lastNoticeAt = now
        lastNoticePhase = activity.phase
        AgentNotifications.publish(
            appContext,
            AgentStatus(
                phase = activity.phase,
                detail = activity.context,
                focus = activity.focus,
                startedAt = activity.startedAt,
                workLine = workLine(),
            ),
        )
    }

    private fun workLine(): String {
        val settings = settingsStore.current
        val work = when (settings.workFocus) {
            AgentSettings.WORK_BUILD -> "Build"
            AgentSettings.WORK_REMOTE -> "Remote"
            else -> "Edit"
        }
        val network = if (settings.networkEnabled) "Network on" else "Network off"
        val programs = when (settings.execAllowed) {
            true -> "Programs on"
            false -> "Programs off"
            null -> "Programs ask"
        }
        return "$work · $network · $programs"
    }

    private fun accessLine(): String {
        val settings = settingsStore.current
        val network = if (settings.networkEnabled) "network on" else "network off"
        val programs = when (settings.execAllowed) {
            true -> "programs allowed"
            false -> "programs blocked"
            null -> "programs not chosen yet"
        }
        return "$network, $programs"
    }

    private fun noteThought(delta: String) {
        thoughtTail = (thoughtTail + delta).takeLast(240)
        val now = System.currentTimeMillis()
        val alreadyThinking = _state.value.agentActivity?.phase == "Thinking"
        if (alreadyThinking && now - lastThoughtUi < 400) return
        lastThoughtUi = now
        val line = thoughtTail
            .lineSequence()
            .lastOrNull { it.isNotBlank() }
            ?.trim()
            .orEmpty()
            .ifBlank { thoughtTail.trim() }
            .take(140)
        setActivity(phase = "Thinking", focus = line)
    }

    private fun toolFocus(name: String, arguments: String): String {
        val shown = visibleToolArguments(name, arguments)
        val obj = runCatching { activityJson.parseToJsonElement(shown) as? JsonObject }.getOrNull()
        val keys = when (name) {
            "remote_connect" -> listOf("host", "protocol", "username")
            "grep", "glob" -> listOf("pattern", "path")
            "move_path" -> listOf("from", "to")
            else -> FOCUS_KEYS
        }
        val value = keys.firstNotNullOfOrNull { key ->
            (obj?.get(key) as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
        }
        return (value ?: shown).replace('\n', ' ').trim().take(140)
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
        if (!path.startsWith("/")) refreshProjectTree(path)
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
            MkdirTool(ws),
            DeletePathTool(ws),
            MovePathTool(ws),
            ZipPathsTool(ws),
            ShellTool(deviceShell, ws, this::ensureExecAllowed) { command, rendered ->
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
            InstallRuntimeTool(
                toolchain = deviceShell.toolchain,
                net = agentNet,
                networkAllowed = { settingsStore.current.networkEnabled },
                ensureExec = this::ensureExecAllowed,
            ),
            ShizukuExecTool(ws, shizukuShell, this::prepareShizuku),
            InstallAndroidSdkTool(
                toolchain = deviceShell.toolchain,
                net = agentNet,
                networkAllowed = { settingsStore.current.networkEnabled },
                ensureExec = this::ensureExecAllowed,
                zipAlignSource = appContext.assets.open("ZipAlign.java").bufferedReader().use { it.readText() },
            ),
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
                settingsStore.current.workFocus,
                accessLine(),
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
            conversation = ToolTranscript.trim(conversation.toList(), MAX_STORED_CONVERSATION),
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
        super.onCleared()
    }

    companion object {
        private val FOCUS_KEYS = listOf(
            "path", "command", "url", "host", "cwd", "query", "pattern",
            "local_path", "remote_path", "from", "to", "name", "source", "dest",
        )
        private val PASSWORD_FIELD = Regex(""""password"\s*:\s*"(?:\\.|[^"\\])*"""")
        private val URL_PASSWORD = Regex("""((?:sftp|ftps|ftp|ssh)://[^:/\s"]+):([^@"\s]+)@""")
        private const val SHIZUKU_REQUEST = 31
        private const val MAX_STORED_CHAT = 400
        private const val MAX_STORED_SHELL = 200
        private const val MAX_STORED_CONVERSATION = 80

        fun factory(context: Context, settingsStore: SettingsStore): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            val scope = (appContext as CEditNeuroApp).agentScope
            return object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    WorkspaceViewModel(appContext, settingsStore, scope) as T
            }
        }
    }
}
