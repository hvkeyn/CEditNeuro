package com.hvkeyn.ceditneuro.net

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import com.hvkeyn.ceditneuro.MainActivity
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.ByteBuffer
import java.nio.channels.SocketChannel
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Records this phone's public IPv4 packets into a pcap and forwards them so the link stays up.
 * Private ranges are left outside the tunnel. A notification stays up until the recording stops itself.
 */
class PacketCaptureService : VpnService() {

    override fun onBind(intent: Intent?) = super.onBind(intent)

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val path = intent?.getStringExtra(EXTRA_PATH).orEmpty()
        val seconds = intent?.getIntExtra(EXTRA_SECONDS, 12) ?: 12
        if (!promote(note(this))) {
            val message = "The recording notification could not be shown."
            PacketCaptureHub.finish(message)
            writeNote(path, message)
            stopSelf()
            return START_NOT_STICKY
        }
        if (path.isBlank()) {
            PacketCaptureHub.finish("No file path for the dump.")
            stopSelf()
            return START_NOT_STICKY
        }
        Thread({
            runCatching { record(path, seconds.coerceIn(5, 60)) }
                .onFailure {
                    val message = "The recording stopped: ${it.javaClass.simpleName}: ${it.message?.take(120) ?: "error"}"
                    PacketCaptureHub.finish(message)
                    writeNote(path, message)
                }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
        }, "packet-capture").start()
        return START_NOT_STICKY
    }

    private fun record(path: String, seconds: Int) {
        val file = File(path)
        file.parentFile?.mkdirs()
        if (prepare(this) != null) {
            val message = "The system VPN prompt is open. Accept it, then call capture_dump again."
            PacketCaptureHub.finish(message)
            writeNote(path, message)
            return
        }
        val builder = Builder()
            .setSession("CEditNeuro")
            .setMtu(1500)
            .setBlocking(true)
            .addAddress("203.0.113.2", 32)
            .addRoute("0.0.0.0", 0)
        if (Build.VERSION.SDK_INT >= 29) builder.setMetered(false)
        if (Build.VERSION.SDK_INT >= 33) {
            builder.excludeRoute(android.net.IpPrefix(InetAddress.getByName("10.0.0.0"), 8))
            builder.excludeRoute(android.net.IpPrefix(InetAddress.getByName("172.16.0.0"), 12))
            builder.excludeRoute(android.net.IpPrefix(InetAddress.getByName("192.168.0.0"), 16))
        }
        if (Build.VERSION.SDK_INT >= 29) builder.allowFamily(OsConstants.AF_INET6)
        val tunnel = builder.establish()
        if (tunnel == null) {
            val message = "The VPN did not start."
            PacketCaptureHub.finish(message)
            writeNote(path, message)
            return
        }
        val stop = AtomicBoolean(false)
        val udp = ConcurrentHashMap<String, DatagramSocket>()
        val tcp = ConcurrentHashMap<String, TcpLeg>()
        try {
            RandomAccessFile(file, "rw").use { pcap ->
                pcap.setLength(0)
                PacketDump.writeHeader(pcap, PacketDump.LINK_RAW)
                val input = FileInputStream(tunnel.fileDescriptor)
                val sink = Sink(pcap, FileOutputStream(tunnel.fileDescriptor))
                val deadline = System.currentTimeMillis() + seconds * 1000L
                Thread {
                    while (!stop.get() && System.currentTimeMillis() < deadline) Thread.sleep(200)
                    stop.set(true)
                    runCatching { tunnel.close() }
                }.start()
                val buf = ByteArray(32_767)
                var count = 0
                while (!stop.get() && count < 800) {
                    val n = runCatching { input.read(buf) }.getOrDefault(-1)
                    if (n <= 0) break
                    sink.record(buf, n)
                    count++
                    forward(buf, n, sink, udp, tcp)
                }
                sink.close()
            }
            val text = PacketDump.summarize(file) + "\nfile=" + file.absolutePath + " bytes=" + file.length()
            File(file.absolutePath + ".txt").writeText(text)
            PacketCaptureHub.finish(text)
        } finally {
            stop.set(true)
            udp.values.forEach { runCatching { it.close() } }
            tcp.values.forEach { it.close() }
            runCatching { tunnel.close() }
        }
    }

    /** Writes a reply into the tunnel and records it in the pcap, so both sides of a flow are seen. */
    private class Sink(private val pcap: RandomAccessFile, private val tun: FileOutputStream) {
        private val open = AtomicBoolean(true)

        @Synchronized
        fun record(packet: ByteArray, length: Int) {
            if (!open.get()) return
            runCatching { PacketDump.append(pcap, packet, length, System.currentTimeMillis()) }
        }

        @Synchronized
        fun reply(packet: ByteArray) {
            if (!open.get()) return
            runCatching { tun.write(packet) }
            runCatching { PacketDump.append(pcap, packet, packet.size, System.currentTimeMillis()) }
        }

        @Synchronized
        fun close() {
            open.set(false)
        }
    }

    private fun forward(
        packet: ByteArray,
        length: Int,
        output: Sink,
        udp: ConcurrentHashMap<String, DatagramSocket>,
        tcp: ConcurrentHashMap<String, TcpLeg>,
    ) {
        if (length < 20 || (packet[0].toInt() ushr 4) != 4) return
        val ihl = (packet[0].toInt() and 0xf) * 4
        if (ihl < 20 || length < ihl) return
        val proto = packet[9].toInt() and 0xff
        val src = PacketCodec.address(packet, 12)
        val dst = PacketCodec.address(packet, 16)
        when (proto) {
            17 -> forwardUdp(packet, length, ihl, src, dst, output, udp)
            6 -> forwardTcp(packet, length, ihl, src, dst, output, tcp)
        }
    }

    private fun forwardUdp(
        packet: ByteArray,
        length: Int,
        ihl: Int,
        src: ByteArray,
        dst: ByteArray,
        output: Sink,
        udp: ConcurrentHashMap<String, DatagramSocket>,
    ) {
        if (length < ihl + 8 || udp.size >= 48) return
        val sport = PacketCodec.u16(packet, ihl)
        val dport = PacketCodec.u16(packet, ihl + 2)
        val key = "${sport}:${dport}:${InetAddress.getByAddress(dst).hostAddress}"
        val socket = udp.getOrPut(key) {
            val opened = DatagramSocket()
            protect(opened)
            Thread {
                val buf = ByteArray(1500)
                val incoming = DatagramPacket(buf, buf.size)
                while (!opened.isClosed) {
                    val n = runCatching {
                        opened.soTimeout = 1000
                        opened.receive(incoming)
                        incoming.length
                    }.getOrDefault(-1)
                    if (n > 0) {
                        val reply = PacketCodec.udp(dst, src, dport, sport, buf, n)
                        output.reply(reply)
                    }
                }
            }.start()
            opened
        }
        val payload = length - ihl - 8
        if (payload <= 0) return
        val data = packet.copyOfRange(ihl + 8, ihl + 8 + payload)
        runCatching { socket.send(DatagramPacket(data, data.size, InetAddress.getByAddress(dst), dport)) }
    }

    private fun forwardTcp(
        packet: ByteArray,
        length: Int,
        ihl: Int,
        src: ByteArray,
        dst: ByteArray,
        output: Sink,
        tcp: ConcurrentHashMap<String, TcpLeg>,
    ) {
        if (length < ihl + 20) return
        val sport = PacketCodec.u16(packet, ihl)
        val dport = PacketCodec.u16(packet, ihl + 2)
        val seq = PacketCodec.u32(packet, ihl + 4)
        val flags = packet[ihl + 13].toInt() and 0xff
        val header = ((packet[ihl + 12].toInt() ushr 4) and 0xf) * 4
        val payloadAt = ihl + header
        val payloadLen = (length - payloadAt).coerceAtLeast(0)
        val key = "$sport:$dport:${InetAddress.getByAddress(dst).hostAddress}"
        if (flags and 0x02 != 0 && flags and 0x10 == 0) {
            if (tcp.containsKey(key) || tcp.size >= 32) return
            val leg = TcpLeg(src, sport, dst, dport, seq + 1, output, this)
            tcp[key] = leg
            leg.connect()
            return
        }
        val leg = tcp[key] ?: return
        if (flags and 0x04 != 0) {
            leg.close()
            tcp.remove(key)
            return
        }
        if (payloadLen > 0 && seq == leg.clientNext) {
            leg.sendToServer(packet, payloadAt, payloadLen)
            leg.clientNext = (leg.clientNext + payloadLen) and 0xffffffffL
        }
        if (flags and 0x01 != 0 && seq + (if (payloadLen > 0) 0 else 0) <= leg.clientNext) {
            leg.clientNext = (leg.clientNext + 1) and 0xffffffffL
            leg.shutdownServer()
        }
        leg.ack()
    }

    private fun promote(note: Notification): Boolean {
        val started = runCatching {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else if (Build.VERSION.SDK_INT >= 29) {
                startForeground(ID, note, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(ID, note)
            }
        }
        if (started.isSuccess) return true
        return runCatching { startForeground(ID, note) }.isSuccess
    }

    private class TcpLeg(
        private val client: ByteArray,
        private val clientPort: Int,
        private val server: ByteArray,
        private val serverPort: Int,
        initialClient: Long,
        private val output: Sink,
        private val vpn: VpnService,
    ) {
        @Volatile
        var clientNext: Long = initialClient

        @Volatile
        private var serverNext: Long = (System.nanoTime() and 0xffffffffL)

        /** A channel has its descriptor before connect, so protect() can keep it out of the tunnel.
         * Its socket streams share one lock, so reads and writes go through the channel itself. */
        private val channel: SocketChannel = SocketChannel.open()
        private val opened = AtomicBoolean(true)

        fun connect() {
            Thread {
                val ok = runCatching {
                    if (!vpn.protect(channel.socket())) return@runCatching false
                    channel.socket().connect(InetSocketAddress(InetAddress.getByAddress(server), serverPort), 4000)
                    true
                }.getOrDefault(false)
                if (!ok) {
                    reset()
                    close()
                    return@Thread
                }
                write(0x12, ByteArray(0), 0)
                serverNext = (serverNext + 1) and 0xffffffffL
                val buf = ByteBuffer.allocate(1400)
                while (opened.get()) {
                    buf.clear()
                    val n = runCatching { channel.read(buf) }.getOrDefault(-1)
                    if (n < 0) break
                    if (n > 0) {
                        write(0x18, buf.array(), n)
                        serverNext = (serverNext + n) and 0xffffffffL
                    }
                }
                if (opened.get()) write(0x11, ByteArray(0), 0)
            }.start()
        }

        fun sendToServer(packet: ByteArray, at: Int, length: Int) {
            if (!channel.isConnected) return
            runCatching {
                val data = ByteBuffer.wrap(packet, at, length)
                while (data.hasRemaining()) channel.write(data)
            }
        }

        fun shutdownServer() {
            runCatching { channel.shutdownOutput() }
        }

        fun ack() {
            write(0x10, ByteArray(0), 0)
        }

        fun close() {
            if (!opened.compareAndSet(true, false)) return
            runCatching { channel.close() }
        }

        private fun reset() {
            write(0x14, ByteArray(0), 0)
        }

        private fun write(flags: Int, payload: ByteArray, length: Int) {
            val packet = PacketCodec.tcp(server, client, serverPort, clientPort, serverNext, clientNext, flags, payload, length)
            output.reply(packet)
        }
    }

    private fun writeNote(path: String, message: String) {
        if (path.isBlank()) return
        runCatching { File("$path.txt").writeText(message) }
    }

    companion object {
        const val EXTRA_PATH = "path"
        const val EXTRA_SECONDS = "seconds"
        private const val ID = 79

        fun start(context: Context, path: String, seconds: Int) {
            val app = context.applicationContext
            val intent = Intent(app, PacketCaptureService::class.java)
                .putExtra(EXTRA_PATH, path)
                .putExtra(EXTRA_SECONDS, seconds)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                app.startForegroundService(intent)
            } else {
                app.startService(intent)
            }
        }

        private fun note(context: Context): Notification {
            val manager = context.getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel("packet-capture", "Packet capture", NotificationManager.IMPORTANCE_LOW)
                manager.createNotificationChannel(channel)
            }
            val open = PendingIntent.getActivity(
                context,
                2,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            return NotificationCompat.Builder(context, "packet-capture")
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle("Recording packets")
                .setContentText("This phone only. Stops by itself.")
                .setContentIntent(open)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .build()
        }
    }
}

object PacketCaptureHub {
    @Volatile
    var running: Boolean = false
        private set

    @Volatile
    private var pending: CompletableDeferred<String>? = null

    @Synchronized
    fun begin(): Boolean {
        if (running) return false
        running = true
        pending = CompletableDeferred()
        return true
    }

    /** Suspends, never blocks: the service must reach startForeground on the main thread meanwhile. */
    suspend fun await(seconds: Int): String {
        val waiting = pending ?: return "The recording did not start."
        val text = withTimeoutOrNull((seconds + 20) * 1000L) { waiting.await() }
        running = false
        return text ?: "The recording did not finish."
    }

    fun finish(text: String) {
        running = false
        pending?.complete(text)
    }

    /** Starts the service and waits for its summary. A line starting with "The ", "No ", or "A " is an error. */
    suspend fun record(context: Context, path: String, seconds: Int): String {
        if (!begin()) return "A recording is already running."
        val started = runCatching { PacketCaptureService.start(context, path, seconds) }
        if (started.isFailure) {
            finish("The recording did not start.")
            return "The recording did not start: ${started.exceptionOrNull()?.javaClass?.simpleName}."
        }
        return await(seconds)
    }

    fun isError(text: String): Boolean =
        text.startsWith("The ") || text.startsWith("No ") || text.startsWith("A ")
}
