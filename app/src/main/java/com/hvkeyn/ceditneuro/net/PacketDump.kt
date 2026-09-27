package com.hvkeyn.ceditneuro.net

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * A pcap of IP packets and a short reading of it.
 * The reading names hosts and ports. It does not print payloads.
 */
object PacketDump {
    const val LINK_ETHERNET = 1
    const val LINK_RAW = 101
    const val SNAP = 256

    fun writeHeader(out: RandomAccessFile, linkType: Int) {
        val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
        header.putInt(0xa1b2c3d4.toInt())
        header.putShort(2)
        header.putShort(4)
        header.putInt(0)
        header.putInt(0)
        header.putInt(SNAP)
        header.putInt(linkType)
        out.write(header.array())
    }

    fun append(out: RandomAccessFile, packet: ByteArray, length: Int, timeMs: Long) {
        val kept = length.coerceAtMost(SNAP).coerceAtLeast(0)
        val record = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN)
        record.putInt((timeMs / 1000).toInt())
        record.putInt(((timeMs % 1000) * 1000).toInt())
        record.putInt(kept)
        record.putInt(length)
        out.write(record.array())
        if (kept > 0) out.write(packet, 0, kept)
    }

    fun summarize(file: File): String {
        if (!file.isFile || file.length() < 24) return "No dump to read."
        RandomAccessFile(file, "r").use { input ->
            val fileHeader = ByteArray(24)
            if (input.read(fileHeader) != 24) return "The dump header is short."
            val header = ByteBuffer.wrap(fileHeader)
            val magic = header.int
            val order = when (magic) {
                0xa1b2c3d4.toInt() -> ByteOrder.BIG_ENDIAN
                0xd4c3b2a1.toInt() -> ByteOrder.LITTLE_ENDIAN
                else -> return "This file is not a pcap."
            }
            header.order(order)
            header.position(20)
            val link = header.int
            val flows = LinkedHashMap<String, Flow>()
            val names = LinkedHashSet<String>()
            val audit = Audit()
            var packets = 0
            var arp = 0
            var other = 0
            val record = ByteArray(16)
            while (input.read(record) == 16 && packets < 2_000) {
                val view = ByteBuffer.wrap(record).order(order)
                view.int
                view.int
                val kept = view.int
                view.int
                if (kept < 0 || kept > 1_000_000) break
                val body = ByteArray(kept)
                if (kept > 0 && input.read(body) != kept) break
                packets++
                when (val kind = classify(body, link)) {
                    is Kind.Ip -> {
                        val key = "${kind.proto} ${kind.src}:${kind.sport} -> ${kind.dst}:${kind.dport}"
                        val flow = flows.getOrPut(key) { Flow(key, kind.ttl, kind.syn, kind.ack) }
                        flow.ttl = kind.ttl
                        flow.syn = flow.syn || kind.syn
                        flow.ack = flow.ack || kind.ack
                        if (kind.note.isNotBlank()) names += kind.note
                        audit.add(kind)
                    }
                    Kind.Arp -> arp++
                    Kind.Skip -> other++
                }
            }
            return buildString {
                append("packets=").append(packets)
                append(" flows=").append(flows.size)
                append(" arp=").append(arp)
                if (other > 0) append(" other=").append(other)
                append('\n')
                flows.values.take(40).forEach { flow ->
                    append(flow.key)
                    append(" ttl=").append(flow.ttl)
                    if (flow.syn) append(" syn")
                    if (flow.ack) append(" ack")
                    append('\n')
                }
                if (flows.size > 40) append("... ").append(flows.size - 40).append(" more flows\n")
                names.take(20).forEach { append("name ").append(it).append('\n') }
                append(audit.report())
                append("Payloads are not printed.")
            }
        }
    }

    private class Flow(val key: String, var ttl: Int, var syn: Boolean, var ack: Boolean)

    /** Cleartext ports: what crosses them can be read by anyone on the path. */
    private val CLEAR = mapOf(21 to "ftp", 23 to "telnet", 25 to "smtp", 80 to "http", 110 to "pop3", 143 to "imap", 8080 to "http")

    private class Audit {
        val called = LinkedHashSet<String>()
        val answered = HashSet<String>()
        val clear = LinkedHashSet<String>()
        val secrets = LinkedHashMap<String, Int>()
        val dnsNames = LinkedHashSet<String>()
        var plainDns = 0
        val quic = LinkedHashSet<String>()

        fun add(kind: Kind.Ip) {
            val out = "${kind.dst}:${kind.dport}"
            val back = "${kind.src}:${kind.sport}"
            if (kind.proto == "tcp") {
                if (kind.syn && !kind.ack) called += out
                if (kind.syn && kind.ack) answered += back
                CLEAR[kind.dport]?.let { clear += "tcp $out (${it})" }
                if (kind.secret.isNotEmpty()) secrets[kind.secret] = (secrets[kind.secret] ?: 0) + 1
            }
            if (kind.proto == "udp") {
                if (kind.dport == 53) {
                    plainDns++
                    if (kind.note.isNotBlank()) dnsNames += kind.note
                }
                if (kind.dport == 443) quic += out
            }
        }

        fun report(): String = buildString {
            append("audit\n")
            val confirmed = called.count { it in answered }
            append("tcp calls answered: ").append(confirmed).append(" of ").append(called.size).append('\n')
            val silent = called.filter { it !in answered }
            if (silent.isNotEmpty()) append("tcp calls with no answer: ").append(silent.take(10).joinToString(", ")).append('\n')
            append("cleartext flows: ").append(if (clear.isEmpty()) "none" else clear.take(10).joinToString(", ")).append('\n')
            append("plain dns queries: ").append(plainDns).append(", names ").append(dnsNames.size).append('\n')
            append("quic flows: ").append(quic.size).append('\n')
            if (secrets.isEmpty()) {
                append("secret fields in cleartext: none seen\n")
            } else {
                append("secret fields in cleartext: ")
                append(secrets.entries.joinToString(", ") { "${it.key} x${it.value}" })
                append(" (values not printed)\n")
            }
        }
    }

    private sealed class Kind {
        data class Ip(
            val proto: String,
            val src: String,
            val sport: Int,
            val dst: String,
            val dport: Int,
            val ttl: Int,
            val syn: Boolean,
            val ack: Boolean,
            val note: String,
            val secret: String = "",
        ) : Kind()
        data object Arp : Kind()
        data object Skip : Kind()
    }

    private fun classify(body: ByteArray, link: Int): Kind {
        var offset = 0
        when (link) {
            LINK_ETHERNET -> {
                if (body.size < 14) return Kind.Skip
                var type = u16(body, 12)
                offset = 14
                if (type == 0x8100 && body.size >= 18) {
                    type = u16(body, 16)
                    offset = 18
                }
                if (type == 0x0806) return Kind.Arp
                if (type != 0x0800) return Kind.Skip
            }
            LINK_RAW, 228 -> offset = 0
            else -> return Kind.Skip
        }
        if (body.size < offset + 20) return Kind.Skip
        val version = (body[offset].toInt() ushr 4) and 0xf
        if (version != 4) return Kind.Skip
        val ihl = (body[offset].toInt() and 0xf) * 4
        if (ihl < 20 || body.size < offset + ihl) return Kind.Skip
        val ttl = body[offset + 8].toInt() and 0xff
        val proto = body[offset + 9].toInt() and 0xff
        val src = ip(body, offset + 12)
        val dst = ip(body, offset + 16)
        val at = offset + ihl
        return when (proto) {
            6 -> tcp(body, at, src, dst, ttl)
            17 -> udp(body, at, src, dst, ttl)
            1 -> Kind.Ip("icmp", src, 0, dst, 0, ttl, syn = false, ack = false, note = "")
            else -> Kind.Ip("ip$proto", src, 0, dst, 0, ttl, syn = false, ack = false, note = "")
        }
    }

    private fun tcp(body: ByteArray, at: Int, src: String, dst: String, ttl: Int): Kind {
        if (body.size < at + 20) return Kind.Skip
        val sport = u16(body, at)
        val dport = u16(body, at + 2)
        val flags = body[at + 13].toInt() and 0xff
        val header = ((body[at + 12].toInt() ushr 4) and 0xf) * 4
        val note = if (dport == 443) sni(body, at + header) else ""
        val secret = if (dport in CLEAR || sport in CLEAR) secretKind(body, at + header) else ""
        return Kind.Ip(
            "tcp", src, sport, dst, dport, ttl,
            syn = flags and 0x02 != 0, ack = flags and 0x10 != 0, note = note, secret = secret,
        )
    }

    /** Names the kind of secret a cleartext payload carries. The value itself is never returned. */
    private fun secretKind(body: ByteArray, at: Int): String {
        if (at >= body.size) return ""
        val text = String(body, at, body.size - at, Charsets.ISO_8859_1).lowercase()
        return when {
            "authorization:" in text -> "authorization header"
            "cookie:" in text -> "cookie header"
            "password=" in text || "passwd=" in text || "pass=" in text -> "password field"
            text.startsWith("pass ") || "\npass " in text -> "login password"
            else -> ""
        }
    }

    private fun udp(body: ByteArray, at: Int, src: String, dst: String, ttl: Int): Kind {
        if (body.size < at + 8) return Kind.Skip
        val sport = u16(body, at)
        val dport = u16(body, at + 2)
        val note = if (dport == 53 || sport == 53) dnsName(body, at + 8) else ""
        return Kind.Ip("udp", src, sport, dst, dport, ttl, syn = false, ack = false, note = note)
    }

    private fun dnsName(body: ByteArray, at: Int): String {
        if (body.size < at + 12) return ""
        var index = at + 12
        val name = StringBuilder()
        var labels = 0
        while (index < body.size && labels < 8) {
            val len = body[index].toInt() and 0xff
            if (len == 0) break
            if (len and 0xc0 != 0 || index + 1 + len > body.size) break
            if (name.isNotEmpty()) name.append('.')
            name.append(String(body, index + 1, len, Charsets.US_ASCII))
            index += 1 + len
            labels++
        }
        val text = name.toString()
        return if (text.length in 1..80 && text.all { it.isLetterOrDigit() || it == '.' || it == '-' }) text else ""
    }

    private fun sni(body: ByteArray, at: Int): String {
        if (body.size < at + 5 || body[at].toInt() and 0xff != 22) return ""
        var index = at + 5
        if (index >= body.size || body[index].toInt() and 0xff != 1) return ""
        index += 4 + 2 + 32
        if (index >= body.size) return ""
        val session = body[index].toInt() and 0xff
        index += 1 + session
        if (index + 2 > body.size) return ""
        val ciphers = u16(body, index)
        index += 2 + ciphers
        if (index >= body.size) return ""
        val compression = body[index].toInt() and 0xff
        index += 1 + compression
        if (index + 2 > body.size) return ""
        val extEnd = index + 2 + u16(body, index)
        index += 2
        while (index + 4 <= body.size && index + 4 <= extEnd) {
            val type = u16(body, index)
            val len = u16(body, index + 2)
            index += 4
            if (index + len > body.size) break
            if (type == 0 && len >= 5) {
                val nameLen = u16(body, index + 3)
                val start = index + 5
                if (nameLen in 1..80 && start + nameLen <= body.size) {
                    val name = String(body, start, nameLen, Charsets.US_ASCII)
                    if (name.all { it.isLetterOrDigit() || it == '.' || it == '-' }) return name
                }
            }
            index += len
        }
        return ""
    }

    private fun u16(body: ByteArray, at: Int): Int =
        ((body[at].toInt() and 0xff) shl 8) or (body[at + 1].toInt() and 0xff)

    private fun ip(body: ByteArray, at: Int): String =
        "${body[at].toInt() and 0xff}.${body[at + 1].toInt() and 0xff}.${body[at + 2].toInt() and 0xff}.${body[at + 3].toInt() and 0xff}"
}
