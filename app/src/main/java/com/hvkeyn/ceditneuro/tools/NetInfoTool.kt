package com.hvkeyn.ceditneuro.tools

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import kotlinx.serialization.json.JsonObject
import java.net.NetworkInterface

/** Public network facts for this app. It does not read another app's VPN config. */
class NetInfoTool(private val context: Context) : Tool {
    override val name = "net_info"
    override val description =
        "Report this app's network: transports, whether a VPN transport is active, " +
            "link addresses visible to this app, and the active interface names. " +
            "It cannot read another app's logs, credentials, or private files."
    override val parameters = objectSchema(properties = emptyMap<String, kotlinx.serialization.json.JsonObject>())

    override suspend fun execute(args: JsonObject): ToolResult {
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork
        val caps = network?.let { manager.getNetworkCapabilities(it) }
        val lines = StringBuilder()
        if (caps == null) {
            lines.append("active network: none\n")
        } else {
            lines.append("vpn transport: ").append(caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)).append('\n')
            lines.append("wifi: ").append(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)).append('\n')
            lines.append("cellular: ").append(caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)).append('\n')
            lines.append("ethernet: ").append(caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)).append('\n')
        }
        val link = network?.let { manager.getLinkProperties(it) }
        link?.interfaceName?.let { lines.append("interface: ").append(it).append('\n') }
        link?.linkAddresses?.forEach { lines.append("address: ").append(it.address.hostAddress).append('\n') }
        lines.append("interfaces:\n")
        NetworkInterface.getNetworkInterfaces()?.toList().orEmpty().forEach { iface ->
            if (!iface.isUp) return@forEach
            val addrs = iface.inetAddresses.toList().mapNotNull { it.hostAddress }
            if (addrs.isNotEmpty()) {
                lines.append("  ").append(iface.name).append(' ').append(addrs.joinToString(", ")).append('\n')
            }
        }
        return ToolResult.ok(lines.toString().ifBlank { "No network details." })
    }
}
