package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.data.RemoteServer
import com.hvkeyn.ceditneuro.net.RemoteClient
import com.hvkeyn.ceditneuro.net.SpaceSync
import com.hvkeyn.ceditneuro.workspace.Workspace
import kotlinx.serialization.json.JsonObject

/** Pulls and pushes the open project through the shared remote folder. */
class SpaceSyncTool(
    private val workspace: Workspace,
    private val server: () -> RemoteServer?,
    private val client: RemoteClient,
    private val device: String,
) : Tool {
    override val name = "space_sync"
    override val description =
        "Sync this project with the shared remote folder used by the other phone. " +
            "Call it before editing a shared project and again after a batch of edits. " +
            "A file the other phone also changed is saved beside it with the suffix .from-peer. " +
            "Do not read the other phone's private files. This only transfers the shared folder."
    override val parameters = objectSchema(properties = emptyMap<String, kotlinx.serialization.json.JsonObject>())

    override suspend fun execute(args: JsonObject): ToolResult {
        val remote = server() ?: return ToolResult.error("No remote server. Both phones need the same server in Settings.")
        val note = runCatching { SpaceSync(client).sync(remote, workspace.root, device) }
            .getOrElse { error -> return ToolResult.error(error.message ?: "Sync failed.") }
        return ToolResult.ok(note)
    }
}
