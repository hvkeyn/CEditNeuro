package com.hvkeyn.ceditneuro.agent.deepseek.web

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * The proof of work chat.deepseek.com asks before a completion ("DeepSeekHashV1").
 * It is SHA3-256 with the first of the 24 Keccak rounds left out. The answer is the
 * nonce whose hash of `salt_expireAt_nonce` equals the challenge.
 */
object DeepSeekPow {
    @OptIn(ExperimentalUnsignedTypes::class)
    private val RC = ulongArrayOf(
        0x0000000000000001uL, 0x0000000000008082uL, 0x800000000000808AuL, 0x8000000080008000uL,
        0x000000000000808BuL, 0x0000000080000001uL, 0x8000000080008081uL, 0x8000000000008009uL,
        0x000000000000008AuL, 0x0000000000000088uL, 0x0000000080008009uL, 0x000000008000000AuL,
        0x000000008000808BuL, 0x800000000000008BuL, 0x8000000000008089uL, 0x8000000000008003uL,
        0x8000000000008002uL, 0x8000000000000080uL, 0x000000000000800AuL, 0x800000008000000AuL,
        0x8000000080008081uL, 0x8000000000008080uL, 0x0000000080000001uL, 0x8000000080008008uL,
    ).map { it.toLong() }.toLongArray()
    private val ROT = intArrayOf(
        0, 1, 62, 28, 27,
        36, 44, 6, 55, 20,
        3, 10, 43, 25, 39,
        41, 45, 15, 21, 8,
        18, 2, 61, 56, 14,
    )
    private const val RATE = 136

    /** Lowercase hex of the 32-byte digest. */
    fun hash(data: ByteArray): String {
        val state = LongArray(25)
        absorb(state, data)
        return hex(state)
    }

    /** The nonce in [0, difficulty) whose hash matches, or null. */
    fun solve(challenge: String, prefix: String, difficulty: Long): Long? {
        val target = challenge.lowercase()
        val head = prefix.toByteArray(Charsets.UTF_8)
        val state = LongArray(25)
        val buffer = ByteArray(head.size + 20)
        head.copyInto(buffer)
        for (nonce in 0 until difficulty) {
            val digits = nonce.toString()
            for (i in digits.indices) buffer[head.size + i] = digits[i].code.toByte()
            state.fill(0L)
            absorb(state, buffer, head.size + digits.length)
            if (matches(state, target)) return nonce
        }
        return null
    }

    /** The base64 value of the x-ds-pow-response header for one challenge object. */
    fun header(challenge: JsonObject): String {
        fun text(name: String) = (challenge[name] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val difficulty = (challenge["difficulty"] as? JsonPrimitive)?.let { it.longOrNull ?: it.doubleOrNull?.toLong() } ?: 144_000L
        val prefix = text("salt") + "_" + text("expire_at") + "_"
        val answer = solve(text("challenge"), prefix, difficulty)
            ?: throw IllegalStateException("The DeepSeek proof of work had no answer; the challenge may have expired.")
        val payload = buildJsonObject {
            put("algorithm", text("algorithm"))
            put("challenge", text("challenge"))
            put("salt", text("salt"))
            put("answer", answer)
            put("signature", text("signature"))
            put("target_path", text("target_path"))
        }
        return java.util.Base64.getEncoder().encodeToString(payload.toString().toByteArray(Charsets.UTF_8))
    }

    private fun absorb(state: LongArray, data: ByteArray, length: Int = data.size) {
        var offset = 0
        while (length - offset >= RATE) {
            xorBlock(state, data, offset, RATE)
            permute(state)
            offset += RATE
        }
        val last = ByteArray(RATE)
        data.copyInto(last, 0, offset, length)
        last[length - offset] = (last[length - offset].toInt() xor 0x06).toByte()
        last[RATE - 1] = (last[RATE - 1].toInt() xor 0x80).toByte()
        xorBlock(state, last, 0, RATE)
        permute(state)
    }

    private fun xorBlock(state: LongArray, data: ByteArray, offset: Int, size: Int) {
        for (lane in 0 until size / 8) {
            var value = 0L
            for (b in 7 downTo 0) value = (value shl 8) or (data[offset + lane * 8 + b].toLong() and 0xFF)
            state[lane] = state[lane] xor value
        }
    }

    private fun permute(a: LongArray) {
        val c = LongArray(5)
        val b = LongArray(25)
        for (round in 1 until 24) {
            for (x in 0 until 5) c[x] = a[x] xor a[x + 5] xor a[x + 10] xor a[x + 15] xor a[x + 20]
            for (x in 0 until 5) {
                val d = c[(x + 4) % 5] xor java.lang.Long.rotateLeft(c[(x + 1) % 5], 1)
                for (y in 0 until 5) a[x + 5 * y] = a[x + 5 * y] xor d
            }
            for (x in 0 until 5) for (y in 0 until 5) {
                b[y + 5 * ((2 * x + 3 * y) % 5)] = java.lang.Long.rotateLeft(a[x + 5 * y], ROT[x + 5 * y])
            }
            for (x in 0 until 5) for (y in 0 until 5) {
                a[x + 5 * y] = b[x + 5 * y] xor (b[(x + 1) % 5 + 5 * y].inv() and b[(x + 2) % 5 + 5 * y])
            }
            a[0] = a[0] xor RC[round]
        }
    }

    private fun matches(state: LongArray, target: String): Boolean {
        if (target.length != 64) return false
        for (i in 0 until 32) {
            val byte = ((state[i / 8] ushr (8 * (i % 8))) and 0xFF).toInt()
            if (HEX[byte shr 4] != target[2 * i] || HEX[byte and 15] != target[2 * i + 1]) return false
        }
        return true
    }

    private fun hex(state: LongArray): String = buildString(64) {
        for (i in 0 until 32) {
            val byte = ((state[i / 8] ushr (8 * (i % 8))) and 0xFF).toInt()
            append(HEX[byte shr 4]).append(HEX[byte and 15])
        }
    }

    private const val HEX = "0123456789abcdef"
}
