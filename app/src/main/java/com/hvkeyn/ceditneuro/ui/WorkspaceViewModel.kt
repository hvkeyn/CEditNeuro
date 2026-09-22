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
import com.hvkeyn.ceditneuro.data.SettingsStore
import com.hvkeyn.ceditneuro.tools.EditFileTool
import com.hvkeyn.ceditneuro.tools.GitDiffTool
import com.hvkeyn.ceditneuro.tools.GitStatusTool
import com.hvkeyn.ceditneuro.tools.GlobTool
import com.hvkeyn.ceditneuro.tools.GrepTool
import com.hvkeyn.ceditneuro.tools.ListDirTool
import com.hvkeyn.ceditneuro.tools.ReadFileTool
import com.hvkeyn.ceditneuro.tools.ShellTool
import com.hvkeyn.ceditneuro.tools.Tool
import com.hvkeyn.ceditneuro.tools.ToolRegistry
import com.hvkeyn.ceditneuro.tools.WriteFileTool
import com.hvkeyn.ceditneuro.workspace.FileEntry
import com.hvkeyn.ceditneuro.workspace.Workspace
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
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

data class WorkspaceUiState(
    val projectRoot: String? = null,
    val rootEntries: List<FileEntry> = emptyList(),
    val dirContents: Map<String, List<FileEntry>> = emptyMap(),
    val expandedDirs: Set<String> = emptySet(),
    val openFiles: List<OpenFile> = emptyList(),
    val activePath: String? = null,
    val dirtyPaths: Set<String> = emptySet(),
    val reloadCounter: Long = 0L,
    val chat: List<ChatEntry> = emptyList(),
    val chatVisible: Boolean = false,
    val agentRunning: Boolean = false,
    val termuxAvailable: Boolean = false,
    val message: String? = null,
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

    /** Live editor text per open file, including unsaved changes. */
    private val buffers = mutableMapOf<String, String>()

    /** Conversation handed back to the model on the next request. */
    private val conversation = mutableListOf<ChatMessage>()

    private var agentJob: Job? = null
    private val idGenerator = AtomicLong(0)

    fun openProject(root: File) {
        val opened = runCatching { Workspace(root) }.getOrElse { error ->
            showMessage("Cannot open project: ${error.message}")
            return
        }
        workspace = opened
        buffers.clear()
        conversation.clear()
        idGenerator.set(0)

        _state.value = WorkspaceUiState(
            projectRoot = opened.root.absolutePath,
            rootEntries = opened.children(),
            chatVisible = _state.value.chatVisible,
            termuxAvailable = isTermuxInstalled(),
        )
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
        _state.update { it.copy(chatVisible = !it.chatVisible) }
    }

    fun dismissMessage() {
        _state.update { it.copy(message = null) }
    }

    fun updateSettings(transform: (AgentSettings) -> AgentSettings) {
        settingsStore.update(transform)
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

        val loop = buildAgentLoop(ws)
        val finalText = StringBuilder()

        agentJob = viewModelScope.launch {
            _state.update { it.copy(agentRunning = true, chatVisible = true, message = null) }

            runCatching {
                loop.run(history, prompt).collect { event -> handleEvent(event, finalText) }
            }.onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                appendChat(ChatRole.Error, error.message ?: error.toString())
            }

            if (finalText.isNotBlank()) {
                conversation += ChatMessage.assistant(finalText.toString())
            }
            _state.update { it.copy(agentRunning = false) }
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
                text = event.arguments.ifBlank { "(no arguments)" },
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

    private fun appendChat(role: ChatRole, text: String, toolName: String? = null) {
        _state.update { current ->
            val sealed = current.chat.map { if (it.streaming) it.copy(streaming = false) else it }
            current.copy(chat = sealed + ChatEntry(nextId(), role, text, toolName))
        }
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
    }

    /** Called from file tools after the agent edits a file. */
    private fun onAgentEditedFile(path: String) {
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

    private fun buildAgentLoop(ws: Workspace): AgentLoop {
        val changeListener: (String, String, String) -> Unit = { path, _, _ ->
            onAgentEditedFile(path)
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
        )
        if (isTermuxInstalled()) {
            tools += ShellTool(appContext, ws.root)
        }

        return AgentLoop(
            backend = DeepSeekBackend { settingsStore.current },
            toolRegistry = ToolRegistry(tools),
            systemPrompt = buildSystemPrompt(ws.root.absolutePath),
        )
    }

    private fun isTermuxInstalled(): Boolean = runCatching {
        appContext.packageManager.getPackageInfo("com.termux", 0)
        true
    }.getOrDefault(false)

    private fun nextId(): Long = idGenerator.incrementAndGet()

    private fun showMessage(text: String) {
        _state.update { it.copy(message = text) }
    }

    override fun onCleared() {
        agentJob?.cancel()
        super.onCleared()
    }

    companion object {
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
