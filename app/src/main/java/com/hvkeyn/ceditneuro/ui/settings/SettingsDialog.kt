package com.hvkeyn.ceditneuro.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.hvkeyn.ceditneuro.data.AgentSettings
import com.hvkeyn.ceditneuro.data.CatalogModel
import com.hvkeyn.ceditneuro.data.ModelProvider
import com.hvkeyn.ceditneuro.data.RemoteServer

@Composable
fun SettingsDialog(
    settings: AgentSettings,
    onSettingsChange: ((AgentSettings) -> AgentSettings) -> Unit,
    onTestRemote: (RemoteServer) -> Unit,
    versionName: String,
    onCheckUpdate: () -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by remember(settings) { mutableStateOf(settings) }

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
                Text(
                    text = "Same idea as Zed: each provider has an API URL and a list of models. " +
                        "API keys stay on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
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

                    if (draft.model.supportsReasoning) {
                        Text("Reasoning", style = MaterialTheme.typography.titleSmall)
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

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Agent network", style = MaterialTheme.typography.labelLarge)
                            Text(
                                text = "Let the agent download files and install modules.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = draft.networkEnabled,
                            onCheckedChange = { draft = draft.copy(networkEnabled = it) },
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Run installed programs", style = MaterialTheme.typography.labelLarge)
                            Text(
                                text = "Lets the agent install and start compilers inside this app. Asked once if left unset.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = draft.execAllowed == true,
                            onCheckedChange = { draft = draft.copy(execAllowed = it) },
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto-approve edits", style = MaterialTheme.typography.labelLarge)
                            Text(
                                text = "Reserved for the diff review flow; edits already apply immediately.",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(
                            checked = draft.autoApproveEdits,
                            onCheckedChange = { draft = draft.copy(autoApproveEdits = it) },
                        )
                    }

                    RemoteSettingsSection(
                        draft = draft,
                        onDraft = { draft = it },
                        onTest = onTestRemote,
                    )
                }

                HorizontalDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Version $versionName",
                        style = MaterialTheme.typography.bodyMedium,
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
