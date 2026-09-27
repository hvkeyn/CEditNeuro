package com.hvkeyn.ceditneuro.tools

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.net.ConnectException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/**
 * Audits the network this phone is joined to. It only opens and closes TCP connections and asks the
 * standard discovery addresses who is there. It does not log in, send a payload, or leave this subnet.
 */
class NetAuditTool(
    private val context: Context,
    private val networkAllowed: () -> Boolean,
) : Tool {
    override val name = "net_audit"
    override val description =
        "Audit the network this phone is joined to: link, gateway, DNS, private DNS, proxy, open service ports " +
            "on the gateway, and devices that announce themselves (SSDP, mDNS). sweep=true also checks a few " +
            "service ports on each address of this phone's own Wi-Fi /24. Connect only: no login, no payload, " +
            "no other network. Ends with findings ranked high, medium, low."
    override val parameters = objectSchema(
        properties = mapOf(
            "sweep" to boolProp("Also check the phone's own /24 for hosts and open ports. Default false."),
            "seconds" to intProp("How long to listen for SSDP and mDNS answers. Default 3, 1 to 8."),
        ),
    )

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        if (!networkAllowed()) return@withContext ToolResult.error("Network access is off in settings.")
        val sweep = args.boolArg("sweep") ?: false
        val listen = (args.intArg("seconds") ?: 3).coerceIn(1, 8)
        val manager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = manager.activeNetwork ?: return@withContext ToolResult.error("No active network.")
        val caps = manager.getNetworkCapabilities(network)
        val link = manager.getLinkProperties(network)
        val findings = mutableListOf<Pair<String, String>>()
        val out = StringBuilder()

        val wifi = caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
        val ethernet = caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true
        val cellular = caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
        val vpn = caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        out.append("link\n")
        out.append("transport: ").append(
            listOfNotNull("wifi".takeIf { wifi }, "ethernet".takeIf { ethernet }, "cellular".takeIf { cellular }, "vpn".takeIf { vpn })
                .joinToString(", ").ifBlank { "unknown" },
        ).append('\n')
        val validated = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        out.append("internet validated: ").append(validated).append('\n')
        if (caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL) == true) {
            out.append("captive portal: yes\n")
            findings += "low" to "A sign-in page holds this network. Traffic before sign-in may be read by the portal."
        }
        if (!validated) findings += "low" to "The system did not confirm internet on this network."
        out.append("metered: ").append(caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) != true).append('\n')
        val v4 = link?.linkAddresses.orEmpty().firstOrNull { it.address is Inet4Address }
        link?.interfaceName?.let { out.append("interface: ").append(it).append('\n') }
        v4?.let { out.append("ipv4: ").append(it.address.hostAddress).append('/').append(it.prefixLength).append('\n') }
        val v6 = link?.linkAddresses.orEmpty().filter { it.address !is Inet4Address }
        if (v6.isNotEmpty()) {
            out.append("ipv6 addresses: ").append(v6.size)
                .append(" (global ").append(v6.count { !it.address.isLinkLocalAddress }).append(")\n")
        }
        val gateways = gateways(link)
        out.append("gateway: ").append(gateways.joinToString(", ") { it.hostAddress.orEmpty() }.ifBlank { "none" }).append('\n')
        val dns = link?.dnsServers.orEmpty()
        out.append("dns: ").append(dns.joinToString(", ") { it.hostAddress.orEmpty() }.ifBlank { "none" }).append('\n')
        if (Build.VERSION.SDK_INT >= 28 && link != null) {
            val active = link.isPrivateDnsActive
            out.append("private dns: ").append(if (active) "on" else "off")
            link.privateDnsServerName?.let { out.append(" (").append(it).append(')') }
            out.append('\n')
            if (!active) findings += "medium" to "Private DNS is off. Every site name this phone looks up can be read on the path."
        }
        if (Build.VERSION.SDK_INT >= 29 && link != null && link.mtu > 0) out.append("mtu: ").append(link.mtu).append('\n')
        link?.httpProxy?.let { proxy ->
            out.append("http proxy: set\n")
            if (proxy.host.isNotBlank()) findings += "medium" to "An HTTP proxy is set on this network. Cleartext web traffic passes through it."
        }

        val local = wifi || ethernet
        if (!local) {
            out.append("\nThe phone is not on Wi-Fi or Ethernet, so the gateway and devices are not probed.\n")
            return@withContext ToolResult.ok(finish(out, findings))
        }

        val gateway = gateways.firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
        if (gateway != null) {
            out.append("\ngateway ").append(gateway.hostAddress).append('\n')
            val open = probe(gateway, GATEWAY_PORTS, 600)
            out.append("open ports: ").append(if (open.isEmpty()) "none of " + GATEWAY_PORTS.keys.joinToString(",") else open.joinToString(", ") { "$it ${GATEWAY_PORTS[it]}" }).append('\n')
            if (23 in open) findings += "high" to "The gateway answers telnet (23). Its login crosses the network in cleartext."
            if (21 in open) findings += "high" to "The gateway answers ftp (21). Logins and files cross in cleartext."
            if (80 in open && 443 !in open && 8443 !in open) {
                findings += "medium" to "The gateway admin page is http only (80). Its password crosses the Wi-Fi in cleartext."
            }
            if (7547 in open) findings += "medium" to "The gateway answers TR-069 (7547) on the local side. Only the provider should reach it."
            if (22 in open) findings += "low" to "The gateway answers ssh (22). Check that its password is not the default."
        } else {
            out.append("\nNo private IPv4 gateway on this link.\n")
        }

        val ssdp = ssdp(listen)
        out.append("\nssdp answers: ").append(ssdp.size).append('\n')
        ssdp.entries.take(20).forEach { (host, server) -> out.append("  ").append(host).append(' ').append(server).append('\n') }
        if (gateway != null && ssdp.keys.any { it == gateway.hostAddress }) {
            findings += "medium" to "The gateway answers UPnP. Any device inside can ask it to open a port to the internet."
        }

        val selfAddress = v4?.address?.hostAddress
        val mdns = mdns(listen)
        out.append("mdns answers: ").append(mdns.size).append('\n')
        mdns.entries.take(20).forEach { (host, names) ->
            out.append("  ").append(host)
            if (host == selfAddress) out.append(" (this phone)")
            out.append(' ').append(names.take(4).joinToString(", ")).append('\n')
        }

        if (sweep) {
            val self = v4?.address as? Inet4Address
            val prefix = v4?.prefixLength ?: 0
            if (self == null || !self.isSiteLocalAddress || prefix < 16) {
                out.append("\nsweep skipped: the phone has no private IPv4 on a /16 or smaller network.\n")
            } else {
                val hosts = sweepHosts(self)
                out.append("\nsweep ").append(subnet(self)).append(".0/24: ").append(hosts.size).append(" hosts answered\n")
                if (prefix < 24) {
                    out.append("The network is /").append(prefix)
                        .append(". Only this phone's /24 was swept; devices elsewhere in it show only through SSDP and mDNS.\n")
                }
                out.append("A host that answers no listed port and refuses none is not listed.\n")
                hosts.entries.take(60).forEach { (host, open) ->
                    out.append("  ").append(host).append(' ')
                    out.append(if (open.isEmpty()) "alive, no listed port open" else open.joinToString(", ") { "$it ${HOST_PORTS[it]}" })
                    out.append('\n')
                    if (23 in open) findings += "high" to "$host answers telnet (23)."
                    if (445 in open) findings += "medium" to "$host shares files over SMB (445). Check that guest access is off."
                    if (554 in open) findings += "medium" to "$host serves RTSP video (554). A camera stream may be open without a password."
                    if (9100 in open) findings += "low" to "$host is a raw printer port (9100). Anyone on the Wi-Fi can print to it."
                }
            }
        }
        ToolResult.ok(finish(out, findings))
    }

    private fun finish(out: StringBuilder, findings: List<Pair<String, String>>): String {
        out.append("\nfindings\n")
        if (findings.isEmpty()) {
            out.append("none from these checks\n")
        } else {
            listOf("high", "medium", "low").forEach { level ->
                findings.filter { it.first == level }.distinct().forEach { out.append(level).append(": ").append(it.second).append('\n') }
            }
        }
        out.append("Only this phone's own network was checked. No login or payload was sent.")
        return out.toString()
    }

    private fun gateways(link: LinkProperties?): List<InetAddress> =
        link?.routes.orEmpty().filter { it.isDefaultRoute && it.gateway != null && !it.gateway!!.isAnyLocalAddress }
            .mapNotNull { it.gateway }.distinct()

    private suspend fun probe(host: InetAddress, ports: Map<Int, String>, timeout: Int): List<Int> = coroutineScope {
        ports.keys.map { port -> async { port.takeIf { connect(host, it, timeout) == Reply.OPEN } } }.awaitAll().filterNotNull()
    }

    private enum class Reply { OPEN, REFUSED, SILENT }

    private fun connect(host: InetAddress, port: Int, timeout: Int): Reply = try {
        Socket().use { it.connect(InetSocketAddress(host, port), timeout) }
        Reply.OPEN
    } catch (error: ConnectException) {
        val text = error.message.orEmpty()
        if ("ECONNREFUSED" in text || "refused" in text.lowercase()) Reply.REFUSED else Reply.SILENT
    } catch (_: SocketTimeoutException) {
        Reply.SILENT
    } catch (_: Exception) {
        Reply.SILENT
    }

    private suspend fun sweepHosts(self: Inet4Address): Map<String, List<Int>> = coroutineScope {
        val base = subnet(self)
        val gate = Semaphore(48)
        (1..254).map { last -> "$base.$last" }.filter { it != self.hostAddress }.map { address ->
            async {
                gate.withPermit {
                    val host = InetAddress.getByName(address)
                    val replies = HOST_PORTS.keys.associateWith { connect(host, it, 300) }
                    val alive = replies.values.any { it != Reply.SILENT }
                    if (alive) address to replies.filterValues { it == Reply.OPEN }.keys.sorted() else null
                }
            }
        }.awaitAll().filterNotNull().toMap()
    }

    private fun subnet(self: Inet4Address): String = self.hostAddress.orEmpty().substringBeforeLast('.')

    /** Who answers the UPnP search on this link. The reply's SERVER line names the device software. */
    private fun ssdp(seconds: Int): Map<String, String> {
        val found = LinkedHashMap<String, String>()
        runCatching {
            DatagramSocket().use { socket ->
                socket.soTimeout = 400
                val ask = ("M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\n" +
                    "MX: 2\r\nST: ssdp:all\r\n\r\n").toByteArray()
                socket.send(DatagramPacket(ask, ask.size, InetAddress.getByName("239.255.255.250"), 1900))
                val until = System.currentTimeMillis() + seconds * 1000L
                val buf = ByteArray(2048)
                while (System.currentTimeMillis() < until) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val host = packet.address.hostAddress ?: continue
                    if (host in found) continue
                    val text = String(buf, 0, packet.length, Charsets.ISO_8859_1)
                    val server = text.lineSequence().firstOrNull { it.startsWith("SERVER:", ignoreCase = true) }
                        ?.substringAfter(':')?.trim()?.take(80).orEmpty()
                    found[host] = server.ifBlank { "(no server line)" }.filter { it.code in 32..126 }
                }
            }
        }
        return found
    }

    /** Asks for the service list with the unicast-reply bit, so answers reach this socket. */
    private fun mdns(seconds: Int): Map<String, List<String>> {
        val found = LinkedHashMap<String, MutableList<String>>()
        runCatching {
            DatagramSocket().use { socket ->
                socket.soTimeout = 400
                val query = mdnsQuery("_services._dns-sd._udp.local")
                socket.send(DatagramPacket(query, query.size, InetAddress.getByName("224.0.0.251"), 5353))
                val until = System.currentTimeMillis() + seconds * 1000L
                val buf = ByteArray(4096)
                while (System.currentTimeMillis() < until) {
                    val packet = DatagramPacket(buf, buf.size)
                    try {
                        socket.receive(packet)
                    } catch (_: SocketTimeoutException) {
                        continue
                    }
                    val host = packet.address.hostAddress ?: continue
                    val names = found.getOrPut(host) { mutableListOf() }
                    ptrNames(buf, packet.length).forEach { if (it !in names) names += it }
                }
            }
        }
        return found
    }

    private fun mdnsQuery(name: String): ByteArray {
        val body = java.io.ByteArrayOutputStream()
        body.write(byteArrayOf(0, 0, 0, 0, 0, 1, 0, 0, 0, 0, 0, 0))
        name.split('.').forEach { label ->
            body.write(label.length)
            body.write(label.toByteArray(Charsets.US_ASCII))
        }
        body.write(0)
        body.write(byteArrayOf(0, 12, 0x80.toByte(), 1))
        return body.toByteArray()
    }

    /** Service type names from PTR answers. Instance names can carry a person's name, so they are cut. */
    private fun ptrNames(buf: ByteArray, length: Int): List<String> {
        if (length < 12) return emptyList()
        val questions = u16(buf, 4)
        val answers = u16(buf, 6) + u16(buf, 8) + u16(buf, 10)
        var at = 12
        repeat(questions) {
            at = skipName(buf, at, length) + 4
            if (at > length) return emptyList()
        }
        val names = mutableListOf<String>()
        repeat(answers.coerceAtMost(40)) {
            at = skipName(buf, at, length)
            if (at + 10 > length) return names
            val type = u16(buf, at)
            val rdLength = u16(buf, at + 8)
            val data = at + 10
            if (type == 12) {
                val service = readName(buf, data, length)
                    .split('.').dropWhile { !it.startsWith("_") }.joinToString(".")
                if (service.isNotBlank() && service.length <= 60) names += service
            }
            at = data + rdLength
        }
        return names
    }

    private fun skipName(buf: ByteArray, start: Int, length: Int): Int {
        var at = start
        while (at < length) {
            val len = buf[at].toInt() and 0xff
            if (len == 0) return at + 1
            if (len and 0xc0 == 0xc0) return at + 2
            at += 1 + len
        }
        return length
    }

    private fun readName(buf: ByteArray, start: Int, length: Int): String {
        val parts = mutableListOf<String>()
        var at = start
        var jumps = 0
        while (at < length && jumps < 8 && parts.size < 10) {
            val len = buf[at].toInt() and 0xff
            if (len == 0) break
            if (len and 0xc0 == 0xc0) {
                if (at + 1 >= length) break
                at = ((len and 0x3f) shl 8) or (buf[at + 1].toInt() and 0xff)
                jumps++
                continue
            }
            if (at + 1 + len > length) break
            parts += String(buf, at + 1, len, Charsets.US_ASCII).filter { it.code in 33..126 }
            at += 1 + len
        }
        return parts.joinToString(".")
    }

    private fun u16(buf: ByteArray, at: Int): Int = ((buf[at].toInt() and 0xff) shl 8) or (buf[at + 1].toInt() and 0xff)

    private companion object {
        val GATEWAY_PORTS = linkedMapOf(
            21 to "ftp", 22 to "ssh", 23 to "telnet", 53 to "dns", 80 to "http", 443 to "https",
            1900 to "upnp", 5000 to "upnp/admin", 7547 to "tr-069", 8080 to "http-alt", 8443 to "https-alt",
        )
        val HOST_PORTS = linkedMapOf(
            22 to "ssh", 23 to "telnet", 80 to "http", 443 to "https", 445 to "smb", 554 to "rtsp",
            8080 to "http-alt", 9100 to "printer", 62078 to "apple-sync",
        )
    }
}
