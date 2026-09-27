package com.hvkeyn.ceditneuro.net

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class PacketDumpTest {
    @Test
    fun summaryNamesTheDnsQueryAndHidesTheCookie() {
        val dir = kotlin.io.path.createTempDirectory("pcap").toFile()
        try {
            val file = File(dir, "capture.pcap")
            RandomAccessFile(file, "rw").use { out ->
                PacketDump.writeHeader(out, PacketDump.LINK_ETHERNET)
                PacketDump.append(out, ethernet(0x0806, ByteArray(28)), 14 + 28, 1_000)
                PacketDump.append(out, ethernet(0x0800, dnsQuery()), 14 + dnsQuery().size, 2_000)
                val http = "GET / HTTP/1.1\r\nCookie: supersecretvalue\r\n".toByteArray()
                PacketDump.append(out, ethernet(0x0800, tcp(http)), 14 + tcp(http).size, 3_000)
            }
            val text = PacketDump.summarize(file)
            assertTrue(text.contains("packets=3"))
            assertTrue(text.contains("arp=1"))
            assertTrue(text.contains("example.com"))
            assertTrue(text.contains("203.0.113.10:12345 -> 198.51.100.20:53"))
            assertTrue(text.contains("syn"))
            assertFalse(text.contains("supersecretvalue"))
            assertFalse(text.contains("Cookie"))
            assertTrue(text.contains("cleartext flows: tcp 198.51.100.20:80 (http)"))
            assertTrue(text.contains("secret fields in cleartext: cookie header x1 (values not printed)"))
            assertTrue(text.contains("plain dns queries: 1, names 1"))
            assertTrue(text.contains("tcp calls answered: 0 of 1"))
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun auditCountsAnAnsweredCall() {
        val dir = kotlin.io.path.createTempDirectory("pcap").toFile()
        try {
            val file = File(dir, "capture.pcap")
            val phone = byteArrayOf(203.toByte(), 0.toByte(), 113.toByte(), 2.toByte())
            val site = byteArrayOf(198.toByte(), 51.toByte(), 100.toByte(), 7.toByte())
            RandomAccessFile(file, "rw").use { out ->
                PacketDump.writeHeader(out, PacketDump.LINK_RAW)
                val syn = PacketCodec.tcp(phone, site, 40000, 443, 1L, 0L, 0x02, ByteArray(0), 0)
                val synAck = PacketCodec.tcp(site, phone, 443, 40000, 9L, 2L, 0x12, ByteArray(0), 0)
                val lost = PacketCodec.tcp(phone, site, 40001, 8443, 1L, 0L, 0x02, ByteArray(0), 0)
                PacketDump.append(out, syn, syn.size, 1_000)
                PacketDump.append(out, synAck, synAck.size, 1_010)
                PacketDump.append(out, lost, lost.size, 1_020)
            }
            val text = PacketDump.summarize(file)
            assertTrue(text.contains("tcp calls answered: 1 of 2"))
            assertTrue(text.contains("tcp calls with no answer: 198.51.100.7:8443"))
            assertTrue(text.contains("cleartext flows: none"))
            assertTrue(text.contains("secret fields in cleartext: none seen"))
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun ethernet(type: Int, payload: ByteArray): ByteArray {
        val frame = ByteArray(14 + payload.size)
        frame[12] = (type shr 8).toByte()
        frame[13] = type.toByte()
        payload.copyInto(frame, 14)
        return frame
    }

    private fun dnsQuery(): ByteArray {
        val name = byteArrayOf(
            7.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), 'a'.code.toByte(), 'm'.code.toByte(),
            'p'.code.toByte(), 'l'.code.toByte(), 'e'.code.toByte(),
            3.toByte(), 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(), 0.toByte(),
        )
        val payload = ByteArray(12 + name.size + 4)
        payload[0] = 0
        payload[1] = 1
        payload[5] = 1
        name.copyInto(payload, 12)
        val at = 12 + name.size
        payload[at] = 0
        payload[at + 1] = 1
        payload[at + 2] = 0
        payload[at + 3] = 1
        return udp(payload, 53)
    }

    private fun udp(payload: ByteArray, dport: Int): ByteArray =
        PacketCodec.udp(
            byteArrayOf(203.toByte(), 0.toByte(), 113.toByte(), 10.toByte()),
            byteArrayOf(198.toByte(), 51.toByte(), 100.toByte(), 20.toByte()),
            12345,
            dport,
            payload,
            payload.size,
        )

    private fun tcp(payload: ByteArray): ByteArray =
        PacketCodec.tcp(
            byteArrayOf(203.toByte(), 0.toByte(), 113.toByte(), 10.toByte()),
            byteArrayOf(198.toByte(), 51.toByte(), 100.toByte(), 20.toByte()),
            12345,
            80,
            1L,
            0L,
            0x02,
            payload,
            payload.size,
        )
}
