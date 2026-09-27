package com.hvkeyn.ceditneuro.tools

import android.content.Context
import android.content.Intent
import android.net.VpnService
import com.hvkeyn.ceditneuro.MainActivity
import com.hvkeyn.ceditneuro.net.PacketCaptureHub
import com.hvkeyn.ceditneuro.net.PacketCaptureService
import com.hvkeyn.ceditneuro.net.PacketDump
import com.hvkeyn.ceditneuro.workspace.Workspace
import kotlinx.serialization.json.JsonObject
import java.io.File

class CaptureDumpTool(private val context: Context, private val workspace: Workspace) : Tool {
    override val name = "capture_dump"
    override val description =
        "Record this phone's public IPv4 packets into a pcap in the project, then summarize hosts and ports. " +
            "Shows a notification and asks for the VPN once. Stops by itself within seconds (5 to 60). " +
            "Private addresses stay off the recording. Does not print payloads, cookies, or passwords. " +
            "Does not record another device."
    override val parameters = objectSchema(
        properties = mapOf(
            "seconds" to intProp("How long to record. Default 12. Maximum 60."),
            "path" to stringProp("Project path of the pcap. Default research/capture.pcap."),
        ),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val seconds = (args.intArg("seconds") ?: 12).coerceIn(5, 60)
        val path = args.stringArg("path")?.trim().orEmpty().ifBlank { "research/capture.pcap" }
        val file = runCatching { workspace.resolve(path) }.getOrElse {
            return ToolResult.error(it.message ?: "That path is closed.")
        }
        if (!file.name.endsWith(".pcap")) return ToolResult.error("The dump file must end in .pcap.")
        val consent = VpnService.prepare(context)
        if (consent != null) {
            val open = Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra("cedit_consent", true)
            val opened = runCatching { context.startActivity(open) }
            if (opened.isFailure) return ToolResult.error("The VPN prompt could not be opened.")
            return ToolResult.error("The system VPN prompt is open. Accept it, then call capture_dump again.")
        }
        if (!PacketCaptureHub.begin()) return ToolResult.error("A recording is already running.")
        file.parentFile?.mkdirs()
        val started = runCatching { PacketCaptureService.start(context, file.absolutePath, seconds) }
        if (started.isFailure) {
            PacketCaptureHub.finish("The recording did not start.")
            return ToolResult.error("The recording did not start.")
        }
        val text = PacketCaptureHub.await(seconds)
        return if (text.startsWith("The ") || text.startsWith("No ") || text.startsWith("A ")) {
            ToolResult.error(text)
        } else {
            ToolResult.ok(text)
        }
    }
}

class ReadDumpTool(private val workspace: Workspace) : Tool {
    override val name = "read_dump"
    override val description =
        "Summarize a pcap: hosts, ports, DNS names, and TLS names. Does not print payloads, cookies, or passwords."
    override val parameters = objectSchema(
        properties = mapOf("path" to stringProp("Path of the pcap in the project or an absolute path.")),
        required = listOf("path"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val path = args.stringArg("path").orEmpty()
        val file = runCatching { workspace.resolve(path) }.getOrElse {
            return ToolResult.error(it.message ?: "That path is closed.")
        }
        if (!file.isFile) return ToolResult.error("No dump at that path.")
        return ToolResult.ok(PacketDump.summarize(file) + "\nfile=" + file.absolutePath + " bytes=" + file.length())
    }
}
