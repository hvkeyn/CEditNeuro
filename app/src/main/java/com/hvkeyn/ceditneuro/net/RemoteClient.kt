package com.hvkeyn.ceditneuro.net

import com.hvkeyn.ceditneuro.data.RemoteServer
import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.HostKey
import com.jcraft.jsch.HostKeyRepository
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.UserInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import org.apache.commons.net.ftp.FTPSClient
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.ConnectException
import java.net.UnknownHostException
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.X509TrustManager

class UntrustedHostException(val fingerprint: String) :
    IOException("Host is not trusted. Fingerprint $fingerprint")

data class RemoteEntry(val name: String, val directory: Boolean, val size: Long)

/**
 * FTP, FTPS, and SFTP. SSH commands use the SFTP login.
 * A new host key or certificate is refused until [trust] accepts its fingerprint.
 */
class RemoteClient(
    private val trust: suspend (serverId: String, host: String, fingerprint: String) -> Boolean,
) {
    suspend fun list(server: RemoteServer, path: String): List<RemoteEntry> =
        use(server) { it.list(resolve(server, path)) }

    suspend fun read(server: RemoteServer, path: String): ByteArray =
        use(server) { it.read(resolve(server, path)) }

    suspend fun write(server: RemoteServer, path: String, bytes: ByteArray) =
        use(server) { it.write(resolve(server, path), bytes) }

    suspend fun exec(server: RemoteServer, command: String): String {
        if (server.protocol != "sftp") {
            throw IOException("Remote commands need an SFTP server. ${server.protocol} only transfers files.")
        }
        return use(server) { it.exec(command) }
    }

    suspend fun probe(server: RemoteServer): String {
        val entries = list(server, server.startPath.ifBlank { "/" })
        return "Connected to ${server.host}. ${entries.size} item(s) in ${server.startPath.ifBlank { "/" }}."
    }

    suspend fun mkdir(server: RemoteServer, path: String) =
        use(server) { it.mkdir(resolve(server, path)) }

    private suspend fun <T> use(server: RemoteServer, block: (RemoteOps) -> T): T {
        try {
            return withContext(Dispatchers.IO) { open(server).use(block) }
        } catch (error: UntrustedHostException) {
            if (!trust(server.id, server.host, error.fingerprint)) throw error
            val trusted = server.copy(trustedFingerprint = error.fingerprint)
            return withContext(Dispatchers.IO) { open(trusted).use(block) }
        }
    }

    private fun open(server: RemoteServer): RemoteOps = when (server.protocol) {
        "sftp" -> SftpOps(server)
        "ftp", "ftps" -> FtpOps(server)
        else -> throw IOException("Unknown protocol ${server.protocol}.")
    }

    private fun resolve(server: RemoteServer, path: String): String {
        val raw = path.trim().replace('\\', '/')
        if (raw.contains('\u0000')) throw IOException("Invalid path.")
        if (raw.startsWith("/")) return raw.ifBlank { "/" }
        val base = server.startPath.trim().ifBlank { "/" }.trimEnd('/')
        if (raw.isBlank() || raw == ".") return if (base.isEmpty()) "/" else base
        return "$base/$raw"
    }
}

private interface RemoteOps : AutoCloseable {
    fun list(path: String): List<RemoteEntry>
    fun read(path: String): ByteArray
    fun write(path: String, bytes: ByteArray)
    fun exec(command: String): String
    fun mkdir(path: String)
}

private class SftpOps(server: RemoteServer) : RemoteOps {
    private val session: Session
    private val sftp: ChannelSftp

    init {
        val seen = AtomicReference("")
        val jsch = JSch()
        session = jsch.getSession(server.username, server.host, server.port)
        session.setPassword(server.password)
        session.hostKeyRepository = FingerprintKeys(server.trustedFingerprint, seen)
        session.setConfig("StrictHostKeyChecking", "yes")
        session.userInfo = DenyUserInfo
        session.timeout = 60_000
        try {
            session.connect(20_000)
        } catch (error: Exception) {
            val fingerprint = seen.get()
            session.disconnect()
            if (fingerprint.isNotBlank() && fingerprint != server.trustedFingerprint) {
                throw UntrustedHostException(fingerprint)
            }
            throw IOException(explainConnect(error, server.host, server.port))
        }
        sftp = session.openChannel("sftp") as ChannelSftp
        sftp.connect(20_000)
    }

    override fun list(path: String): List<RemoteEntry> {
        val listed = sftp.ls(path) ?: return emptyList()
        return listed.mapNotNull { item ->
            val entry = item as? ChannelSftp.LsEntry ?: return@mapNotNull null
            val name = entry.filename
            if (name == "." || name == "..") return@mapNotNull null
            RemoteEntry(name, entry.attrs.isDir, entry.attrs.size)
        }
    }

    override fun read(path: String): ByteArray {
        val size = sftp.lstat(path).size
        if (size > MAX_BYTES) throw IOException("Remote file is $size bytes, limit is $MAX_BYTES.")
        sftp.get(path).use { input -> return input.readBytes().also { checkSize(it.size) } }
    }

    override fun write(path: String, bytes: ByteArray) {
        checkSize(bytes.size)
        ByteArrayInputStream(bytes).use { input ->
            sftp.put(input, path, ChannelSftp.OVERWRITE)
        }
    }

    override fun exec(command: String): String {
        val channel = session.openChannel("exec") as ChannelExec
        channel.setCommand(command)
        val output = ByteArrayOutputStream()
        channel.inputStream.use { input ->
            channel.connect(20_000)
            val buffer = ByteArray(4096)
            val deadline = System.nanoTime() + 60_000_000_000L
            while (System.nanoTime() < deadline) {
                val read = input.read(buffer)
                if (read < 0) break
                if (output.size() < MAX_EXEC) {
                    val room = MAX_EXEC - output.size()
                    output.write(buffer, 0, minOf(read, room))
                }
            }
        }
        val text = output.toString(Charsets.UTF_8)
        val code = channel.exitStatus
        channel.disconnect()
        return "exit=$code\n$text"
    }

    override fun mkdir(path: String) {
        runCatching { sftp.mkdir(path) }
    }

    override fun close() {
        sftp.disconnect()
        session.disconnect()
    }
}

private class FtpOps(server: RemoteServer) : RemoteOps {
    private val ftp: FTPClient = if (server.protocol == "ftps") {
        FTPSClient(server.port == 990)
    } else {
        FTPClient()
    }

    init {
        val seen = AtomicReference("")
        ftp.connectTimeout = 20_000
        ftp.defaultTimeout = 20_000
        ftp.controlEncoding = "UTF-8"
        if (ftp is FTPSClient) {
            ftp.trustManager = FingerprintTrust(server.trustedFingerprint, seen)
        }
        try {
            ftp.connect(server.host, server.port)
            ftp.soTimeout = 60_000
            if (!ftp.login(server.username, server.password)) {
                throw IOException("FTP login failed: ${ftp.replyString.trim()}")
            }
            if (ftp is FTPSClient) {
                ftp.execPBSZ(0)
                ftp.execPROT("P")
            }
            ftp.enterLocalPassiveMode()
            ftp.setFileType(FTP.BINARY_FILE_TYPE)
        } catch (error: Exception) {
            runCatching { ftp.disconnect() }
            val fingerprint = seen.get()
            if (fingerprint.isNotBlank() && fingerprint != server.trustedFingerprint) {
                throw UntrustedHostException(fingerprint)
            }
            if (error is IOException && error.message?.startsWith("FTP login failed") == true) throw error
            throw IOException(explainConnect(error, server.host, server.port))
        }
    }

    override fun list(path: String): List<RemoteEntry> =
        ftp.listFiles(path).orEmpty().mapNotNull { file ->
            val name = file.name ?: return@mapNotNull null
            if (name == "." || name == "..") return@mapNotNull null
            RemoteEntry(name, file.isDirectory, file.size)
        }

    override fun read(path: String): ByteArray {
        val output = ByteArrayOutputStream()
        if (!ftp.retrieveFile(path, output)) {
            throw IOException("FTP read failed: ${ftp.replyString.trim()}")
        }
        checkSize(output.size())
        return output.toByteArray()
    }

    override fun write(path: String, bytes: ByteArray) {
        checkSize(bytes.size)
        ByteArrayInputStream(bytes).use { input ->
            if (!ftp.storeFile(path, input)) {
                throw IOException("FTP write failed: ${ftp.replyString.trim()}")
            }
        }
    }

    override fun exec(command: String): String =
        throw IOException("Remote commands need an SFTP server.")

    override fun mkdir(path: String) {
        ftp.makeDirectory(path)
    }

    override fun close() {
        runCatching { ftp.logout() }
        runCatching { ftp.disconnect() }
    }
}

private object DenyUserInfo : UserInfo {
    override fun getPassphrase(): String? = null
    override fun getPassword(): String? = null
    override fun promptPassword(message: String?): Boolean = false
    override fun promptPassphrase(message: String?): Boolean = false
    override fun promptYesNo(message: String?): Boolean = false
    override fun showMessage(message: String?) = Unit
}

private class FingerprintKeys(
    private val expected: String,
    private val seen: AtomicReference<String>,
) : HostKeyRepository {
    override fun check(host: String?, key: ByteArray?): Int {
        if (key == null) return HostKeyRepository.NOT_INCLUDED
        val fingerprint = sha256Fingerprint(key)
        seen.set(fingerprint)
        return if (expected.isNotBlank() && expected == fingerprint) {
            HostKeyRepository.OK
        } else {
            HostKeyRepository.NOT_INCLUDED
        }
    }

    override fun add(hostkey: HostKey?, ui: UserInfo?) = Unit
    override fun remove(host: String?, type: String?) = Unit
    override fun remove(host: String?, type: String?, key: ByteArray?) = Unit
    override fun getKnownHostsRepositoryID(): String = "ceditneuro"
    override fun getHostKey(): Array<HostKey> = emptyArray()
    override fun getHostKey(host: String?, type: String?): Array<HostKey> = emptyArray()
}

private class FingerprintTrust(
    private val expected: String,
    private val seen: AtomicReference<String>,
) : X509TrustManager {
    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        val encoded = chain?.firstOrNull()?.encoded ?: throw CertificateException("No server certificate.")
        val fingerprint = sha256Fingerprint(encoded)
        seen.set(fingerprint)
        if (expected.isBlank() || expected != fingerprint) {
            throw CertificateException("Untrusted certificate $fingerprint")
        }
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

private fun sha256Fingerprint(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    val encoded = android.util.Base64.encodeToString(digest, android.util.Base64.NO_WRAP).trimEnd('=')
    return "SHA256:$encoded"
}

private fun explainConnect(error: Throwable, host: String, port: Int): String {
    val raw = generateSequence(error) { it.cause }
        .mapNotNull { it.message?.takeIf(String::isNotBlank) }
        .joinToString(" ")
    val refused = error.causes().any { it is ConnectException } ||
        raw.contains("Connection refused", ignoreCase = true)
    val dns = error.causes().any { it is UnknownHostException } ||
        raw.contains("Unable to resolve", ignoreCase = true)
    return when {
        raw.contains("FTP login failed", ignoreCase = true) ||
            raw.contains("Auth fail", ignoreCase = true) ->
            "Login rejected by $host. The username or password was not accepted."
        dns -> "DNS failed for $host. The name did not resolve."
        refused -> "Connection refused by $host:$port."
        raw.contains("timeout", ignoreCase = true) || raw.contains("timed out", ignoreCase = true) ->
            "Timed out connecting to $host:$port. Check the host name, the IP, and the port."
        else -> raw.ifBlank { "Connection to $host:$port failed." }.take(400)
    }
}

private fun Throwable.causes(): Sequence<Throwable> = generateSequence(this) { it.cause }

private fun checkSize(size: Int) {
    if (size > MAX_BYTES) throw IOException("Transfer is $size bytes, limit is $MAX_BYTES.")
}

private const val MAX_BYTES = 2_000_000
private const val MAX_EXEC = 20_000
