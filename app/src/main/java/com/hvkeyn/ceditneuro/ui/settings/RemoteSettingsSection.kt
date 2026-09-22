package com.hvkeyn.ceditneuro.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.hvkeyn.ceditneuro.data.AgentSettings
import com.hvkeyn.ceditneuro.data.RemoteServer

@Composable
fun RemoteSettingsSection(
    draft: AgentSettings,
    onDraft: (AgentSettings) -> Unit,
    onTest: (RemoteServer) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf("") }
    var protocol by rememberSaveable { mutableStateOf("sftp") }
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("22") }
    var username by rememberSaveable { mutableStateOf("") }
    var password by rememberSaveable { mutableStateOf("") }
    var startPath by rememberSaveable { mutableStateOf("/") }
    var webUrl by rememberSaveable { mutableStateOf("") }
    var editingId by rememberSaveable { mutableStateOf("") }
    var formError by rememberSaveable { mutableStateOf("") }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("Remote servers", style = MaterialTheme.typography.titleSmall)
        Text(
            text = "FTP, FTPS, or SFTP. The agent can list, edit, and upload files, then check the site URL. " +
                "FTP sends the password without encryption. SSH asks before trusting a new host.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        draft.remotes.forEach { server ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(
                    selected = draft.activeRemoteId == server.id,
                    onClick = { onDraft(draft.copy(activeRemoteId = server.id)) },
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(server.label(), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        text = "${server.protocol}://${server.host}:${server.port}",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { onTest(server) }) { Text("Test") }
                TextButton(onClick = {
                    editingId = server.id
                    name = server.name
                    protocol = server.protocol
                    host = server.host
                    port = server.port.toString()
                    username = server.username
                    password = server.password
                    startPath = server.startPath
                    webUrl = server.webUrl
                }) { Text("Edit") }
                TextButton(onClick = {
                    onDraft(
                        draft.copy(
                            remotes = draft.remotes.filterNot { it.id == server.id },
                            activeRemoteId = if (draft.activeRemoteId == server.id) "" else draft.activeRemoteId,
                        ),
                    )
                }) { Text("Remove") }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RemoteServer.PROTOCOLS.forEach { item ->
                FilterChip(
                    selected = protocol == item,
                    onClick = {
                        protocol = item
                        port = RemoteServer.defaultPort(item).toString()
                    },
                    label = { Text(item) },
                )
            }
        }
        OutlinedTextField(name, { name = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
        OutlinedTextField(host, { host = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Host") }, singleLine = true)
        OutlinedTextField(port, { port = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Port") }, singleLine = true)
        OutlinedTextField(username, { username = it }, modifier = Modifier.fillMaxWidth(), label = { Text("Username") }, singleLine = true)
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Password") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
        )
        OutlinedTextField(
            startPath,
            { startPath = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Start path") },
            singleLine = true,
        )
        OutlinedTextField(
            webUrl,
            { webUrl = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Site URL to check after upload") },
            singleLine = true,
        )
        if (formError.isNotBlank()) {
            Text(formError, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelSmall)
        }
        Row {
            TextButton(onClick = {
                val parsedPort = port.toIntOrNull()
                if (host.isBlank() || parsedPort == null || parsedPort !in 1..65535) {
                    formError = "Host and a port from 1 to 65535 are required."
                    return@TextButton
                }
                formError = ""
                val kept = draft.remotes.find { it.id == editingId }
                val server = RemoteServer(
                    id = editingId.ifBlank { java.util.UUID.randomUUID().toString() },
                    name = name.trim(),
                    protocol = protocol,
                    host = host.trim(),
                    port = parsedPort,
                    username = username,
                    password = password,
                    startPath = startPath.trim().ifBlank { "/" },
                    webUrl = webUrl.trim(),
                    trustedFingerprint = kept?.trustedFingerprint.orEmpty(),
                )
                val remotes = if (kept == null) draft.remotes + server else draft.remotes.map {
                    if (it.id == server.id) server else it
                }
                onDraft(draft.copy(remotes = remotes, activeRemoteId = server.id))
                editingId = ""
                name = ""
                password = ""
            }) { Text(if (editingId.isBlank()) "Add server" else "Save server") }
        }
    }
}
