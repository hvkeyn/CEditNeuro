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
import com.hvkeyn.ceditneuro.agent.AgentDoctor
import com.hvkeyn.ceditneuro.agent.AgentLoop
import com.hvkeyn.ceditneuro.agent.ChatMessage
import com.hvkeyn.ceditneuro.agent.ToolTranscript
import com.hvkeyn.ceditneuro.agent.buildSetupPrompt
import com.hvkeyn.ceditneuro.agent.buildSystemPrompt
import com.hvkeyn.ceditneuro.agent.deepseek.DeepSeekBackend
import com.hvkeyn.ceditneuro.data.AgentSettings
import com.hvkeyn.ceditneuro.data.RemoteServer
import com.hvkeyn.ceditneuro.data.DeviceProfile
import com.hvkeyn.ceditneuro.data.ProfileStore
import com.hvkeyn.ceditneuro.data.ProjectLibrary
import com.hvkeyn.ceditneuro.data.SettingsStore
import com.hvkeyn.ceditneuro.data.StoredChat
import com.hvkeyn.ceditneuro.data.StoredSession
import com.hvkeyn.ceditneuro.data.StoredShell
import com.hvkeyn.ceditneuro.net.AgentNet
import com.hvkeyn.ceditneuro.net.RemoteClient
import com.hvkeyn.ceditneuro.shell.DeviceShell
import com.hvkeyn.ceditneuro.workspace.StoragePaths
import com.hvkeyn.ceditneuro.shizuku.ShizukuCommandRunner
import com.hvkeyn.ceditneuro.shizuku.ShizukuShell
import com.hvkeyn.ceditneuro.shell.ProgramRun
import com.hvkeyn.ceditneuro.tools.BrowsePageTool
import com.hvkeyn.ceditneuro.tools.EditFileTool
import com.hvkeyn.ceditneuro.tools.HttpRequestTool
import com.hvkeyn.ceditneuro.tools.InstallApkTool
import com.hvkeyn.ceditneuro.tools.InstallModuleTool
import com.hvkeyn.ceditneuro.tools.InstallAndroidSdkTool
import com.hvkeyn.ceditneuro.tools.InstallJdkTool
import com.hvkeyn.ceditneuro.tools.InstallProgramTool
import com.hvkeyn.ceditneuro.tools.InstallRuntimeTool
import com.hvkeyn.ceditneuro.tools.LoadToolsTool
import com.hvkeyn.ceditneuro.tools.ReaderNoteTool
import com.hvkeyn.ceditneuro.tools.ReaderSketchTool
import com.hvkeyn.ceditneuro.tools.ToolGroups
import com.hvkeyn.ceditneuro.tools.ToolSession
import com.hvkeyn.ceditneuro.tools.ExecuteSystemActionTool
import com.hvkeyn.ceditneuro.tools.FetchSystemLayoutTool
import com.hvkeyn.ceditneuro.tools.ShizukuExecTool
import com.hvkeyn.ceditneuro.tools.ZipPathsTool
import com.hvkeyn.ceditneuro.tools.GitDiffTool
import com.hvkeyn.ceditneuro.tools.GitStatusTool
import com.hvkeyn.ceditneuro.tools.DeletePathTool
import com.hvkeyn.ceditneuro.tools.GlobTool
import com.hvkeyn.ceditneuro.tools.MkdirTool
import com.hvkeyn.ceditneuro.tools.MovePathTool
import com.hvkeyn.ceditneuro.tools.NetInfoTool
import com.hvkeyn.ceditneuro.tools.GrepTool
import com.hvkeyn.ceditneuro.tools.ListDirTool
import com.hvkeyn.ceditneuro.tools.ReadFileTool
import com.hvkeyn.ceditneuro.tools.RemoteConnectTool
import com.hvkeyn.ceditneuro.tools.RemoteGetTool
import com.hvkeyn.ceditneuro.tools.RemoteListTool
import com.hvkeyn.ceditneuro.tools.SpaceSyncTool
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

data class FileClipboard(
    val absolutePath: String,
    val name: String,
    val cut: Boolean,
)

data class HostTrustPrompt(val serverId: String, val host: String, val fingerprint: String)

data class ReaderToc(val title: String, val page: Int)

data class BookLine(val mine: Boolean, val text: String)

data class ReaderView(
    val path: String,
    val title: String,
    val page: Int,
    val pageCount: Int,
    val chapter: String,
    val theme: String,
    val fontSp: Int,
    val fontName: String,
    val spacing: Float,
    val bookmarked: Boolean,
    val percent: Int,
    val toc: List<ReaderToc>,
    val bookmarks: List<Int>,
    val notes: List<com.hvkeyn.ceditneuro.data.ReaderNote>,
    val ink: List<com.hvkeyn.ceditneuro.data.PageInk> = emptyList(),
    val labels: List<com.hvkeyn.ceditneuro.data.PageLabel> = emptyList(),
    val bookChat: List<BookLine> = emptyList(),
)

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
    val shareOffer: String? = null,
    val linkVisible: Boolean = false,
    val linkNote: String = "",
    val linkPeer: String = "",
    val linkSent: Int = 0,
    val linkGot: Int = 0,
    val linkDirect: Boolean = false,
    val linkClients: Int = 0,
    val linkActive: Int = 0,
    val linkSpeed: Int = 0,
    val webVisible: Boolean = false,
    /** True while the running agent is waiting on the in-app browser. */
    val agentBrowsing: Boolean = false,
    val webUrl: String = "",
    val webGeneration: Int = 0,
    val appUpdate: AppUpdate? = null,
    val fileClipboard: FileClipboard? = null,
    val otherRuns: List<ProjectRunStatus> = emptyList(),
    val reader: ReaderView? = null,
    /** When a book is open, true puts it above chat and shell. False leaves those panels in front. */
    val readerInFront: Boolean = true,
) {
    val projectName: String?
        get() = projectRoot?.let { File(it).name.ifBlank { it } }
}

/** A project whose agent is still running, or just finished, while another folder is open. */
data class ProjectRunStatus(
    val path: String,
    val name: String,
    val phase: String,
    val focus: String,
    val running: Boolean,
)

/** Live line shown while the agent is waiting, thinking, or running a tool. */
data class AgentActivity(
    val phase: String,
    val context: String,
    val focus: String,
    val startedAt: Long,
)

private class LiveProject(
    val workspace: Workspace,
    val conversation: MutableList<ChatMessage>,
    val buffers: MutableMap<String, String>,
    val idGenerator: AtomicLong,
    val epoch: AtomicLong = AtomicLong(0),
    var agentJob: Job? = null,
    val liveAnswer: StringBuilder = StringBuilder(),
    var runContext: List<ChatMessage>? = null,
    var runOutcome: String? = null,
    var thoughtTail: String = "",
    var lastThoughtUi: Long = 0L,
    var lastNoticeAt: Long = 0L,
    var lastNoticePhase: String = "",
    var unseenResult: String? = null,
    var treeGeneration: Long = 0L,
    var persistJob: Job? = null,
    var ui: WorkspaceUiState,
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
    private val projects = linkedMapOf<String, LiveProject>()
    private var current: LiveProject? = null
    private val deviceShell = DeviceShell(appContext) { settingsStore.current.networkEnabled }
    private val shizukuShell = ShizukuShell(appContext)
    private val agentNet = AgentNet()
    private val execMutex = Mutex()
    private var execWaiter: CompletableDeferred<Boolean>? = null
    private val hostMutex = Mutex()
    private var hostWaiter: CompletableDeferred<Boolean>? = null
    private var browseWaiter: CompletableDeferred<String>? = null
    private var browseGeneration = 0
    private var shizukuPrompted = false
    private var browseReportJob: Job? = null
    private val remoteClient = RemoteClient(::ensureHostTrusted)
    private val beacon = com.hvkeyn.ceditneuro.net.BeaconClient()
    private val peerLines = ArrayDeque<String>()
    private var beaconRole: String = ""
    private val seenPeers = mutableSetOf<String>()
    @Volatile
    private var parallelPeer: String = ""
    private var speedBytes = 0
    private var speedMark = System.currentTimeMillis()
    private val library = ProjectLibrary(appContext)
    private val readerStore = com.hvkeyn.ceditneuro.data.ReaderStore(appContext)
    private val bookChats = HashMap<String, List<BookLine>>()
    private var pendingMarkup: com.hvkeyn.ceditneuro.data.ReaderRecord? = null
    private var markupJob: kotlinx.coroutines.Job? = null
    private var openPages: List<com.hvkeyn.ceditneuro.reader.BookPage> = emptyList()
    private var readerImages: Map<String, ByteArray> = emptyMap()
    private var openBookKey: String? = null
    private var sourceText: String = ""
    private val profiles = ProfileStore(appContext)
    private var profileJob: Job? = null
    private var activeProfileName = "default"
    private var allowEmptyProfile = false

    private var updateChecked = false
    private var updateJob: Job? = null
    private val activityJson = Json { ignoreUnknownKeys = true }

    init {
        settingsStore.afterChange = { scheduleProfile() }
        library.afterChange = { scheduleProfile() }
        viewModelScope.launch {
            UpdateBus.failures.collect { message ->
                val replace = message.contains("UPDATE_INCOMPATIBLE", ignoreCase = true) ||
                    message.contains("VERSION_DOWNGRADE", ignoreCase = true) ||
                    message.contains("signatures do not match", ignoreCase = true)
                val permission = !replace && (
                    message.contains("USER_RESTRICTED", ignoreCase = true) ||
                    message.contains("not allowed", ignoreCase = true) ||
                    !AppUpdater.canInstallPackages(appContext)
                    )
                val exported = if (replace) {
                    withContext(Dispatchers.IO) { AppUpdater.exportUpdateApk(appContext)?.absolutePath }
                } else {
                    null
                }
                val text = when {
                    replace && exported != null ->
                        "This phone already has CEditNeuro signed with a different key, so Android will not replace it. " +
                            "The new APK is in Downloads:\n$exported\n" +
                            "Uninstall this app, then open that file. The phone profile is kept and the new install loads it."
                    replace ->
                        "This phone already has CEditNeuro signed with a different key, so Android will not replace it. " +
                            "Uninstall this app, then install the new APK. The phone profile is kept and the new install loads it."
                    permission ->
                        "Android is not letting this app install updates. Allow installs for CEditNeuro, then come back."
                    else -> message
                }
                _state.update { current ->
                    val shown = current.appUpdate ?: AppUpdate(versionName = "", notes = "", apkUrl = "")
                    current.copy(
                        appUpdate = shown.copy(
                            downloading = false,
                            installing = false,
                            error = text,
                            needsInstallPermission = permission,
                            replaceInstalled = replace,
                            exportedApk = exported,
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
        adoptDeviceProfile()
        val live = library.roots().filter { path -> File(path).isDirectory }
        library.replaceRoots(live)
        _state.update { it.copy(recentProjects = live) }
        live.firstOrNull()?.let { openProject(File(it)) }
    }

    fun profileNames(): List<String> = profiles.names().ifEmpty { listOf(activeProfileName) }

    fun activeProfileName(): String = activeProfileName

    fun saveProfileAs(name: String) {
        val safe = profiles.safeName(name)
        viewModelScope.launch(Dispatchers.IO) {
            flushProfile()
            activeProfileName = safe
            allowEmptyProfile = library.roots().isEmpty()
            flushProfile()
            showMessage("Profile $safe saved.")
        }
    }

    fun useProfile(name: String) {
        val safe = profiles.safeName(name)
        if (safe == activeProfileName) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { flushProfile() }
            val profile = withContext(Dispatchers.IO) { profiles.read(safe) }
            if (profile == null) {
                showMessage("Profile $safe is missing.")
                return@launch
            }
            applyProfile(profile)
        }
    }

    private fun adoptDeviceProfile() {
        activeProfileName = profiles.activeName()
        if (library.roots().isNotEmpty()) return
        val settings = settingsStore.current
        val configured = settings.providers.any { it.apiKey.isNotBlank() } || settings.remotes.isNotEmpty()
        if (configured) return
        val profile = profiles.readActive() ?: return
        val useful = profile.settings.providers.any { it.apiKey.isNotBlank() } ||
            profile.settings.remotes.isNotEmpty() ||
            profile.roots.isNotEmpty()
        if (!useful) return
        activeProfileName = profile.name.ifBlank { profiles.safeName(profile.name) }
        settingsStore.replace(profile.settings.normalized())
        val live = profile.roots.filter { path -> File(path).isDirectory }
        if (live.isNotEmpty()) library.replaceAll(live, profile.sessions)
        _state.update { it.copy(message = "Profile $activeProfileName loaded.") }
    }

    private fun applyProfile(profile: DeviceProfile) {
        projects.values.toList().forEach { project ->
            project.persistJob?.cancel()
            if (project.agentJob?.isActive == true) abandonAgent(project, announce = false)
        }
        projects.clear()
        current = null
        workspace = null
        activeProfileName = profile.name.ifBlank { profiles.safeName(profile.name) }
        profiles.markActive(activeProfileName)
        settingsStore.replace(profile.settings.normalized())
        val live = profile.roots.filter { path -> File(path).isDirectory }
        allowEmptyProfile = live.isEmpty()
        library.replaceAll(live, profile.sessions)
        _state.value = WorkspaceUiState(
            recentProjects = live,
            message = "Profile $activeProfileName loaded.",
        )
        live.firstOrNull()?.let { openProject(File(it)) }
    }

    private fun scheduleProfile() {
        profileJob?.cancel()
        profileJob = viewModelScope.launch {
            delay(400)
            withContext(Dispatchers.IO) { flushProfile() }
        }
    }

    private fun flushProfile() {
        val (roots, sessions) = library.snapshot()
        val existing = profiles.read(activeProfileName)
        val keepPrevious = roots.isEmpty() && !allowEmptyProfile &&
            existing != null && existing.roots.isNotEmpty()
        profiles.write(
            name = activeProfileName,
            settings = settingsStore.current,
            roots = if (keepPrevious) existing.roots else roots,
            sessions = if (keepPrevious) existing.sessions else sessions,
        )
    }

    fun openProject(root: File) {
        val canonical = runCatching { root.canonicalFile }.getOrElse { error ->
            showMessage("Cannot open project: ${error.message}")
            return
        }
        if (!canonical.isDirectory) {
            if (library.roots().size <= 1) allowEmptyProfile = true
            library.forget(canonical.path)
            projects.remove(canonical.path)
            _state.update { it.copy(recentProjects = library.roots(), otherRuns = runStatuses(current)) }
            showMessage("Folder is gone: ${canonical.path}")
            return
        }
        if (current?.workspace?.root?.canonicalPath == canonical.path) {
            current?.let { project ->
                project.ui = projectSlice(_state.value)
                library.save(canonical.path, snapshotSession(project))
            }
            _state.update { it.copy(projectRoot = canonical.path, recentProjects = library.roots()) }
            refreshProjectTree()
            return
        }

        current?.let { leaving ->
            leaving.ui = projectSlice(_state.value)
            persistNow(leaving)
        }

        val project = projects[canonical.path] ?: run {
            val created = runCatching { loadProject(canonical) }.getOrElse { error ->
                showMessage("Cannot open project: ${error.message}")
                return
            }
            projects[canonical.path] = created
            created
        }
        showProject(project)
    }

    fun forgetProject(path: String) {
        val canonical = runCatching { File(path).canonicalPath }.getOrDefault(path)
        val project = projects.remove(canonical)
        val leavingCurrent = current === project || workspace?.root?.canonicalPath == canonical
        if (project != null) {
            project.persistJob?.cancel()
            if (project.agentJob?.isActive == true) abandonAgent(project, announce = false)
        }
        if (leavingCurrent) {
            current = null
            workspace = null
        }
        if (library.roots().size <= 1) allowEmptyProfile = true
        library.forget(canonical)
        val remaining = library.roots()
        if (!leavingCurrent) {
            _state.update { it.copy(recentProjects = remaining, otherRuns = runStatuses(current)) }
            return
        }
        val previous = _state.value
        _state.value = WorkspaceUiState(
            recentProjects = remaining,
            chatVisible = previous.chatVisible,
            shellVisible = previous.shellVisible,
            webVisible = previous.webVisible,
            webUrl = previous.webUrl,
            webGeneration = previous.webGeneration,
            agentBrowsing = previous.agentBrowsing,
            appUpdate = previous.appUpdate,
            otherRuns = runStatuses(null),
        )
        remaining.firstOrNull()?.let { openProject(File(it)) }
    }

    private fun loadProject(canonical: File): LiveProject {
        val opened = Workspace(canonical)
        val session = library.load(canonical.path)
        library.save(canonical.path, session)
        return LiveProject(
            workspace = opened,
            conversation = ToolTranscript.seal(session.conversation).toMutableList(),
            buffers = mutableMapOf(),
            idGenerator = AtomicLong(session.nextId),
            ui = WorkspaceUiState(
                projectRoot = canonical.path,
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
            ),
        )
    }

    private fun showProject(project: LiveProject) {
        current = project
        workspace = project.workspace
        project.unseenResult = null
        val previous = _state.value
        _state.value = project.ui.copy(
            projectRoot = project.workspace.root.canonicalPath,
            recentProjects = library.roots(),
            chatVisible = previous.chatVisible,
            shellVisible = previous.shellVisible,
            webVisible = previous.webVisible,
            webUrl = previous.webUrl,
            webGeneration = previous.webGeneration,
            agentBrowsing = previous.agentBrowsing,
            appUpdate = previous.appUpdate,
            execPrompt = previous.execPrompt,
            hostPrompt = previous.hostPrompt,
            otherRuns = runStatuses(project),
        )
        project.ui = projectSlice(_state.value)
        openPages = emptyList()
        readerImages = emptyMap()
        openBookKey = null
        sourceText = ""
        val restore = _state.value.reader?.path
        if (restore != null) {
            viewModelScope.launch {
                val opened = withContext(Dispatchers.IO) {
                    runCatching { loadReader(restore, keepPlace = true) }
                }
                opened.onFailure { showMessage(it.message ?: "Cannot open this book.") }
            }
        }
    }

    private fun projectSlice(state: WorkspaceUiState): WorkspaceUiState =
        state.copy(
            recentProjects = emptyList(),
            message = null,
            execPrompt = null,
            hostPrompt = null,
            appUpdate = null,
            otherRuns = emptyList(),
        )

    private fun runStatuses(except: LiveProject?): List<ProjectRunStatus> =
        projects.values.mapNotNull { project ->
            if (project === except) return@mapNotNull null
            val running = project.agentJob?.isActive == true
            val result = project.unseenResult
            if (!running && result.isNullOrBlank()) return@mapNotNull null
            val activity = project.ui.agentActivity
            ProjectRunStatus(
                path = project.workspace.root.canonicalPath,
                name = project.workspace.root.name.ifBlank { project.workspace.root.path },
                phase = if (running) activity?.phase ?: "Working" else result.orEmpty(),
                focus = if (running) activity?.focus.orEmpty() else "",
                running = running,
            )
        }

    private fun editProject(project: LiveProject, transform: (WorkspaceUiState) -> WorkspaceUiState) {
        if (current === project) {
            _state.update { global ->
                val next = transform(global)
                project.ui = projectSlice(next)
                next.copy(otherRuns = runStatuses(project))
            }
        } else {
            project.ui = projectSlice(transform(project.ui))
            _state.update { it.copy(otherRuns = runStatuses(current)) }
        }
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
        val name = path.substringAfterLast('/')
        if (com.hvkeyn.ceditneuro.reader.BookText.prefersReader(name)) {
            openReader(path)
            return
        }
        if (name.endsWith(".zip", ignoreCase = true)) {
            viewModelScope.launch {
                val books = withContext(Dispatchers.IO) {
                    runCatching { com.hvkeyn.ceditneuro.reader.BookText.looksLikeBooks(ws.resolve(path)) }
                        .getOrDefault(false)
                }
                if (books) openReader(path) else openFileAsText(path)
            }
            return
        }
        openFileAsText(path)
    }

    private fun openFileAsText(path: String) {
        val ws = workspace ?: return
        if (_state.value.openFiles.any { it.path == path }) {
            _state.update { it.copy(activePath = path) }
            return
        }
        val content = runCatching { ws.read(path) }.getOrElse { error ->
            showMessage("Cannot read $path: ${error.message}")
            return
        }
        current?.buffers?.set(path, content)
        _state.update {
            it.copy(openFiles = it.openFiles + OpenFile(path, content), activePath = path)
        }
    }

    fun openReader(path: String) {
        if (workspace == null) {
            showMessage("Open a project folder first.")
            return
        }
        if (path.isBlank()) {
            showMessage("Open a txt, Markdown, HTML, FB2, or EPUB file, then tap Read.")
            return
        }
        _state.update { it.copy(activePath = path) }
        viewModelScope.launch {
            val opened = withContext(Dispatchers.IO) { runCatching { loadReader(path, keepPlace = false) } }
            opened.onFailure { showMessage(it.message ?: "Cannot open this book.") }
        }
    }

    fun closeReader() {
        _state.update { it.copy(reader = null, readerInFront = true) }
    }

    /** Brings an already open book back over chat or shell without reading the file again. */
    fun showReader() {
        if (_state.value.reader == null) return
        _state.update { it.copy(readerInFront = true) }
    }

    fun readerPageText(): String = sourceText

    fun readerImages(): Map<String, ByteArray> = readerImages

    fun readerTurn(delta: Int) {
        val reader = _state.value.reader ?: return
        readerGoTo((reader.page + delta).coerceIn(0, (reader.pageCount - 1).coerceAtLeast(0)))
    }

    fun readerGoTo(page: Int) {
        val reader = _state.value.reader ?: return
        val next = page.coerceIn(0, (openPages.size - 1).coerceAtLeast(0))
        publishReader(reader.path, next, reader.theme, reader.fontSp)
    }

    fun readerFont(delta: Int) {
        val reader = _state.value.reader ?: return
        val size = (reader.fontSp + delta).coerceIn(15, 28)
        if (size == reader.fontSp) return
        readerStyle(size, reader.fontName, reader.spacing, reader.theme)
    }

    fun readerStyle(fontSp: Int, fontName: String, spacing: Float, theme: String) {
        val reader = _state.value.reader ?: return
        val record = readerStore.read(reader.path)
        readerStore.write(
            record.copy(
                fontSp = fontSp.coerceIn(15, 28),
                fontName = fontName,
                spacing = spacing,
                theme = theme,
            ),
        )
        publishReader(reader.path, reader.page, theme, fontSp, reader.title, fontName, spacing)
    }

    fun readerProgress(page: Int, pageCount: Int, chapter: String) {
        val reader = _state.value.reader ?: return
        if (page == reader.page && pageCount == reader.pageCount && chapter == reader.chapter) return
        val record = readerStore.read(reader.path)
        val count = pageCount.coerceAtLeast(1)
        readerStore.write(record.copy(page = page, fraction = page.toFloat() / count))
        _state.update {
            it.copy(
                reader = reader.copy(
                    page = page,
                    pageCount = count,
                    chapter = chapter,
                    percent = ((page + 1) * 100 / count),
                    bookmarked = page in record.bookmarks,
                ),
            )
        }
    }

    fun readerCycleTheme() {
        val reader = _state.value.reader ?: return
        val theme = when (reader.theme) {
            "day" -> "sepia"
            "sepia" -> "night"
            else -> "day"
        }
        publishReader(reader.path, reader.page, theme, reader.fontSp)
    }

    fun readerToggleBookmark() {
        val reader = _state.value.reader ?: return
        val record = readerStore.read(reader.path)
        val marks = if (reader.page in record.bookmarks) record.bookmarks - reader.page else record.bookmarks + reader.page
        readerStore.write(record.copy(bookmarks = marks.sorted()))
        publishReader(reader.path, reader.page, reader.theme, reader.fontSp)
    }

    fun readerAddNote(text: String) {
        val reader = _state.value.reader ?: return
        if (text.isBlank()) return
        val record = readerStore.read(reader.path)
        val note = com.hvkeyn.ceditneuro.data.ReaderNote(reader.page, text.trim(), System.currentTimeMillis())
        readerStore.write(record.copy(notes = record.notes + note))
        publishReader(reader.path, reader.page, reader.theme, reader.fontSp)
    }

    fun readerDeleteNote(note: com.hvkeyn.ceditneuro.data.ReaderNote) {
        val reader = _state.value.reader ?: return
        val record = readerStore.read(reader.path)
        readerStore.write(record.copy(notes = record.notes.filterNot { it == note }))
        publishReader(reader.path, reader.page, reader.theme, reader.fontSp)
    }

    fun readerAddInk(page: Int, color: Long, width: Float, points: List<com.hvkeyn.ceditneuro.data.InkPoint>) {
        val reader = _state.value.reader ?: return
        if (points.size < 2) return
        val stroke = com.hvkeyn.ceditneuro.data.PageInk(
            id = java.util.UUID.randomUUID().toString(),
            page = page,
            color = color,
            width = width,
            points = points,
        )
        patchMarkup(reader) { it.copy(ink = it.ink + stroke) }
    }

    fun readerAddLabel(page: Int, text: String, x: Float, y: Float, color: Long) {
        val reader = _state.value.reader ?: return
        if (text.isBlank()) return
        val record = readerStore.read(reader.path)
        val label = com.hvkeyn.ceditneuro.data.PageLabel(
            id = java.util.UUID.randomUUID().toString(),
            page = page,
            text = text.trim(),
            x = x.coerceIn(0.05f, 0.9f),
            y = y.coerceIn(0.05f, 0.9f),
            color = color,
        )
        patchMarkup(reader) { it.copy(labels = it.labels + label) }
    }

    fun readerMoveLabel(id: String, x: Float, y: Float, scale: Float, rotation: Float) {
        val reader = _state.value.reader ?: return
        patchMarkup(reader) { record ->
            record.copy(
                labels = record.labels.map { label ->
                    if (label.id != id) label else label.copy(
                        x = x.coerceIn(0f, 1f),
                        y = y.coerceIn(0f, 1f),
                        scale = scale.coerceIn(0.4f, 4f),
                        rotation = rotation,
                    )
                },
            )
        }
    }

    fun readerClearPage(page: Int) {
        val reader = _state.value.reader ?: return
        patchMarkup(reader) { record ->
            record.copy(
                ink = record.ink.filterNot { it.page == page },
                labels = record.labels.filterNot { it.page == page },
            )
        }
    }

    fun readerRestyle(id: String, color: Long? = null, width: Float? = null, scale: Float? = null, rotation: Float? = null) {
        val reader = _state.value.reader ?: return
        patchMarkup(reader) { record ->
            record.copy(
                ink = record.ink.map { stroke ->
                    if (stroke.id != id) stroke else stroke.copy(
                        color = color ?: stroke.color,
                        width = width ?: stroke.width,
                    )
                },
                labels = record.labels.map { label ->
                    if (label.id != id) label else label.copy(
                        color = color ?: label.color,
                        scale = scale ?: label.scale,
                        rotation = rotation ?: label.rotation,
                    )
                },
            )
        }
    }

    fun readerDeleteMarkup(id: String) {
        val reader = _state.value.reader ?: return
        patchMarkup(reader) { record ->
            record.copy(
                ink = record.ink.filterNot { it.id == id },
                labels = record.labels.filterNot { it.id == id },
            )
        }
    }

    private fun patchMarkup(
        reader: ReaderView,
        transform: (com.hvkeyn.ceditneuro.data.ReaderRecord) -> com.hvkeyn.ceditneuro.data.ReaderRecord,
    ) {
        val base = pendingMarkup?.takeIf { it.path == reader.path } ?: readerStore.read(reader.path)
        val next = transform(base)
        pendingMarkup = next
        _state.update { state ->
            val open = state.reader ?: return@update state
            if (open.path != reader.path) state else state.copy(reader = open.copy(ink = next.ink, labels = next.labels))
        }
        markupJob?.cancel()
        markupJob = viewModelScope.launch {
            kotlinx.coroutines.delay(350)
            val save = pendingMarkup ?: return@launch
            pendingMarkup = null
            readerStore.write(save)
        }
    }

    fun readerExplain(page: Int, passage: String) {
        readerAsk(page, passage, "Explain this page.")
    }

    fun readerAsk(page: Int, passage: String, question: String) {
        val reader = _state.value.reader ?: return
        val asked = question.trim().ifBlank { return }
        val book = bookPlain().ifBlank { passage.trim() }.ifBlank { "(This book has no text.)" }
        val prior = bookChats[reader.path].orEmpty()
        val shown = prior + BookLine(true, asked) + BookLine(false, "…")
        bookChats[reader.path] = shown
        _state.update { state ->
            val open = state.reader ?: return@update state
            state.copy(reader = open.copy(bookChat = shown), chatVisible = false, readerInFront = true)
        }
        viewModelScope.launch {
            val reply = StringBuilder()
            val messages = buildList {
                add(
                    com.hvkeyn.ceditneuro.agent.ChatMessage.system(
                        "You answer only from this one book. Use the book text below and the question. " +
                            "The reader is on the page named in the question. " +
                            "Do not browse files, do not list a folder, and do not use tools.",
                    ),
                )
                prior.takeLast(8).forEach { line ->
                    add(
                        if (line.mine) com.hvkeyn.ceditneuro.agent.ChatMessage.user(line.text)
                        else com.hvkeyn.ceditneuro.agent.ChatMessage.assistant(line.text)
                    )
                }
                add(
                    com.hvkeyn.ceditneuro.agent.ChatMessage.user(
                        "Open page ${page + 1}.\n\nBook:\n$book\n\nQuestion: $asked",
                    ),
                )
            }
            runCatching {
                com.hvkeyn.ceditneuro.agent.deepseek.DeepSeekBackend { settingsStore.current }
                    .complete(messages, emptyList())
                    .collect { chunk ->
                        if (chunk is com.hvkeyn.ceditneuro.agent.BackendChunk.Text) {
                            reply.append(chunk.value)
                            replaceBookReply(reader.path, reply.toString())
                        }
                    }
            }.onFailure { error ->
                replaceBookReply(reader.path, error.message ?: "The book chat stopped.")
            }
            if (reply.isEmpty()) replaceBookReply(reader.path, "No answer.")
        }
    }

    private fun bookPlain(): String {
        val plain = sourceText
            .replace('\u0000', '\n')
            .replace(Regex("\u0001[^\u0001]*\u0001"), " ")
            .trim()
        val cap = 48_000
        return if (plain.length <= cap) plain else plain.take(cap) + "\n\n[The rest of the file was cut to fit.]"
    }

    private fun replaceBookReply(path: String, text: String) {
        val lines = bookChats[path].orEmpty()
        if (lines.isEmpty()) return
        val next = lines.dropLast(1) + BookLine(false, text)
        bookChats[path] = next
        _state.update { state ->
            val open = state.reader ?: return@update state
            if (open.path != path) state else state.copy(reader = open.copy(bookChat = next))
        }
    }

    private fun loadReader(path: String, keepPlace: Boolean) {
        val ws = workspace ?: return
        val file = ws.resolve(path)
        val name = file.name
        if (!com.hvkeyn.ceditneuro.reader.BookText.supports(name)) {
            error("Read supports txt, Markdown, HTML, FB2, EPUB, and a zip of those files.")
        }
        val book = com.hvkeyn.ceditneuro.reader.BookText.parse(name, file.readBytes())
        val saved = readerStore.read(file.absolutePath)
        val font = saved.fontSp.coerceIn(15, 28)
        sourceText = book.chapters.joinToString("\n") { "\u0000${it.title}\n${it.text}" }
        readerImages = book.images
        openPages = com.hvkeyn.ceditneuro.reader.BookText.pages(book, charsFor(font))
        openBookKey = file.absolutePath
        val page = if (keepPlace) {
            _state.value.reader?.page ?: saved.page
        } else {
            saved.page
        }.coerceIn(0, openPages.lastIndex.coerceAtLeast(0))
        publishReader(file.absolutePath, page, saved.theme, font, book.title, saved.fontName, saved.spacing)
    }

    private fun reflow(path: String, font: Int, theme: String, fraction: Float) {
        val ws = workspace ?: return
        val file = ws.resolve(path)
        val book = com.hvkeyn.ceditneuro.reader.BookText.parse(file.name, file.readBytes())
        openPages = com.hvkeyn.ceditneuro.reader.BookText.pages(book, charsFor(font))
        openBookKey = file.absolutePath
        val page = (fraction * openPages.size).toInt().coerceIn(0, openPages.lastIndex.coerceAtLeast(0))
        publishReader(file.absolutePath, page, theme, font)
    }

    private fun publishReader(
        path: String,
        page: Int,
        theme: String,
        font: Int,
        title: String? = null,
        fontName: String? = null,
        spacing: Float? = null,
    ) {
        pendingMarkup?.let { pending ->
            if (pending.path == path) {
                readerStore.write(pending)
                pendingMarkup = null
            }
        }
        val record = readerStore.read(path)
        val shown = openPages.getOrNull(page)
        val count = openPages.size.coerceAtLeast(1)
        val toc = ArrayList<ReaderToc>()
        openPages.forEachIndexed { index, item ->
            if (toc.none { it.title == item.chapter }) toc.add(ReaderToc(item.chapter, index))
        }
        val view = ReaderView(
            path = path,
            title = title ?: _state.value.reader?.title ?: File(path).name,
            page = page,
            pageCount = count,
            chapter = shown?.chapter.orEmpty(),
            theme = theme,
            fontSp = font,
            fontName = fontName ?: record.fontName,
            spacing = spacing ?: record.spacing,
            bookmarked = page in record.bookmarks,
            percent = ((page + 1) * 100 / count),
            toc = toc,
            bookmarks = record.bookmarks.filter { it in openPages.indices },
            notes = record.notes,
            ink = record.ink,
            labels = record.labels,
            bookChat = bookChats[path].orEmpty(),
        )
        readerStore.write(
            record.copy(
                path = path,
                page = page,
                fraction = page.toFloat() / count,
                theme = theme,
                fontSp = font,
                fontName = fontName ?: record.fontName,
                spacing = spacing ?: record.spacing,
            ),
        )
        _state.update { it.copy(reader = view, readerInFront = true) }
    }

    private fun charsFor(fontSp: Int): Int = (1800 * 20 / fontSp).coerceIn(500, 3_200)

    fun closeFile(path: String) {
        current?.buffers?.remove(path)
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
        current?.buffers?.get(path)
            ?: _state.value.openFiles.firstOrNull { it.path == path }?.onDisk
            ?: ""

    fun onEditorTextChanged(path: String, text: String) {
        val buffers = current?.buffers ?: return
        if (buffers[path] == text) return
        buffers[path] = text
        if (path !in _state.value.dirtyPaths) {
            _state.update { it.copy(dirtyPaths = it.dirtyPaths + path) }
        }
    }

    fun saveActiveFile() {
        _state.value.activePath?.let(::saveFile)
    }

    /** Saves every open file that has unsaved edits. Returns false if one of them failed. */
    fun saveDirtyFiles(): Boolean {
        val paths = _state.value.dirtyPaths.toList()
        if (paths.isEmpty()) return false
        return paths.all(::saveFile)
    }

    private fun saveFile(path: String): Boolean {
        val ws = workspace ?: return false
        val text = current?.buffers?.get(path) ?: return false
        return runCatching { ws.write(path, text) }
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
            .isSuccess
    }

    fun toggleChat() {
        _state.update {
            when {
                it.chatVisible && it.reader != null && it.readerInFront ->
                    it.copy(readerInFront = false, shellVisible = false)
                it.chatVisible ->
                    it.copy(chatVisible = false, shellVisible = false, readerInFront = it.reader != null)
                else ->
                    it.copy(chatVisible = true, shellVisible = false, readerInFront = false)
            }
        }
    }

    fun toggleShell() {
        _state.update {
            when {
                it.shellVisible && it.reader != null && it.readerInFront ->
                    it.copy(readerInFront = false, chatVisible = false)
                it.shellVisible ->
                    it.copy(shellVisible = false, chatVisible = false, readerInFront = it.reader != null)
                else ->
                    it.copy(shellVisible = true, chatVisible = false, readerInFront = false)
            }
        }
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
            _state.update { it.copy(webVisible = true, webUrl = "", shellVisible = false) }
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
        val generation = ++browseGeneration
        finishBrowse("Superseded by a newer page.")
        val waiter = CompletableDeferred<String>()
        browseWaiter = waiter
        _state.update {
            it.copy(
                webVisible = true,
                agentBrowsing = true,
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
        } finally {
            if (browseGeneration == generation) {
                _state.update { it.copy(agentBrowsing = false) }
            }
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
        val project = current ?: run {
            showMessage("Open a project folder first.")
            return
        }
        val trimmed = command.trim()
        if (trimmed.isEmpty() || project.ui.shellRunning) return
        val id = nextId(project)
        editProject(project) {
            it.copy(
                shellVisible = true,
                shellRunning = true,
                shellLines = it.shellLines + ShellLine(id, trimmed, "", running = true),
            )
        }
        schedulePersist(project)
        viewModelScope.launch {
            val rendered = runCatching {
                if (ProgramRun.needsConsent(trimmed) && !ensureExecAllowed(
                        "The command runs a program outside the system shell.",
                    )
                ) {
                    "exit=1\nRunning installed programs is not allowed. Turn it on in Settings to run compilers."
                } else {
                    withContext(Dispatchers.IO) {
                        deviceShell.run(trimmed, project.workspace.root, timeoutSeconds = 120).render()
                    }
                }
            }.getOrElse { error -> "exit=1\n${error.message}" }
            editProject(project) { shown ->
                shown.copy(
                    shellRunning = false,
                    shellLines = shown.shellLines.map { line ->
                        if (line.id == id) line.copy(output = rendered, running = false) else line
                    },
                )
            }
            refreshProjectTree(project)
            persistNow(project)
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

    fun createShare() {
        val project = current ?: run {
            showMessage("Open a project folder first.")
            return
        }
        if (_state.value.shareOffer != null) {
            _state.update { it.copy(linkVisible = true) }
            return
        }
        seenPeers.clear()
        val code = (100000..999999).random().toString()
        _state.update {
            it.copy(
                shareOffer = code,
                linkVisible = true,
                linkNote = "This code stays yours. The other phone enters it and presses Join.",
                linkPeer = "",
                linkSent = 0,
                linkGot = 0,
                linkDirect = false,
            )
        }
        openBeacon(code, lead = true)
    }

    fun joinShare(code: String) {
        val trimmed = code.filter { it.isDigit() }
        val mine = _state.value.shareOffer
        if (trimmed.length != 6) {
            _state.update { it.copy(linkNote = "Enter the 6-digit code from the other phone.") }
            return
        }
        if (trimmed == mine) {
            _state.update { it.copy(linkNote = "That is your code. Send it to the other phone. They press Join.") }
            return
        }
        seenPeers.clear()
        _state.update {
            it.copy(
                linkVisible = true,
                linkNote = "Joining $trimmed…",
                linkPeer = "",
                linkActive = 0,
                linkClients = 0,
                linkDirect = false,
            )
        }
        openBeacon(trimmed, lead = false)
        viewModelScope.launch {
            kotlinx.coroutines.delay(12_000)
            if (_state.value.linkNote == "Joining $trimmed…") {
                val mine = _state.value.shareOffer
                if (!mine.isNullOrBlank()) openBeacon(mine, lead = true)
                _state.update {
                    it.copy(
                        linkNote = "No answer on $trimmed. Back on your code. The other phone enters it and presses Join.",
                        linkActive = 0,
                        linkClients = 0,
                        linkPeer = "",
                    )
                }
            }
        }
    }

    fun disconnectLink() {
        com.hvkeyn.ceditneuro.net.LinkService.stop(appContext)
        beacon.leave()
        beaconRole = ""
        seenPeers.clear()
        current?.let { project ->
            appendChat(project, ChatRole.Error, "Link closed. The lead and the other phones are told this phone left.")
        }
        _state.update {
            it.copy(
                linkNote = "Disconnected.",
                linkActive = 0,
                linkClients = 0,
                linkPeer = "",
                linkDirect = false,
            )
        }
    }

    fun dismissShare() {
        _state.update { it.copy(linkVisible = false) }
    }

    fun showCopied() {
        showMessage("Code copied.")
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

    fun sendPrompt(prompt: String, attachments: List<android.net.Uri> = emptyList()) {
        val project = current ?: run {
            showMessage("Open a project folder first.")
            return
        }
        val prepared = com.hvkeyn.ceditneuro.agent.Attachments.prepare(
            appContext, project.workspace.root, prompt, attachments,
        )
        val images = if (settingsStore.current.model.seesImages()) prepared.imageDataUrls else emptyList()
        startPrompt(project, prepared.prompt, images, fromPeer = false)
    }

    private fun startPrompt(
        project: LiveProject,
        prompt: String,
        images: List<String> = emptyList(),
        fromPeer: Boolean = false,
    ) {
        if (prompt.isBlank() || project.agentJob?.isActive == true) return

        appendChat(project, ChatRole.User, prompt)
        val history = project.conversation.toList()
        project.runContext = null
        project.conversation += ChatMessage.user(prompt, images)
        val epoch = project.epoch.get()
        val openFile = _state.value.activePath?.substringAfterLast('/').orEmpty()
        val preparedChars = history.sumOf { it.content?.length ?: 0 } + prompt.length

        val loop = buildAgentLoop(project)
        val finalText = StringBuilder()
        project.liveAnswer.clear()
        project.runOutcome = null
        project.thoughtTail = ""
        editProject(project) {
            it.copy(
                agentRunning = true,
                chatVisible = true,
                shellVisible = false,
                readerInFront = false,
                message = null,
                agentActivity = AgentActivity(
                    phase = "Preparing the request",
                    context = formatActivityContext(project, history.size + 1, preparedChars),
                    focus = if (openFile.isBlank()) "" else "open $openFile",
                    startedAt = System.currentTimeMillis(),
                ),
            )
        }
        publishStatus(project, force = true)
        if (!fromPeer && beaconRole == "lead") {
            beacon.send("TASK " + prompt.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty().take(180))
        }

        project.agentJob = agentScope.launch {
            runCatching {
                loop.run(history, prompt).collect { event ->
                    if (project.epoch.get() == epoch) handleEvent(project, event, finalText)
                }
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                if (project.epoch.get() == epoch) {
                    val message = error.message?.lineSequence()?.firstOrNull { it.isNotBlank() }?.take(180)
                        ?: "The agent stopped."
                    appendChat(project, ChatRole.Error, message)
                    project.runOutcome = message
                }
            }

            if (project.epoch.get() != epoch) return@launch
            val text = finalText.toString()
            project.liveAnswer.clear()
            commitRunContext(project, text)
            val outcome = project.runOutcome
            val activity = project.ui.agentActivity
            val started = activity?.startedAt ?: System.currentTimeMillis()
            val summary = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()?.take(160)
            if (current !== project) {
                project.unseenResult = outcome ?: summary?.take(80) ?: "+ Done"
            }
            editProject(project) { it.copy(agentRunning = false, agentActivity = null) }
            val status = if (outcome == null) {
                agentStatus(
                    project,
                    phase = "+ Done",
                    detail = summary?.ifBlank { null } ?: "Finished",
                    focus = activity?.focus.orEmpty(),
                    startedAt = started,
                    done = true,
                )
            } else {
                agentStatus(project, outcome, workLine(), activity?.focus.orEmpty(), started)
            }
            AgentNotifications.settle(appContext, status)
            val report = (summary ?: outcome ?: "done").take(180)
            if (beaconRole == "follow") beacon.send("AUDIT $report")
            else if (!fromPeer && beaconRole == "lead") beacon.send("FIX $report")
            persistNow(project)
        }
    }

    /** Sends a short follow-up so a stopped task can pick up from the chat history. */
    fun continueAgent(path: String? = null) {
        val project = projectFor(path)
        if (project == null) {
            showMessage("Open a project folder first.")
            return
        }
        if (project.agentJob?.isActive == true) return
        if (project.conversation.isEmpty() && project.ui.chat.isEmpty()) {
            showMessage("Nothing to continue yet.")
            return
        }
        startPrompt(
            project,
            "Continue the previous task from where it stopped. Do not repeat steps that already finished.",
        )
    }

    fun cancelAgent(path: String? = null) {
        val project = projectFor(path) ?: return
        abandonAgent(project, announce = true)
    }

    private fun projectFor(path: String?): LiveProject? {
        if (path.isNullOrBlank()) return current
        return projects[path]
    }

    fun checkForUpdate(manual: Boolean = false) {
        if (!manual && (updateChecked || _state.value.appUpdate != null)) return
        if (manual && _state.value.appUpdate?.downloading == true) return
        if (!manual) updateChecked = true
        viewModelScope.launch {
            val local = withContext(Dispatchers.IO) { AppUpdater.localVersion(appContext) }
            val offer = withContext(Dispatchers.IO) {
                runCatching { AppUpdater.latestNewerThan(local) }.getOrNull()
            }
            if (offer == null) {
                if (manual) showMessage("Version $local is up to date.")
                return@launch
            }
            _state.update {
                it.copy(appUpdate = offer.copy(error = null, downloading = false, installing = false))
            }
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
        startUpdate(offer.copy(error = null, downloading = false, installing = false, replaceInstalled = false))
    }

    fun allowInstalls() {
        AppUpdater.requestInstallPermission(appContext)
    }

    /** Copies the update into Downloads, closes this dialog, then opens Android's uninstall screen. */
    fun uninstallForUpdate(context: Context) {
        val ready = _state.value.appUpdate?.exportedApk?.let { File(it) }?.takeIf { it.isFile }
            ?: AppUpdater.exportUpdateApk(appContext)
        if (ready == null) {
            showMessage("Could not copy the update into Downloads.")
            return
        }
        _state.update { it.copy(appUpdate = null) }
        val opened = runCatching { AppUpdater.uninstallSelf(context) }
        if (opened.isFailure) {
            showMessage(opened.exceptionOrNull()?.message ?: "Could not open the uninstall screen.")
        }
    }

    fun stageCopy(path: String) = stageFile(path, cut = false)

    fun stageCut(path: String) = stageFile(path, cut = true)

    fun clearFileClipboard() {
        _state.update { it.copy(fileClipboard = null) }
    }

    fun renameEntry(path: String, newName: String) {
        val project = current ?: return
        val cleaned = newName.trim().replace('\\', '/').trim('/')
        if (cleaned.isEmpty() || cleaned.contains('/') || cleaned.contains('\u0000')) {
            showMessage("Use a single name without slashes.")
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val source = project.workspace.resolve(path)
                if (!source.exists()) error("That item is gone.")
                if (isProtectedFile(source, project)) error("That folder cannot be renamed.")
                val dest = File(source.parentFile, cleaned)
                if (dest.exists()) error("$cleaned already exists.")
                val from = project.workspace.relativize(source)
                if (!source.renameTo(dest)) error("Could not rename ${source.name}.")
                from to project.workspace.relativize(dest)
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { (from, to) ->
                    retargetPaths(project, from, to)
                    refreshProjectTree(project, to)
                    showMessage("Renamed to ${to.substringAfterLast('/')}")
                }.onFailure { showMessage(it.message ?: "Could not rename.") }
            }
        }
    }

    fun deleteEntry(path: String) {
        val project = current ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val source = project.workspace.resolve(path)
                if (!source.exists()) error("That item is gone.")
                if (isProtectedFile(source, project)) error("That folder cannot be deleted.")
                val from = project.workspace.relativize(source)
                val removed = if (source.isDirectory) source.deleteRecursively() else source.delete()
                if (!removed) error("Could not delete ${source.name}.")
                from
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { from ->
                    forgetPaths(project, from)
                    refreshProjectTree(project, from.substringBeforeLast('/', ""))
                    showMessage("Deleted ${from.substringAfterLast('/')}")
                }.onFailure { showMessage(it.message ?: "Could not delete.") }
            }
        }
    }

    fun pasteEntry(intoDir: String) {
        val clip = _state.value.fileClipboard ?: return
        val project = current ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val result = runCatching {
                val source = File(clip.absolutePath)
                if (!source.exists()) error("The copied item is gone.")
                val dir = project.workspace.resolve(intoDir)
                if (!dir.isDirectory) error("Choose a folder to paste into.")
                val dest = File(dir, source.name)
                if (dest.exists()) error("${source.name} already exists here.")
                val sourceCanon = source.canonicalFile
                val destCanon = dest.canonicalFile
                if (sourceCanon.isDirectory &&
                    (destCanon.path == sourceCanon.path || destCanon.path.startsWith(sourceCanon.path + File.separator))
                ) {
                    error("Cannot paste a folder into itself.")
                }
                val from = runCatching { project.workspace.relativize(source) }.getOrDefault(source.path)
                if (clip.cut) {
                    if (!source.renameTo(dest)) {
                        source.copyRecursively(dest, overwrite = false)
                        if (!source.deleteRecursively()) error("Could not finish the move.")
                    }
                } else {
                    source.copyRecursively(dest, overwrite = false)
                }
                from to project.workspace.relativize(dest)
            }
            withContext(Dispatchers.Main) {
                result.onSuccess { (from, to) ->
                    if (clip.cut) {
                        retargetPaths(project, from, to)
                        _state.update { it.copy(fileClipboard = null) }
                    }
                    refreshProjectTree(project, to)
                    showMessage(if (clip.cut) "Moved ${to.substringAfterLast('/')}" else "Pasted ${to.substringAfterLast('/')}")
                }.onFailure { showMessage(it.message ?: "Could not paste.") }
            }
        }
    }

    private fun stageFile(path: String, cut: Boolean) {
        val project = current ?: return
        val file = runCatching { project.workspace.resolve(path) }.getOrElse {
            showMessage(it.message ?: "Bad path.")
            return
        }
        if (!file.exists()) {
            showMessage("That item is gone.")
            return
        }
        val absolute = runCatching { file.canonicalPath }.getOrElse {
            showMessage(it.message ?: "Bad path.")
            return
        }
        _state.update {
            it.copy(fileClipboard = FileClipboard(absolute, file.name, cut))
        }
        showMessage(if (cut) "Cut ${file.name}. Long-press a folder to paste." else "Copied ${file.name}. Long-press a folder to paste.")
    }

    private fun isProtectedFile(target: File, project: LiveProject): Boolean {
        val path = runCatching { target.canonicalPath }.getOrDefault(target.path)
        if (path == project.workspace.root.canonicalPath) return true
        return path in setOf("/", "/sdcard", "/storage", "/storage/emulated", "/storage/emulated/0")
    }

    private fun retargetPaths(project: LiveProject, from: String, to: String) {
        if (from == to) return
        project.buffers.keys.toList().forEach { key ->
            val mapped = mapStoredPath(key, from, to)
            if (mapped != key) project.buffers.remove(key)?.let { project.buffers[mapped] = it }
        }
        editProject(project) { shown ->
            shown.copy(
                openFiles = shown.openFiles.map { file ->
                    val mapped = mapStoredPath(file.path, from, to)
                    if (mapped == file.path) file else file.copy(path = mapped)
                },
                activePath = shown.activePath?.let { mapStoredPath(it, from, to) },
                dirtyPaths = shown.dirtyPaths.map { mapStoredPath(it, from, to) }.toSet(),
                expandedDirs = shown.expandedDirs.map { mapStoredPath(it, from, to) }.toSet(),
            )
        }
    }

    private fun forgetPaths(project: LiveProject, from: String) {
        project.buffers.keys.removeAll { it == from || it.startsWith("$from/") }
        editProject(project) { shown ->
            val open = shown.openFiles.filterNot { it.path == from || it.path.startsWith("$from/") }
            val activeGone = shown.activePath == from || shown.activePath?.startsWith("$from/") == true
            shown.copy(
                openFiles = open,
                activePath = if (activeGone) open.lastOrNull()?.path else shown.activePath,
                dirtyPaths = shown.dirtyPaths.filterNot { it == from || it.startsWith("$from/") }.toSet(),
            )
        }
    }

    private fun mapStoredPath(path: String, from: String, to: String): String = when {
        path == from -> to
        path.startsWith("$from/") -> to + path.removePrefix(from)
        else -> path
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
    private fun commitRunContext(project: LiveProject, finalText: String) {
        val saved = project.runContext
        project.runContext = null
        if (saved != null) {
            project.conversation.clear()
            project.conversation.addAll(ToolTranscript.seal(saved))
            val alreadyThere = project.conversation.lastOrNull()?.content == finalText
            if (finalText.isNotBlank() && !alreadyThere) {
                project.conversation += ChatMessage.assistant(finalText)
            }
            return
        }
        if (finalText.isNotBlank()) {
            project.conversation += ChatMessage.assistant(finalText)
        }
    }

    private fun abandonAgent(project: LiveProject, announce: Boolean) {
        val wasRunning = project.agentJob?.isActive == true || project.ui.agentRunning
        val partial = project.liveAnswer.toString()
        project.liveAnswer.clear()
        project.epoch.incrementAndGet()
        commitRunContext(project, partial)
        project.agentJob?.cancel()
        project.agentJob = null
        project.thoughtTail = ""
        project.lastNoticePhase = ""
        val started = project.ui.agentActivity?.startedAt ?: System.currentTimeMillis()
        if (current !== project && wasRunning) {
            project.unseenResult = if (announce) "Stopped" else "Left running task"
        }
        editProject(project) { it.copy(agentRunning = false, agentActivity = null) }
        if (announce && wasRunning) {
            val note = "Stopped by the user."
            appendChat(project, ChatRole.Error, note)
            project.runOutcome = note
            AgentNotifications.settle(
                appContext,
                agentStatus(project, note, "Continue resumes, or swipe this away.", "", started),
            )
        } else if (wasRunning) {
            AgentNotifications.dismiss(appContext, project.workspace.root.canonicalPath)
        }
    }

    private fun handleEvent(project: LiveProject, event: AgentEvent, finalText: StringBuilder) {
        when (event) {
            is AgentEvent.AssistantText -> {
                finalText.append(event.text)
                project.liveAnswer.append(event.text)
                appendStreaming(project, ChatRole.Assistant, event.text)
                if (project.ui.agentActivity?.phase != "Writing the answer") {
                    setActivity(project, phase = "Writing the answer", focus = "")
                }
            }

            is AgentEvent.Reasoning -> {
                appendStreaming(project, ChatRole.Reasoning, event.text)
                noteThought(project, event.text)
            }

            is AgentEvent.Activity -> {
                project.thoughtTail = ""
                val openFile = project.ui.activePath?.substringAfterLast('/').orEmpty()
                setActivity(
                    project,
                    phase = event.phase,
                    context = formatActivityContext(project, event.messages, event.chars),
                    focus = if (openFile.isBlank()) "" else "open $openFile",
                )
            }

            is AgentEvent.ToolStarted -> {
                appendChat(
                    project,
                    role = ChatRole.Tool,
                    text = visibleToolArguments(event.name, event.arguments),
                    toolName = "${event.name} →",
                )
                setActivity(
                    project,
                    phase = toolPhase(event.name, finished = false, error = false),
                    focus = toolFocus(event.name, event.arguments),
                )
            }

            is AgentEvent.ToolFinished -> {
                appendChat(
                    project,
                    role = if (event.result.isError) ChatRole.Error else ChatRole.Tool,
                    text = event.result.content,
                    toolName = "${event.name} ←",
                )
                val line = event.result.content
                    .lineSequence()
                    .firstOrNull { it.isNotBlank() }
                    .orEmpty()
                    .take(140)
                val phase = toolPhase(event.name, finished = true, error = event.result.isError)
                setActivity(project, phase = phase, focus = line)
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
                    appendChat(project, ChatRole.Error, note)
                    project.runOutcome = note
                }
            }

            is AgentEvent.Context -> project.runContext = event.messages
            is AgentEvent.Failed -> appendChat(project, ChatRole.Error, event.message)
        }
    }

    private suspend fun prepareShizuku(): String? {
        if (!runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            if (com.hvkeyn.ceditneuro.shizuku.SuShell.available()) return null
            val launch = appContext.packageManager.getLaunchIntentForPackage("moe.shizuku.privileged.api")
            if (launch != null && !shizukuPrompted) {
                shizukuPrompted = true
                launch.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { appContext.startActivity(launch) }
            }
            return "Shell access is not available. Root is not present and Shizuku is not running. " +
                "Do not call shizuku_exec, fetch_system_layout, or execute_system_action again until the user starts Shizuku or roots the phone. " +
                "Use run_command for files, net_info for this app's network, and install_apk for an APK."
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

    private fun formatActivityContext(project: LiveProject, messages: Int, chars: Int): String {
        val model = settingsStore.current.modelLabel()
        val size = if (chars >= 1024) "${chars / 1024} KB" else "$chars B"
        val name = project.workspace.root.name.ifBlank { "project" }
        return "$model · $messages messages · $size · $name"
    }

    private fun setActivity(
        project: LiveProject,
        phase: String,
        context: String? = null,
        focus: String? = null,
    ) {
        editProject(project) { shown ->
            val previous = shown.agentActivity
            val started = previous?.startedAt ?: System.currentTimeMillis()
            shown.copy(
                agentActivity = AgentActivity(
                    phase = phase,
                    context = context ?: previous?.context.orEmpty(),
                    focus = focus ?: previous?.focus.orEmpty(),
                    startedAt = started,
                ),
            )
        }
        publishStatus(project)
    }

    private fun publishStatus(project: LiveProject, force: Boolean = false) {
        val activity = project.ui.agentActivity
        if (!project.ui.agentRunning || activity == null) return
        val now = System.currentTimeMillis()
        if (!force && activity.phase == project.lastNoticePhase && now - project.lastNoticeAt < 800) return
        project.lastNoticeAt = now
        project.lastNoticePhase = activity.phase
        AgentNotifications.publish(
            appContext,
            agentStatus(project, activity.phase, activity.context, activity.focus, activity.startedAt),
        )
    }

    private fun toolPhase(name: String, finished: Boolean, error: Boolean): String {
        val action = when (name) {
            "read_file" -> "Reading a file"
            "write_file" -> "Writing a file"
            "edit_file" -> "Editing a file"
            "list_dir" -> "Listing a folder"
            "grep" -> "Searching the project"
            "glob" -> "Finding files"
            "mkdir" -> "Creating a folder"
            "delete_path" -> "Deleting"
            "move_path" -> "Moving a file"
            "git_status" -> "Checking git"
            "git_diff" -> "Reading the diff"
            "run_command" -> "Running a command"
            "zip_paths" -> "Packing a zip"
            "http_request" -> "Requesting a page"
            "load_tools" -> "Loading tools"
            "fetch_system_layout" -> "Reading the screen"
            "execute_system_action" -> "Tapping the screen"
            "shizuku_exec" -> "Running a shell command"
            "install_apk" -> "Installing an APK"
            "install_jdk" -> "Installing the JDK"
            "install_android_sdk" -> "Installing the Android SDK"
            "install_runtime" -> "Installing a runtime"
            "install_program" -> "Installing a program"
            "install_module" -> "Installing a module"
            "net_info" -> "Checking the network"
            "remote_connect" -> "Connecting to a server"
            "remote_list" -> "Listing a remote folder"
            "remote_read" -> "Reading a remote file"
            "remote_write" -> "Writing a remote file"
            "remote_put" -> "Uploading"
            "remote_get" -> "Downloading"
            "ssh_exec" -> "Running a remote command"
            "browse_page" -> "Opening a page"
            else -> name.replace('_', ' ').replaceFirstChar { it.uppercase() }
        }
        return when {
            error -> "$action failed"
            finished -> "+ $action"
            else -> action
        }
    }

    private fun agentStatus(
        project: LiveProject,
        phase: String,
        detail: String,
        focus: String,
        startedAt: Long,
        done: Boolean = false,
    ): AgentStatus = AgentStatus(
        key = project.workspace.root.canonicalPath,
        name = project.workspace.root.name.ifBlank { "Agent" },
        phase = phase,
        detail = detail,
        focus = focus,
        startedAt = startedAt,
        workLine = workLine(),
        done = done,
    )

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
        return "$work · $network · $programs" +
            (if (beaconRole == "lead") " · Lead" else if (beaconRole == "follow") " · Support" else "") +
            parallelPeer.takeIf { it.isNotBlank() }?.let { "\nParallel: $it" }.orEmpty()
    }

    private fun openBeacon(code: String, lead: Boolean) {
        beaconRole = if (lead) "lead" else "follow"
        com.hvkeyn.ceditneuro.net.LinkService.start(appContext, code, "Waiting for the other phone.")
        val device = android.os.Build.MODEL.replace(Regex("[^A-Za-z0-9]"), "").ifBlank { "phone" }
        beacon.start(code, device, onReady = {
            beacon.send("HERE")
            current?.workspace?.root?.name?.let { beacon.send("FOLDER $it") }
        }, onDirect = { direct ->
            _state.update { it.copy(linkDirect = direct) }
        }, onRoster = { body ->
            val names = body.split(' ').drop(1).filter { it.isNotBlank() }
            val total = synchronized(seenPeers) {
                seenPeers += names
                seenPeers.size
            }
            _state.update {
                it.copy(
                    linkActive = names.size,
                    linkClients = total,
                    linkPeer = names.joinToString(),
                    linkNote = if (names.size > 1) "Linked. $total in this code, ${names.size} active."
                    else "On the code. Waiting for the other phone.",
                )
            }
            com.hvkeyn.ceditneuro.net.LinkService.update(
                appContext,
                code,
                if (names.size > 1) "$total in this code, ${names.size} active."
                else "Waiting for the other phone.",
            )
        }) { name, text ->
            viewModelScope.launch {
                noteSpeed(text.length)
                val project = current
                if (text.startsWith("FOLDER ") && project == null) {
                    ensurePeerFolder(text.removePrefix("FOLDER "))
                    return@launch
                }
                val open = project ?: return@launch
                _state.update { it.copy(linkGot = it.linkGot + 1, linkPeer = name) }
                if (text == "LEFT") {
                    appendChat(open, ChatRole.Error, "$name left the link. Do not wait for that phone.")
                    synchronized(peerLines) { peerLines.add("$name left the link") }
                    return@launch
                }
                if (text == "HERE") {
                    if (beaconRole == "lead") pushFolder(open)
                    return@launch
                }
                if (text.startsWith("FILE ")) {
                    saveSharedFile(open, text.removePrefix("FILE "))
                    return@launch
                }
                val running = open.agentJob?.isActive == true
                val body = text.removePrefix("TASK ").removePrefix("AUDIT ").removePrefix("FIX ")
                val started = when {
                    text.startsWith("TASK ") && beaconRole == "follow" && !running -> {
                        startPrompt(
                            open,
                            "You are the supporting agent. Do this part of the lead's task. " +
                                "End with corrections the lead should apply.\n$body",
                            fromPeer = true,
                        )
                        true
                    }
                    text.startsWith("AUDIT ") && beaconRole == "lead" && !running -> {
                        startPrompt(
                            open,
                            "Apply this audit from the supporting agent. Correct your work where the audit is right. " +
                                "Do not assign the same task again.\n$body",
                            fromPeer = true,
                        )
                        true
                    }
                    text.startsWith("FIX ") && beaconRole == "follow" && !running -> {
                        startPrompt(
                            open,
                            "The lead finished this. Check it and end with only the corrections.\n$body",
                            fromPeer = true,
                        )
                        true
                    }
                    else -> false
                }
                val line = "$name: $text"
                synchronized(peerLines) {
                    peerLines.add(line)
                    while (peerLines.size > 8) peerLines.removeFirst()
                }
                parallelPeer = line.take(140)
                if (!started) appendChat(open, ChatRole.Reasoning, "Peer $line")
                if (open.ui.agentRunning) publishStatus(open, force = true)
                else if (!started) {
                    AgentNotifications.publish(
                        appContext,
                        agentStatus(open, "Parallel · $name", line, "", System.currentTimeMillis()),
                    )
                }
            }
        }
    }

    private fun ensurePeerFolder(name: String) {
        val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "shared" }
        val root = com.hvkeyn.ceditneuro.workspace.StoragePaths.primaryRoot()
        var dir = java.io.File(root, safe)
        var n = 2
        while (dir.exists()) {
            dir = java.io.File(root, "$safe-$n")
            n++
        }
        dir.mkdirs()
        openProject(dir)
    }

    private fun noteSpeed(bytes: Int) {
        speedBytes += bytes
        val now = System.currentTimeMillis()
        if (now - speedMark < 1_000) return
        val speed = (speedBytes * 1000 / (now - speedMark)).toInt()
        speedBytes = 0
        speedMark = now
        _state.update { it.copy(linkSpeed = speed) }
    }

    private fun pushFolder(project: LiveProject) {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            val root = project.workspace.root
            root.walkTopDown().filter { it.isFile && it.length() in 1..40_000 }.take(30).forEach { file ->
                val relative = file.relativeTo(root).invariantSeparatorsPath
                if (relative.startsWith(".git") || relative.contains("/build/")) return@forEach
                beacon.sendFile(relative, file.readBytes())
                _state.update { it.copy(linkSent = it.linkSent + 1) }
            }
        }
    }

    private fun saveSharedFile(project: LiveProject, body: String) {
        val parts = body.split(' ', limit = 2)
        if (parts.size < 2 || parts[0].contains("..") || parts[0].startsWith("/")) return
        val bytes = android.util.Base64.decode(parts[1], android.util.Base64.NO_WRAP)
        val dest = java.io.File(project.workspace.root, parts[0])
        dest.parentFile?.mkdirs()
        dest.writeBytes(bytes)
        refreshProjectTree(project)
    }

    private fun takePeerFeed(): String = synchronized(peerLines) {
        if (peerLines.isEmpty()) "" else peerLines.joinToString("\n").also { peerLines.clear() }
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

    private fun noteThought(project: LiveProject, delta: String) {
        project.thoughtTail = (project.thoughtTail + delta).takeLast(240)
        val now = System.currentTimeMillis()
        val alreadyThinking = project.ui.agentActivity?.phase == "Thinking"
        if (alreadyThinking && now - project.lastThoughtUi < 400) return
        project.lastThoughtUi = now
        val line = project.thoughtTail
            .lineSequence()
            .lastOrNull { it.isNotBlank() }
            ?.trim()
            .orEmpty()
            .ifBlank { project.thoughtTail.trim() }
            .take(140)
        setActivity(project, phase = "Thinking", focus = line)
        beacon.send(line)
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

    private fun appendChat(project: LiveProject, role: ChatRole, text: String, toolName: String? = null) {
        editProject(project) { shown ->
            val sealed = shown.chat.map { if (it.streaming) it.copy(streaming = false) else it }
            shown.copy(chat = sealed + ChatEntry(nextId(project), role, text, toolName))
        }
        schedulePersist(project)
    }

    private fun appendStreaming(project: LiveProject, role: ChatRole, delta: String) {
        editProject(project) { shown ->
            val last = shown.chat.lastOrNull()
            if (last != null && last.role == role && last.streaming) {
                shown.copy(
                    chat = shown.chat.dropLast(1) + last.copy(text = last.text + delta),
                )
            } else {
                val sealed = shown.chat.map { if (it.streaming) it.copy(streaming = false) else it }
                shown.copy(chat = sealed + ChatEntry(nextId(project), role, delta, streaming = true))
            }
        }
        schedulePersist(project)
    }

    /** Called from file tools after the agent creates or edits a file. */
    private fun onAgentEditedFile(project: LiveProject, path: String) {
        refreshOpenFile(project, path)
        if (!path.startsWith("/")) refreshProjectTree(project, path)
    }

    private fun refreshOpenFile(project: LiveProject, path: String) {
        val shown = if (current === project) _state.value else project.ui
        if (shown.openFiles.none { it.path == path }) return
        if (path in shown.dirtyPaths) {
            if (current === project) {
                showMessage("The agent changed $path, but you have unsaved edits. Save or revert, then reopen it.")
            }
            return
        }
        val fresh = runCatching { project.workspace.read(path) }.getOrNull() ?: return
        project.buffers[path] = fresh
        editProject(project) { currentState ->
            currentState.copy(
                openFiles = currentState.openFiles.map {
                    if (it.path == path) it.copy(onDisk = fresh) else it
                },
                reloadCounter = currentState.reloadCounter + 1,
            )
        }
    }

    /**
     * Reloads the visible tree from disk. [revealPath] expands every parent directory
     * so a file the agent just created shows up without reopening the project.
     */
    private fun refreshProjectTree(revealPath: String? = null) {
        val project = current ?: return
        refreshProjectTree(project, revealPath)
    }

    private fun refreshProjectTree(project: LiveProject, revealPath: String? = null) {
        val ws = project.workspace
        val generation = ++project.treeGeneration
        val parents = ancestorDirs(revealPath)
        viewModelScope.launch(Dispatchers.IO) {
            val expandedSource = if (current === project) _state.value.expandedDirs else project.ui.expandedDirs
            val expandedNow = expandedSource + parents
            val rootEntries = runCatching { ws.children() }.getOrDefault(emptyList())
            val contents = expandedNow.associateWith { dir ->
                runCatching { ws.children(dir) }.getOrDefault(emptyList())
            }
            if (generation != project.treeGeneration) return@launch
            editProject(project) { shown ->
                shown.copy(
                    rootEntries = rootEntries,
                    expandedDirs = shown.expandedDirs + parents,
                    dirContents = shown.dirContents + contents,
                )
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

    private fun buildAgentLoop(project: LiveProject): AgentLoop {
        val ws = project.workspace
        val epochAtBuild = project.epoch.get()
        val changeListener: (String, String, String) -> Unit = { path, _, _ ->
            if (project.epoch.get() == epochAtBuild) onAgentEditedFile(project, path)
        }

        val shizukuCommands = ShizukuCommandRunner(shizukuShell)
        val loadedGroups = ToolGroups.forFocus(settingsStore.current.workFocus)
        val toolSession = ToolSession(loadedGroups)
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
                if (project.epoch.get() != epochAtBuild) {
                    rememberFinishedCommand(ws.root.canonicalPath, command, rendered)
                    return@ShellTool
                }
                appendShellLine(project, command, rendered)
                refreshProjectTree(project)
            },
            HttpRequestTool(ws, agentNet, { settingsStore.current.networkEnabled }) { path ->
                if (project.epoch.get() == epochAtBuild) onAgentEditedFile(project, path)
            },
            InstallModuleTool(ws, agentNet, { settingsStore.current.networkEnabled }) { path ->
                if (project.epoch.get() == epochAtBuild) onAgentEditedFile(project, path)
            },
            InstallRuntimeTool(
                toolchain = deviceShell.toolchain,
                net = agentNet,
                networkAllowed = { settingsStore.current.networkEnabled },
                ensureExec = this::ensureExecAllowed,
            ),
            ShizukuExecTool(ws, shizukuShell, this::prepareShizuku),
            LoadToolsTool(toolSession),
            ReaderNoteTool { text -> readerAddNote(text) },
            ReaderSketchTool { labels ->
                val page = _state.value.reader?.page ?: 0
                val body = labels.mapIndexed { index, line -> "${index + 1}. $line" }.joinToString("\n")
                readerAddLabel(page, body, 0.08f, 0.12f, 0xFF175CD3)
            },
            FetchSystemLayoutTool(
                shizukuCommands,
                this::prepareShizuku,
                File(appContext.getExternalFilesDir(null) ?: appContext.cacheDir, "ui-state.xml"),
            ),
            ExecuteSystemActionTool(shizukuCommands, this::prepareShizuku),
            NetInfoTool(appContext),
            InstallApkTool(appContext, ws),
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
                if (project.epoch.get() == epochAtBuild) onAgentEditedFile(project, path)
            },
            SshExecTool(::activeRemote, remoteClient),
            SpaceSyncTool(ws, ::activeRemote, remoteClient, android.os.Build.MODEL.replace(Regex("[^A-Za-z0-9]"), "")),
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
            toolSession = toolSession,
            systemPrompt = buildSystemPrompt(),
            peerFeed = ::takePeerFeed,
            setupPrompt = buildSetupPrompt(
                projectRoot = ws.root.absolutePath,
                toolchainBin = deviceShell.toolchainBin.absolutePath,
                remoteSummary = remoteSummary,
                workFocus = settingsStore.current.workFocus,
                accessLine = accessLine(),
                storageLine = StoragePaths.describe(),
                loadedGroups = loadedGroups,
                projectRules = buildString {
                    val rules = readProjectRules(ws.root)
                    if (rules.isNotBlank()) append(rules)
                    val names = ws.root.list()?.filter { it != ".ceditneuro" }.orEmpty()
                    if (names.isEmpty()) {
                        if (isNotEmpty()) append("\n")
                        append("This project folder is empty. Do not treat it as the user's notes. ")
                        append("Ask which folder to open, or use an absolute path the user already gave.")
                    }
                },
            ),
        )
    }

    private fun appendShellLine(project: LiveProject, command: String, rendered: String) {
        editProject(project) {
            it.copy(
                shellLines = it.shellLines + ShellLine(nextId(project), command, rendered),
            )
        }
        schedulePersist(project)
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

    private fun snapshotSession(project: LiveProject): StoredSession {
        val shown = if (current === project) _state.value else project.ui
        return StoredSession(
            chat = shown.chat.takeLast(MAX_STORED_CHAT).map { entry ->
                StoredChat(
                    id = entry.id,
                    role = entry.role.name,
                    text = entry.text,
                    toolName = entry.toolName,
                )
            },
            conversation = ToolTranscript.trim(project.conversation.toList(), MAX_STORED_CONVERSATION),
            shell = shown.shellLines.filterNot { it.running }.takeLast(MAX_STORED_SHELL).map { line ->
                StoredShell(id = line.id, command = line.command, output = line.output)
            },
            nextId = project.idGenerator.get(),
        )
    }

    private fun schedulePersist(project: LiveProject) {
        project.persistJob?.cancel()
        project.persistJob = viewModelScope.launch {
            delay(400)
            persistNow(project)
        }
    }

    private fun persistNow(project: LiveProject) {
        val root = project.workspace.root.canonicalPath
        library.save(root, snapshotSession(project))
    }

    private fun nextId(project: LiveProject): Long = project.idGenerator.incrementAndGet()

    fun agentDoctorReport(): String {
        val (_, saved) = library.snapshot()
        val merged = saved.toMutableMap()
        projects.forEach { (root, project) ->
            val session = merged[root] ?: com.hvkeyn.ceditneuro.data.StoredSession()
            merged[root] = session.copy(conversation = project.conversation.toList())
        }
        return AgentDoctor.report(merged)
    }

    private fun showMessage(text: String) {
        _state.update { it.copy(message = text) }
    }

    private fun readProjectRules(root: File): String {
        val file = File(root, "AGENTS.md")
        if (!file.isFile || file.length() > 64_000L) return ""
        return runCatching { file.readText(Charsets.UTF_8).trim().take(6_000) }.getOrDefault("")
    }

    override fun onCleared() {
        current?.let { project -> project.ui = projectSlice(_state.value) }
        projects.values.forEach { project ->
            project.persistJob?.cancel()
            persistNow(project)
        }
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
