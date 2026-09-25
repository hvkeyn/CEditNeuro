package com.hvkeyn.ceditneuro.net

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Outbound link to the beacon. The phone never logs into the server.
 * A shared code is the only key, and the beacon only forwards lines to the other phone.
 */
class BeaconClient(
    private val host: String = HOST,
    private val port: Int = PORT,
) {
    private val running = AtomicBoolean(false)
    private val outgoing = LinkedBlockingQueue<String>(32)
    private val generation = java.util.concurrent.atomic.AtomicInteger()
    @Volatile
    private var socket: Socket? = null
    @Volatile
    private var writerOut: BufferedWriter? = null
    @Volatile
    private var direct: InetSocketAddress? = null
    private var udp: DatagramSocket? = null

    fun start(
        code: String,
        device: String,
        onReady: () -> Unit = {},
        onDirect: (Boolean) -> Unit = {},
        onRoster: (String) -> Unit = {},
        onError: (String) -> Unit = {},
        onPeer: (name: String, text: String) -> Unit,
    ) {
        stop()
        val gen = generation.incrementAndGet()
        running.set(true)
        val punch = DatagramSocket()
        udp = punch
        Thread {
            val buffer = ByteArray(1400)
            while (running.get() && gen == generation.get()) {
                try {
                    val packet = DatagramPacket(buffer, buffer.size)
                    punch.soTimeout = 1_000
                    punch.receive(packet)
                    val text = String(packet.data, 0, packet.length, Charsets.UTF_8)
                    if (text.startsWith("Y ")) continue
                    direct = InetSocketAddress(packet.address, packet.port)
                    onDirect(true)
                    if (text.startsWith("M ")) {
                        val body = text.removePrefix("M ")
                        val name = body.substringBefore(' ')
                        onPeer(name, body.substringAfter(' ', ""))
                    } else {
                        punch.send(DatagramPacket("PUNCH".toByteArray(), 5, packet.address, packet.port))
                    }
                } catch (_: Exception) {
                }
            }
        }.also { it.isDaemon = true; it.name = "beacon-udp" }.start()
        Thread {
            while (running.get() && gen == generation.get()) {
                try {
                    val link = Socket()
                    link.connect(InetSocketAddress(host, port), 12_000)
                    link.soTimeout = 0
                    socket = link
                    val writer = BufferedWriter(OutputStreamWriter(link.getOutputStream(), Charsets.UTF_8))
                    writerOut = writer
                    val reader = BufferedReader(InputStreamReader(link.getInputStream(), Charsets.UTF_8))
                    writer.write("JOIN $code $device\n")
                    writer.flush()
                    runCatching { onReady() }
                    punch.send(datagram("PING $code $device", InetAddress.getByName(host), PORT))
                    val pump = Thread {
                        while (running.get() && !link.isClosed) {
                            val line = outgoing.poll() ?: run {
                                Thread.sleep(200)
                                null
                            } ?: continue
                            writer.write(line + "\n")
                            writer.flush()
                        }
                    }
                    pump.start()
                    while (running.get()) {
                        val line = reader.readLine() ?: break
                        if (line.startsWith("N ")) {
                            runCatching { onRoster(line.removePrefix("N ")) }
                            continue
                        }
                        if (line.startsWith("A ")) {
                            val bits = line.split(' ')
                            if (bits.size >= 4) poke(punch, code, bits[2], bits[3].toIntOrNull() ?: continue)
                        }
                        val parts = line.split(' ', limit = 3)
                        if (parts.size == 3 && parts[0] == "P") runCatching { onPeer(parts[1], parts[2]) }
                    }
                    pump.interrupt()
                } catch (error: Exception) {
                    if (gen == generation.get()) {
                        runCatching { onError(error.message ?: "Link failed") }
                        Thread.sleep(2_000)
                    }
                } finally {
                    runCatching { socket?.close() }
                    socket = null
                }
            }
        }.also { it.isDaemon = true; it.name = "beacon" }.start()
    }

    fun send(text: String) {
        val peer = direct
        if (peer != null && udp != null) {
            runCatching { udp?.send(datagram("M $text", peer.address, peer.port)) }
        } else {
            offer("T $text")
        }
    }

    fun sendFile(relative: String, bytes: ByteArray) {
        val encoded = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        val payload = "FILE $relative $encoded"
        val peer = direct
        if (peer != null && payload.length < 1100 && udp != null) {
            runCatching { udp?.send(datagram("M $payload", peer.address, peer.port)) }
        } else {
            offer("F $relative $encoded")
        }
    }

    private fun poke(punch: DatagramSocket, code: String, host: String, port: Int) {
        val address = InetAddress.getByName(host)
        repeat(6) {
            runCatching { punch.send(datagram("PUNCH $code", address, port)) }
            Thread.sleep(250)
        }
    }

    private fun datagram(text: String, address: InetAddress, port: Int): DatagramPacket {
        val bytes = text.toByteArray()
        return DatagramPacket(bytes, bytes.size, address, port)
    }

    private fun offer(line: String) {
        val clean = line.replace('\n', ' ').trim().take(60_000)
        if (clean.isBlank() || !running.get()) return
        if (!outgoing.offer(clean)) {
            outgoing.poll()
            outgoing.offer(clean)
        }
    }

    fun leave() {
        runCatching {
            writerOut?.write("T LEFT\n")
            writerOut?.flush()
        }
        stop()
    }

    fun stop() {
        running.set(false)
        generation.incrementAndGet()
        direct = null
        writerOut = null
        runCatching { socket?.close() }
        runCatching { udp?.close() }
    }

    companion object {
        const val HOST = "vmi3481754.contaboserver.net"
        const val PORT = 8756
    }
}
