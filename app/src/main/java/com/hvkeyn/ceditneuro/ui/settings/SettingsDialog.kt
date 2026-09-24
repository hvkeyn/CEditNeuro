package com.hvkeyn.ceditneuro.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hvkeyn.ceditneuro.data.AgentSettings
import com.hvkeyn.ceditneuro.data.CatalogModel
import com.hvkeyn.ceditneuro.data.ModelProvider
import com.hvkeyn.ceditneuro.data.RemoteServer
import com.hvkeyn.ceditneuro.net.ProviderBalance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun SettingsDialog(
    settings: AgentSettings,
    onSettingsChange: ((AgentSettings) -> AgentSettings) -> Unit,
    onTestRemote: (RemoteServer) -> Unit,
    versionName: String,
    profileName: String,
    profileNames: List<String>,
    onSaveProfile: (String) -> Unit,
    onUseProfile: (String) -> Unit,
    onCheckUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(settings) { mutableStateOf(settings) }
    var profileDraft by rememberSaveable(profileName) { mutableStateOf(profileName) }
    var openSection by rememberSaveable { mutableStateOf("models") }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .safeDrawingPadding(),
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Text(
                    text = "Language models",
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp),
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                ) {
                    SettingsSection(
                        title = "Models",
                        summary = draft.modelLabel(),
                        expanded = openSection == "models",
                        onToggle = { openSection = if (openSection == "models") "" else "models" },
                    ) {
                        draft.providers.forEach { provider ->
                            ProviderCard(
                                provider = provider,
                                selectedProviderId = draft.activeProviderId,
                                selectedModel = draft.activeModel,
                                onSelectModel = { modelName ->
                                    draft = draft.copy(
                                        activeProviderId = provider.id,
                                        activeModel = modelName,
                                    )
                                },
                                onChange = { updated ->
                                    draft = draft.copy(
                                        providers = draft.providers.map { item ->
                                            if (item.id == provider.id) updated else item
                                        },
                                    )
                                },
                                onRemove = if (provider.builtin || draft.providers.size == 1) {
                                    null
                                } else {
                                    {
                                        val remaining = draft.providers.filterNot { it.id == provider.id }
                                        draft = draft.copy(providers = remaining).normalized()
                                    }
                                },
                            )
                        }
                        AddProviderForm(
                            onAdd = { provider ->
                                draft = draft.copy(providers = draft.providers + provider)
                            },
                        )
                    }
                    if (draft.model.supportsReasoning) {
                        SettingsSection(
                            title = "Reasoning",
                            summary = if (draft.thinkingEnabled) draft.reasoningEffort else "Off",
                            expanded = openSection == "reasoning",
                            onToggle = { openSection = if (openSection == "reasoning") "" else "reasoning" },
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text("Thinking", style = MaterialTheme.typography.bodyMedium)
                                Switch(
                                    checked = draft.thinkingEnabled,
                                    onCheckedChange = { draft = draft.copy(thinkingEnabled = it) },
                                )
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                AgentSettings.REASONING_EFFORTS.forEach { effort ->
                                    FilterChip(
                                        selected = draft.reasoningEffort == effort,
                                        onClick = { draft = draft.copy(reasoningEffort = effort) },
                                        label = { Text(effort) },
                                    )
                                }
                            }
                        }
                    }
                    SettingsSection(
                        title = "Agent",
                        summary = "${workLabel(draft.workFocus)} · Net ${if (draft.networkEnabled) "on" else "off"} · Run ${if (draft.execAllowed == true) "on" else "off"}",
                        expanded = openSection == "agent",
                        onToggle = { openSection = if (openSection == "agent") "" else "agent" },
                    ) {
                        Text("Work", style = MaterialTheme.typography.labelLarge)
                        Text(
                            text = "Edit keeps file tools. Build also loads installers. Remote also loads the server tools.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf(
                                AgentSettings.WORK_EDIT to "Edit",
                                AgentSettings.WORK_BUILD to "Build",
                                AgentSettings.WORK_REMOTE to "Remote",
                            ).forEach { (id, label) ->
                                FilterChip(
                                    selected = draft.workFocus == id,
                                    onClick = { draft = draft.copy(workFocus = id) },
                                    label = { Text(label) },
                                )
                            }
                        }
                        ToggleRow(
                            title = "Agent network",
                            detail = "Let the agent download files and install modules.",
                            checked = draft.networkEnabled,
                            onCheckedChange = { draft = draft.copy(networkEnabled = it) },
                        )
                        ToggleRow(
                            title = "Run installed programs",
                            detail = "Lets the agent install and start compilers inside this app. Asked once if left unset.",
                            checked = draft.execAllowed == true,
                            onCheckedChange = { draft = draft.copy(execAllowed = it) },
                        )
                        ToggleRow(
                            title = "Auto-approve edits",
                            detail = "Reserved for the diff review flow; edits already apply immediately.",
                            checked = draft.autoApproveEdits,
                            onCheckedChange = { draft = draft.copy(autoApproveEdits = it) },
                        )
                    }
                    SettingsSection(
                        title = "Servers",
                        summary = draft.remotes.find { it.id == draft.activeRemoteId }?.label() ?: "None",
                        expanded = openSection == "servers",
                        onToggle = { openSection = if (openSection == "servers") "" else "servers" },
                    ) {
                        RemoteSettingsSection(
                            draft = draft,
                            onDraft = { draft = it },
                            onTest = onTestRemote,
                        )
                    }
                    SettingsSection(
                        title = "Profile",
                        summary = profileName,
                        expanded = openSection == "profile",
                        onToggle = { openSection = if (openSection == "profile") "" else "profile" },
                    ) {
                        Text(
                            text = "Saved in CEditNeuro/profiles on this phone. A new install loads the active profile after storage access.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = profileDraft,
                                onValueChange = { profileDraft = it },
                                modifier = Modifier.weight(1f),
                                singleLine = true,
                                label = { Text("Name") },
                            )
                            TextButton(
                                onClick = { onSaveProfile(profileDraft) },
                                enabled = profileDraft.isNotBlank(),
                            ) { Text("Save") }
                        }
                        if (profileNames.isNotEmpty()) {
                            Row(
                                modifier = Modifier.horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                profileNames.forEach { name ->
                                    FilterChip(
                                        selected = name == profileName,
                                        onClick = { onUseProfile(name) },
                                        label = { Text(name) },
                                    )
                                }
                            }
                        }
                    }
                }

                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Version $versionName",
                        style = MaterialTheme.typography.labelMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onCheckUpdate) { Text("Check for updates") }
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.End,
                ) {
                    TextButton(onClick = onDismiss) { Text("Cancel") }
                    TextButton(
                        onClick = {
                            onSettingsChange { draft.normalized() }
                            onDismiss()
                        },
                    ) { Text("Save") }
                }
            }
        }
    }
}

@Composable
private fun SettingsSection(
    title: String,
    summary: String,
    expanded: Boolean,
    onToggle: () -> Unit,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                if (!expanded) {
                    Text(
                        text = summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Icon(
                imageVector = if (expanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                contentDescription = if (expanded) "Collapse $title" else "Expand $title",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (expanded) {
            Column(
                modifier = Modifier.padding(start = 20.dp, end = 20.dp, bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                content()
            }
        }
        HorizontalDivider()
    }
}

private fun workLabel(focus: String): String = when (focus) {
    AgentSettings.WORK_BUILD -> "Build"
    AgentSettings.WORK_REMOTE -> "Remote"
    else -> "Edit"
}

@Composable
private fun ToggleRow(
    title: String,
    detail: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Text(
                text = detail,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun ProviderCard(
    provider: ModelProvider,
    selectedProviderId: String,
    selectedModel: String,
    onSelectModel: (String) -> Unit,
    onChange: (ModelProvider) -> Unit,
    onRemove: (() -> Unit)?,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(provider.name, style = MaterialTheme.typography.titleMedium)
            if (onRemove != null) {
                TextButton(onClick = onRemove) { Text("Remove") }
            }
        }
        OutlinedTextField(
            value = provider.apiUrl,
            onValueChange = { onChange(provider.copy(apiUrl = it)) },
            label = { Text("API URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = provider.apiKey,
            onValueChange = { onChange(provider.copy(apiKey = it)) },
            label = { Text("API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        BalanceLine(provider.apiUrl, provider.apiKey)
        provider.models.forEach { model ->
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth(),
            ) {
                RadioButton(
                    selected = provider.id == selectedProviderId && model.name == selectedModel,
                    onClick = { onSelectModel(model.name) },
                )
                Column {
                    Text(model.displayName, style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = model.name,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
        AddModelRow { name, displayName, reasoning ->
            onChange(
                provider.copy(
                    models = provider.models + CatalogModel(
                        name = name,
                        displayName = displayName.ifBlank { name },
                        supportsReasoning = reasoning,
                    ),
                ),
            )
        }
        HorizontalDivider()
    }
}

@Composable
private fun BalanceLine(apiUrl: String, apiKey: String) {
    var text by remember(apiUrl, apiKey) { mutableStateOf(if (apiKey.isBlank()) "No API key." else "Checking balance…") }
    val scope = rememberCoroutineScope()
    fun refresh() {
        if (apiKey.isBlank()) {
            text = "No API key."
            return
        }
        text = "Checking balance…"
        scope.launch {
            text = withContext(Dispatchers.IO) { ProviderBalance.lookup(apiUrl, apiKey) }
        }
    }
    LaunchedEffect(apiUrl, apiKey) { refresh() }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = { refresh() }, enabled = apiKey.isNotBlank()) { Text("Balance") }
    }
}

@Composable
private fun AddModelRow(onAdd: (name: String, displayName: String, reasoning: Boolean) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }
    var reasoning by rememberSaveable { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Add a model", style = MaterialTheme.typography.labelLarge)
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Model id") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            label = { Text("Display name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Reasoning", style = MaterialTheme.typography.bodySmall)
            Switch(checked = reasoning, onCheckedChange = { reasoning = it })
        }
        TextButton(
            onClick = {
                val trimmed = name.trim()
                if (trimmed.isEmpty()) return@TextButton
                onAdd(trimmed, displayName.trim(), reasoning)
                name = ""
                displayName = ""
                reasoning = false
            },
            enabled = name.isNotBlank(),
        ) { Text("Add model") }
    }
}

@Composable
private fun AddProviderForm(onAdd: (ModelProvider) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    var apiUrl by rememberSaveable { mutableStateOf("") }
    var apiKey by rememberSaveable { mutableStateOf("") }
    var modelId by rememberSaveable { mutableStateOf("") }
    var displayName by rememberSaveable { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Add a provider", style = MaterialTheme.typography.titleSmall)
        Text(
            text = "OpenAI-compatible host. The key is saved only in device settings.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = apiUrl,
            onValueChange = { apiUrl = it },
            label = { Text("API URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = apiKey,
            onValueChange = { apiKey = it },
            label = { Text("API key") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = modelId,
            onValueChange = { modelId = it },
            label = { Text("Model id") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = displayName,
            onValueChange = { displayName = it },
            label = { Text("Display name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        TextButton(
            onClick = {
                val providerName = name.trim()
                val url = apiUrl.trim()
                val model = modelId.trim()
                if (providerName.isEmpty() || url.isEmpty() || model.isEmpty()) return@TextButton
                onAdd(
                    ModelProvider(
                        id = "custom-" + System.currentTimeMillis().toString(36),
                        name = providerName,
                        apiUrl = url,
                        apiKey = apiKey.trim(),
                        models = listOf(
                            CatalogModel(
                                name = model,
                                displayName = displayName.trim().ifBlank { model },
                            ),
                        ),
                    ),
                )
                name = ""
                apiUrl = ""
                apiKey = ""
                modelId = ""
                displayName = ""
            },
            enabled = name.isNotBlank() && apiUrl.isNotBlank() && modelId.isNotBlank(),
        ) { Text("Add provider") }
    }
}
