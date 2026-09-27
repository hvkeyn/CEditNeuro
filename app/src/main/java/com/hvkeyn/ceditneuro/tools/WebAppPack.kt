package com.hvkeyn.ceditneuro.tools

import com.android.apksig.ApkSigner
import com.android.apksig.KeyConfig
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Locale
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/**
 * Turns the WebView shell APK into a signed app for one page.
 * The package id and the home-screen name replace fixed-length slots.
 * The signing key lives in this app's files and is only for these wrappers.
 */
object WebAppPack {
    const val PACKAGE_SLOT = "cedit.web.aaaaaaaa"
    const val LABEL_SLOT = "WEBAPP_LABEL_PLACEHOLD"
    const val MAX_SITE_BYTES = 8 * 1024 * 1024
    const val MAX_FILE_BYTES = 2 * 1024 * 1024

    private val webExtensions = setOf(
        "html", "htm", "css", "js", "mjs", "svg", "png", "jpg", "jpeg", "gif", "webp",
        "json", "txt", "xml", "ico", "map", "wasm", "woff", "woff2", "ttf", "otf", "webmanifest",
    )

    data class Built(
        val packageId: String,
        val label: String,
        val launcherLabel: String,
        val file: File,
    )

    fun cleanName(raw: String): String {
        val cleaned = raw.trim().filter { it >= ' ' && it != '\u007F' && !it.isSurrogate() }
        if (cleaned.isEmpty()) throw IllegalArgumentException("Missing name.")
        return cleaned.take(LABEL_SLOT.length)
    }

    /** Same name and same page keep the same id, so a later build updates that app. */
    fun packageId(name: String, source: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${cleanName(name)}\n$source".toByteArray(StandardCharsets.UTF_8))
        val hex = digest.joinToString("") { "%02x".format(it) }
        val id = "cedit.web.a" + hex.take(7)
        check(id.length == PACKAGE_SLOT.length)
        return id
    }

    fun validateUrl(raw: String): String {
        val text = raw.trim()
        val uri = try {
            java.net.URI(text)
        } catch (_: Exception) {
            throw IllegalArgumentException("The URL is not valid.")
        }
        val scheme = uri.scheme?.lowercase(Locale.US)
        if (scheme != "http" && scheme != "https") {
            throw IllegalArgumentException("The URL must start with http:// or https://.")
        }
        if (!uri.userInfo.isNullOrEmpty()) {
            throw IllegalArgumentException("The URL cannot contain a username or password.")
        }
        if (uri.host.isNullOrBlank()) throw IllegalArgumentException("The URL needs a host.")
        return text
    }

    /** A file becomes index.html. A folder must contain index.html. Other web files are kept. */
    fun collectSite(root: File): List<Pair<String, ByteArray>> {
        val base = root.canonicalFile
        if (!base.exists()) throw IllegalArgumentException("No file at ${base.absolutePath}.")
        val files = if (base.isFile) {
            if (!allowed(base.name)) throw IllegalArgumentException("${base.name} is not a web page.")
            listOf(base to "index.html")
        } else {
            val index = File(base, "index.html")
            if (!index.isFile) throw IllegalArgumentException("That folder has no index.html.")
            val found = ArrayList<Pair<File, String>>()
            base.walkTopDown().filter { it.isFile }.forEach { file ->
                if (file.name.startsWith(".")) return@forEach
                val canonical = file.canonicalFile
                val prefix = base.path + File.separator
                if (canonical != base && !canonical.path.startsWith(prefix)) {
                    throw IllegalArgumentException("A file points outside the folder.")
                }
                if (!allowed(file.name)) return@forEach
                val rel = canonical.relativeTo(base).path.replace('\\', '/')
                if (rel.isEmpty() || rel.contains("..") || rel.startsWith("/")) {
                    throw IllegalArgumentException("Bad path $rel.")
                }
                found += canonical to rel
            }
            found
        }
        var total = 0L
        val out = ArrayList<Pair<String, ByteArray>>()
        for ((file, rel) in files) {
            if (file.length() > MAX_FILE_BYTES) {
                throw IllegalArgumentException("${file.name} is too large to pack.")
            }
            total += file.length()
            if (total > MAX_SITE_BYTES) throw IllegalArgumentException("The site is larger than 8 MB.")
            out += "assets/www/$rel" to file.readBytes()
        }
        if (out.isEmpty()) throw IllegalArgumentException("There is nothing to pack.")
        return out
    }

    fun pack(
        template: ByteArray,
        name: String,
        source: String,
        startUrl: String,
        site: List<Pair<String, ByteArray>>,
        identityDir: File,
        output: File,
    ): Built {
        val label = cleanName(name)
        val id = packageId(label, source)
        val entries = readZip(template).toMutableList()
        for (entry in entries) {
            if (!entry.name.endsWith(".dex") && !entry.name.endsWith(".so")) continue
            if (containsAscii(entry.bytes, PACKAGE_SLOT) || containsAscii(entry.bytes, LABEL_SLOT)) {
                throw IllegalStateException("The web shell template has a slot inside ${entry.name}.")
            }
        }
        var packageHits = 0
        var labelHits = 0
        var launcher = label
        for (index in entries.indices) {
            val entry = entries[index]
            if (entry.name != "AndroidManifest.xml" && entry.name != "resources.arsc") continue
            val patched = patchSlots(entry.bytes, id, label)
            packageHits += patched.packageHits
            labelHits += patched.labelHits
            if (patched.shown != null) launcher = patched.shown
            entries[index] = Entry(entry.name, patched.bytes)
        }
        if (packageHits == 0) throw IllegalStateException("The web shell template has no package slot.")
        if (labelHits == 0) throw IllegalStateException("The web shell template has no name slot.")
        val start = (startUrl.trim() + "\n").toByteArray(StandardCharsets.UTF_8)
        val title = (label + "\n").toByteArray(StandardCharsets.UTF_8)
        put(entries, "assets/start.txt", start)
        put(entries, "assets/title.txt", title)
        for ((path, bytes) in site) put(entries, path, bytes)
        output.parentFile?.mkdirs()
        val unsigned = File(output.parentFile, output.name + ".unsigned")
        try {
            writeAligned(entries, unsigned)
            val (key, cert) = WebAppIdentity.load(identityDir)
            val signer = ApkSigner.SignerConfig.Builder("WEBAPP", KeyConfig.Jca(key), listOf(cert)).build()
            ApkSigner.Builder(listOf(signer))
                .setInputApk(unsigned)
                .setOutputApk(output)
                .setV1SigningEnabled(false)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(false)
                .setV4SigningEnabled(false)
                .build()
                .sign()
        } finally {
            unsigned.delete()
        }
        if (!output.isFile || output.length() == 0L) {
            throw IllegalStateException("The APK was not written.")
        }
        return Built(id, label, launcher, output)
    }

    private fun allowed(name: String): Boolean {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.US)
        return ext in webExtensions
    }

    private data class Entry(val name: String, val bytes: ByteArray)

    private data class Patch(
        val bytes: ByteArray,
        val packageHits: Int,
        val labelHits: Int,
        val shown: String?,
    )

    private fun patchSlots(data: ByteArray, packageId: String, label: String): Patch {
        var bytes = data
        var packageHits = 0
        var labelHits = 0
        var shown: String? = null
        val pkg = swap(bytes, PACKAGE_SLOT.toByteArray(StandardCharsets.US_ASCII), packageId.toByteArray(StandardCharsets.US_ASCII))
        bytes = pkg.first
        packageHits += pkg.second
        val pkg16 = swap(bytes, utf16le(PACKAGE_SLOT), utf16le(packageId))
        bytes = pkg16.first
        packageHits += pkg16.second
        val padded = label.padEnd(LABEL_SLOT.length, ' ')
        val label16 = swap(bytes, utf16le(LABEL_SLOT), utf16le(padded))
        bytes = label16.first
        labelHits += label16.second
        if (label16.second > 0) shown = label
        val fitted = fitUtf8(label, LABEL_SLOT.length)
        val utf8 = patchUtf8Label(bytes, fitted)
        bytes = utf8.first
        labelHits += utf8.second
        if (utf8.second > 0 && shown == null) {
            shown = String(fitted, StandardCharsets.UTF_8).trimEnd()
        }
        if (labelHits == 0) {
            val ascii = if (label.all { it.code in 32..126 }) padded else "Web app".padEnd(LABEL_SLOT.length, ' ')
            val label8 = swap(bytes, LABEL_SLOT.toByteArray(StandardCharsets.US_ASCII), ascii.toByteArray(StandardCharsets.US_ASCII))
            bytes = label8.first
            labelHits += label8.second
            if (label8.second > 0) shown = ascii.trim()
        }
        return Patch(bytes, packageHits, labelHits, shown)
    }

    /** Keeps the UTF-8 byte length of the placeholder so the string-pool size stays valid. */
    private fun fitUtf8(label: String, byteLength: Int): ByteArray {
        val out = ArrayList<Byte>(byteLength)
        for (char in label) {
            val encoded = char.toString().toByteArray(StandardCharsets.UTF_8)
            if (out.size + encoded.size > byteLength) break
            encoded.forEach { out += it }
        }
        while (out.size < byteLength) out += ' '.code.toByte()
        return out.toByteArray()
    }

    private fun patchUtf8Label(data: ByteArray, fitted: ByteArray): Pair<ByteArray, Int> {
        val slot = LABEL_SLOT.toByteArray(StandardCharsets.US_ASCII)
        if (fitted.size != slot.size) return data to 0
        val charCount = String(fitted, StandardCharsets.UTF_8).length
        if (charCount > 127) return data to 0
        val needle = byteArrayOf(slot.size.toByte(), slot.size.toByte()) + slot + byteArrayOf(0)
        val replacement = byteArrayOf(charCount.toByte(), slot.size.toByte()) + fitted + byteArrayOf(0)
        return swap(data, needle, replacement)
    }

    private fun put(entries: MutableList<Entry>, name: String, bytes: ByteArray) {
        val index = entries.indexOfFirst { it.name == name }
        if (index >= 0) entries[index] = Entry(name, bytes) else entries += Entry(name, bytes)
    }

    private fun readZip(template: ByteArray): List<Entry> {
        val out = ArrayList<Entry>()
        ZipInputStream(ByteArrayInputStream(template)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory || entry.name.startsWith("META-INF/")) continue
                out += Entry(entry.name, zip.readBytes())
            }
        }
        if (out.none { it.name == "AndroidManifest.xml" }) {
            throw IllegalStateException("The web shell template has no manifest.")
        }
        return out
    }

    private fun writeAligned(entries: List<Entry>, output: File) {
        FileOutputStream(output).use { file ->
            val counting = CountingOutputStream(file)
            ZipOutputStream(counting).use { zip ->
                for (entry in entries) {
                    val stored = store(entry.name)
                    val next = ZipEntry(entry.name)
                    if (stored) {
                        val crc = CRC32()
                        crc.update(entry.bytes)
                        next.method = ZipEntry.STORED
                        next.size = entry.bytes.size.toLong()
                        next.compressedSize = entry.bytes.size.toLong()
                        next.crc = crc.value
                        val nameBytes = entry.name.toByteArray(StandardCharsets.UTF_8)
                        val start = counting.count + 30 + nameBytes.size
                        val pad = ((4 - (start % 4)) % 4).toInt()
                        if (pad > 0) next.extra = ByteArray(pad)
                    } else {
                        next.method = ZipEntry.DEFLATED
                    }
                    zip.putNextEntry(next)
                    zip.write(entry.bytes)
                    zip.closeEntry()
                }
            }
        }
    }

    private fun store(name: String): Boolean {
        val lower = name.lowercase(Locale.US)
        return lower == "resources.arsc" || lower.endsWith(".dex") || lower.endsWith(".so")
    }

    private fun swap(data: ByteArray, from: ByteArray, to: ByteArray): Pair<ByteArray, Int> {
        check(from.size == to.size && from.isNotEmpty())
        val out = data.copyOf()
        var count = 0
        var index = 0
        while (index + from.size <= out.size) {
            if (indexOf(out, from, index) == index) {
                to.copyInto(out, index)
                count++
                index += from.size
            } else {
                index++
            }
        }
        return out to count
    }

    private fun containsAscii(data: ByteArray, text: String): Boolean =
        indexOf(data, text.toByteArray(StandardCharsets.US_ASCII)) >= 0

    private fun indexOf(data: ByteArray, needle: ByteArray, start: Int = 0): Int {
        if (needle.isEmpty() || start > data.size - needle.size) return -1
        var index = start
        while (index + needle.size <= data.size) {
            var matched = true
            for (offset in needle.indices) {
                if (data[index + offset] != needle[offset]) {
                    matched = false
                    break
                }
            }
            if (matched) return index
            index++
        }
        return -1
    }

    private fun utf16le(text: String): ByteArray {
        val out = ByteArray(text.length * 2)
        text.forEachIndexed { index, char ->
            val value = char.code
            out[index * 2] = (value and 0xFF).toByte()
            out[index * 2 + 1] = (value ushr 8).toByte()
        }
        return out
    }
}

/** One RSA key for every wrapper this install builds. A new key would block updates. */
object WebAppIdentity {
    private val lock = Any()

    fun load(dir: File): Pair<PrivateKey, X509Certificate> = synchronized(lock) {
        dir.mkdirs()
        val keyFile = File(dir, "key.pk8")
        val certFile = File(dir, "cert.der")
        if (keyFile.isFile && certFile.isFile) {
            val loaded = runCatching { read(keyFile, certFile) }.getOrNull()
            if (loaded != null) return loaded
        }
        val created = create()
        keyFile.writeBytes(created.first.encoded)
        certFile.writeBytes(created.second.encoded)
        created
    }

    private fun read(keyFile: File, certFile: File): Pair<PrivateKey, X509Certificate> {
        val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
        val cert = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certFile.readBytes())) as X509Certificate
        cert.checkValidity()
        return key to cert
    }

    private fun create(): Pair<PrivateKey, X509Certificate> {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(2048, SecureRandom())
        val pair = generator.generateKeyPair()
        val serial = ByteArray(8)
        SecureRandom().nextBytes(serial)
        serial[0] = (serial[0].toInt() and 0x7F).toByte()
        if (serial.all { it == 0.toByte() }) serial[7] = 1
        val alg = Der.sequence(Der.oid("1.2.840.113549.1.1.11") + Der.nullValue())
        val name = Der.sequence(
            Der.set(
                Der.sequence(
                    Der.oid("2.5.4.3") + Der.printable("CEditNeuro Web"),
                ),
            ),
        )
        val validity = Der.sequence(
            Der.utcTime("240101000000Z") + Der.utcTime("491231235959Z"),
        )
        val tbs = Der.sequence(
            Der.explicit(0, Der.integer(byteArrayOf(2))) +
                Der.integer(serial) +
                alg +
                name +
                validity +
                name +
                pair.public.encoded,
        )
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(pair.private)
            update(tbs)
            sign()
        }
        val certificate = Der.sequence(tbs + alg + Der.bitString(signature))
        val parsed = CertificateFactory.getInstance("X.509")
            .generateCertificate(ByteArrayInputStream(certificate)) as X509Certificate
        parsed.verify(pair.public)
        return pair.private to parsed
    }
}

private object Der {
    fun sequence(body: ByteArray): ByteArray = tag(0x30, body)
    fun set(body: ByteArray): ByteArray = tag(0x31, body)
    fun nullValue(): ByteArray = byteArrayOf(0x05, 0x00)
    fun printable(text: String): ByteArray = tag(0x13, text.toByteArray(StandardCharsets.US_ASCII))
    fun utcTime(text: String): ByteArray = tag(0x17, text.toByteArray(StandardCharsets.US_ASCII))
    fun bitString(raw: ByteArray): ByteArray = tag(0x03, byteArrayOf(0) + raw)
    fun explicit(number: Int, body: ByteArray): ByteArray = tag(0xA0 or number, body)

    fun integer(value: ByteArray): ByteArray {
        var body = value.dropWhile { it == 0.toByte() }.toByteArray()
        if (body.isEmpty()) body = byteArrayOf(0)
        if (body[0].toInt() and 0x80 != 0) body = byteArrayOf(0) + body
        return tag(0x02, body)
    }

    fun oid(dotted: String): ByteArray {
        val parts = dotted.split('.').map { it.toInt() }
        val body = ArrayList<Byte>()
        body += (parts[0] * 40 + parts[1]).toByte()
        parts.drop(2).forEach { part -> base128(part).forEach { body += it } }
        return tag(0x06, body.toByteArray())
    }

    private fun base128(value: Int): ByteArray {
        val tmp = ArrayList<Byte>()
        var current = value
        tmp += (current and 0x7F).toByte()
        current = current ushr 7
        while (current > 0) {
            tmp += ((current and 0x7F) or 0x80).toByte()
            current = current ushr 7
        }
        return tmp.asReversed().toByteArray()
    }

    private fun tag(kind: Int, body: ByteArray): ByteArray = byteArrayOf(kind.toByte()) + length(body.size) + body

    private fun length(size: Int): ByteArray = when {
        size < 0x80 -> byteArrayOf(size.toByte())
        size <= 0xFF -> byteArrayOf(0x81.toByte(), size.toByte())
        size <= 0xFFFF -> byteArrayOf(0x82.toByte(), (size shr 8).toByte(), size.toByte())
        else -> byteArrayOf(
            0x83.toByte(),
            (size shr 16).toByte(),
            (size shr 8).toByte(),
            size.toByte(),
        )
    }
}

private class CountingOutputStream(out: OutputStream) : FilterOutputStream(out) {
    var count: Long = 0
        private set

    override fun write(value: Int) {
        out.write(value)
        count++
    }

    override fun write(bytes: ByteArray, offset: Int, length: Int) {
        out.write(bytes, offset, length)
        count += length
    }
}
