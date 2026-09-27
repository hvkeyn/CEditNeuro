package com.hvkeyn.ceditneuro.net

import java.nio.ByteBuffer

/** Builds the IPv4 packets written back into the capture tunnel. */
internal object PacketCodec {
    fun udp(src: ByteArray, dst: ByteArray, sport: Int, dport: Int, payload: ByteArray, length: Int): ByteArray {
        val packet = ByteArray(20 + 8 + length)
        ipv4(packet, 17, 8 + length, src, dst)
        packet[20] = (sport shr 8).toByte()
        packet[21] = sport.toByte()
        packet[22] = (dport shr 8).toByte()
        packet[23] = dport.toByte()
        packet[24] = ((8 + length) shr 8).toByte()
        packet[25] = (8 + length).toByte()
        if (length > 0) payload.copyInto(packet, 28, 0, length)
        val sum = checksum(pseudo(src, dst, 17, packet, 20, 8 + length), 0, 12 + 8 + length)
        packet[26] = (sum shr 8).toByte()
        packet[27] = sum.toByte()
        return packet
    }

    fun tcp(
        src: ByteArray,
        dst: ByteArray,
        sport: Int,
        dport: Int,
        seq: Long,
        ack: Long,
        flags: Int,
        payload: ByteArray,
        length: Int,
    ): ByteArray {
        val packet = ByteArray(20 + 20 + length)
        ipv4(packet, 6, 20 + length, src, dst)
        packet[20] = (sport shr 8).toByte()
        packet[21] = sport.toByte()
        packet[22] = (dport shr 8).toByte()
        packet[23] = dport.toByte()
        put32(packet, 24, seq)
        put32(packet, 28, ack)
        packet[32] = 0x50
        packet[33] = flags.toByte()
        packet[34] = 0xff.toByte()
        packet[35] = 0xff.toByte()
        if (length > 0) payload.copyInto(packet, 40, 0, length)
        val sum = checksum(pseudo(src, dst, 6, packet, 20, 20 + length), 0, 12 + 20 + length)
        packet[36] = (sum shr 8).toByte()
        packet[37] = sum.toByte()
        return packet
    }

    private fun ipv4(packet: ByteArray, proto: Int, rest: Int, src: ByteArray, dst: ByteArray) {
        packet[0] = 0x45
        val total = 20 + rest
        packet[2] = (total shr 8).toByte()
        packet[3] = total.toByte()
        packet[8] = 64
        packet[9] = proto.toByte()
        src.copyInto(packet, 12, 0, 4)
        dst.copyInto(packet, 16, 0, 4)
        val sum = checksum(packet, 0, 20)
        packet[10] = (sum shr 8).toByte()
        packet[11] = sum.toByte()
    }

    private fun pseudo(src: ByteArray, dst: ByteArray, proto: Int, packet: ByteArray, at: Int, length: Int): ByteArray {
        val buf = ByteArray(12 + length)
        src.copyInto(buf, 0, 0, 4)
        dst.copyInto(buf, 4, 0, 4)
        buf[9] = proto.toByte()
        buf[10] = (length shr 8).toByte()
        buf[11] = length.toByte()
        packet.copyInto(buf, 12, at, at + length)
        return buf
    }

    private fun put32(packet: ByteArray, at: Int, value: Long) {
        val bits = value and 0xffffffffL
        packet[at] = (bits shr 24).toByte()
        packet[at + 1] = (bits shr 16).toByte()
        packet[at + 2] = (bits shr 8).toByte()
        packet[at + 3] = bits.toByte()
    }

    fun checksum(buf: ByteArray, offset: Int, length: Int): Int {
        var sum = 0
        var index = offset
        var left = length
        while (left > 1) {
            sum += ((buf[index].toInt() and 0xff) shl 8) or (buf[index + 1].toInt() and 0xff)
            index += 2
            left -= 2
        }
        if (left == 1) sum += (buf[index].toInt() and 0xff) shl 8
        while (sum shr 16 != 0) sum = (sum and 0xffff) + (sum shr 16)
        return sum.inv() and 0xffff
    }

    fun address(bytes: ByteArray, at: Int): ByteArray = bytes.copyOfRange(at, at + 4)

    fun u16(bytes: ByteArray, at: Int): Int =
        ((bytes[at].toInt() and 0xff) shl 8) or (bytes[at + 1].toInt() and 0xff)

    fun u32(bytes: ByteArray, at: Int): Long =
        ByteBuffer.wrap(bytes, at, 4).int.toLong() and 0xffffffffL
}
