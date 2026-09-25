package com.hvkeyn.ceditneuro.net

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.InputStreamReader
import java.io.OutputStreamWriter
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

    fun start(code: String, device: String, onReady: () -> Unit = {}, onPeer: (name: String, text: String) -> Unit) {
        stop()
        val gen = generation.incrementAndGet()
        running.set(true)
        Thread {
            while (running.get() && gen == generation.get()) {
                try {
                    val link = Socket()
                    link.connect(InetSocketAddress(host, port), 12_000)
                    link.soTimeout = 0
                    socket = link
                    val writer = BufferedWriter(OutputStreamWriter(link.getOutputStream(), Charsets.UTF_8))
                    val reader = BufferedReader(InputStreamReader(link.getInputStream(), Charsets.UTF_8))
                    writer.write("JOIN $code $device\n")
                    writer.flush()
                    onReady()
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
                        val parts = line.split(' ', limit = 3)
                        if (parts.size == 3 && parts[0] == "P") onPeer(parts[1], parts[2])
                    }
                    pump.interrupt()
                } catch (_: Exception) {
                    if (gen == generation.get()) Thread.sleep(2_000)
                } finally {
                    runCatching { socket?.close() }
                    socket = null
                }
            }
        }.also { it.isDaemon = true; it.name = "beacon" }.start()
    }

    fun send(text: String) {
        offer("T $text")
    }

    fun sendFile(relative: String, bytes: ByteArray) {
        val payload = "F $relative " + android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
        offer(payload)
    }

    private fun offer(line: String) {
        val clean = line.replace('\n', ' ').trim().take(60_000)
        if (clean.isBlank() || !running.get()) return
        if (!outgoing.offer(clean)) {
            outgoing.poll()
            outgoing.offer(clean)
        }
    }

    fun stop() {
        running.set(false)
        generation.incrementAndGet()
        runCatching { socket?.close() }
    }

    companion object {
        const val HOST = "458043.vm.hosted-by-spacecore.pro"
        const val PORT = 8756
    }
}
