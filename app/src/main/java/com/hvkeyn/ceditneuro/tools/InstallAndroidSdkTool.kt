package com.hvkeyn.ceditneuro.tools

import com.hvkeyn.ceditneuro.net.AgentNet
import com.hvkeyn.ceditneuro.shell.ProgramRun
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FilterInputStream
import java.io.InputStream
import java.net.URLEncoder
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * Android SDK pieces that can actually run on this phone: aapt2, aidl, d8,
 * apksigner, a small zipalign, Gradle, and one android.jar.
 * Linux build-tools binaries are not used; they do not start under Android.
 */
class InstallAndroidSdkTool(
    private val toolchain: File,
    private val net: AgentNet,
    private val networkAllowed: () -> Boolean,
    private val ensureExec: suspend (String) -> Boolean,
    private val zipAlignSource: String,
) : Tool {
    override val name = "install_android_sdk"
    override val description =
        "Download aapt2, d8, apksigner, zipalign, Gradle 9.7.1, and Android SDK platform 36 " +
            "so gradle assembleDebug can build an APK. Call install_jdk first. " +
            "Use Android Gradle Plugin 9.4.1, compileSdk 36, and buildTools 36.0.0. " +
            "The first Gradle build downloads plugins and needs timeout_seconds of 600 or more."
    override val parameters = objectSchema(properties = emptyMap())

    override suspend fun execute(args: JsonObject): ToolResult = withContext(Dispatchers.IO) {
        if (alreadyInstalled()) return@withContext ToolResult.ok(ALREADY)
        if (!File(toolchain, "bin/javac").isFile) {
            return@withContext ToolResult.error("OpenJDK is missing. Call install_jdk first.")
        }
        if (!networkAllowed()) {
            return@withContext ToolResult.error("Agent network is off. Turn on Agent network in Settings.")
        }
        val allowed = ensureExec("Download aapt2, Gradle, and the Android SDK into this app.")
        if (!allowed) {
            return@withContext ToolResult.error(
                "The user did not allow running installed programs. They can turn it on in Settings.",
            )
        }
        val staging = File(toolchain, ".android-staging").apply {
            deleteRecursively()
            mkdirs()
        }
        val unpacked = longArrayOf(0L)
        try {
            for (path in DEBS) {
                val deb = File(staging, path.substringAfterLast('/').replace(':', '_'))
                val limit = if (path.contains("/gradle/")) GRADLE_DOWNLOAD else MAX_DOWNLOAD
                net.download(packageUrl(path), deb, limit, TIMEOUT_SECONDS)
                extractDeb(deb, toolchain, unpacked)
                deb.delete()
            }
            val platform = File(staging, "platform.zip")
            net.download(PLATFORM_URL, platform, MAX_DOWNLOAD, TIMEOUT_SECONDS)
            extractPlatform(platform)
            writeLaunchers()
            compileZipAlign()
            writeSdkLayout()
            ProgramRun.markTree(toolchain)
            File(toolchain, STAMP_NAME).writeText(STAMP)
            ToolResult.ok(INSTALLED)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            ToolResult.error(error.message ?: "Android SDK install failed.")
        } finally {
            staging.deleteRecursively()
        }
    }

    private fun alreadyInstalled(): Boolean {
        val stamp = File(toolchain, STAMP_NAME)
        return stamp.isFile && stamp.readText() == STAMP &&
            File(toolchain, "bin/aapt2").isFile &&
            File(toolchain, "bin/gradle").isFile &&
            File(toolchain, "android-sdk/platforms/android-$API/android.jar").isFile &&
            File(toolchain, "android-sdk/build-tools/$BUILD_TOOLS/aapt2").isFile
    }

    private fun writeLaunchers() {
        replaceScript(File(toolchain, "bin/d8"), javaTool("d8.jar", "com.android.tools.r8.D8"))
        replaceScript(File(toolchain, "bin/r8"), javaTool("d8.jar", "com.android.tools.r8.R8"))
        replaceScript(File(toolchain, "bin/apksigner"), javaJar("apksigner.jar"))
        replaceScript(File(toolchain, "bin/gradle"), gradleWrapper())
        val keytool = File(toolchain, "lib/jvm/java-17-openjdk/bin/keytool")
        if (keytool.isFile) replaceScript(File(toolchain, "bin/keytool"), toolWrapper("keytool"))
    }

    private fun compileZipAlign() {
        val dir = File(toolchain, "share/java").apply { mkdirs() }
        val source = File(dir, "ZipAlign.java")
        source.writeText(lf(zipAlignSource))
        val classes = File(dir, "zipalign-classes").apply { mkdirs() }
        val javac = File(toolchain, "bin/javac")
        val process = ProcessBuilder(
            javac.absolutePath,
            "-encoding",
            "UTF-8",
            "-d",
            classes.absolutePath,
            source.absolutePath,
        ).redirectErrorStream(true).start()
        val log = process.inputStream.bufferedReader().readText()
        if (!process.waitFor(120, TimeUnit.SECONDS) || process.exitValue() != 0) {
            process.destroyForcibly()
            throw IllegalStateException("zipalign compile failed. ${log.take(500)}")
        }
        replaceScript(File(toolchain, "bin/zipalign"), zipAlignWrapper())
    }

    private fun writeSdkLayout() {
        val tools = File(toolchain, "android-sdk/build-tools/$BUILD_TOOLS").apply { mkdirs() }
        for (name in listOf("aapt2", "aapt", "aidl")) {
            if (File(toolchain, "bin/$name").isFile) {
                replaceScript(File(tools, name), nativeWrapper(name))
            }
        }
        for (name in listOf("d8", "apksigner", "zipalign")) {
            replaceScript(File(tools, name), binWrapper(name))
        }
        File(tools, "source.properties").writeText(lf("Pkg.UserSrc=false\nPkg.Revision=$BUILD_TOOLS\n"))
        val licenses = File(toolchain, "android-sdk/licenses").apply { mkdirs() }
        File(licenses, "android-sdk-license").writeText(lf("24333f8a63b6825ea9c5514f83c2829b004d1fee\n"))
    }

    private fun extractPlatform(zip: File) {
        val dest = File(toolchain, "android-sdk/platforms/android-$API").apply { mkdirs() }
        ZipInputStream(zip.inputStream()).use { zipIn ->
            while (true) {
                val entry = zipIn.nextEntry ?: break
                val name = entry.name.replace('\\', '/')
                val fileName = name.substringAfterLast('/')
                if (!entry.isDirectory && fileName in PLATFORM_FILES && name.count { it == '/' } <= 1) {
                    File(dest, fileName).outputStream().use { output -> zipIn.copyTo(output) }
                }
                zipIn.closeEntry()
            }
        }
        if (!File(dest, "android.jar").isFile) {
            throw IllegalStateException("Platform archive did not contain android.jar.")
        }
    }

    private fun packageUrl(path: String): String =
        BASE + path.split('/').joinToString("/") { segment ->
            URLEncoder.encode(segment, Charsets.UTF_8).replace("+", "%20")
        }

    private fun extractDeb(deb: File, destRoot: File, unpacked: LongArray) {
        FileInputStream(deb).use { input ->
            val magic = ByteArray(8)
            readFully(input, magic)
            if (!magic.contentEquals(AR_MAGIC)) throw IllegalStateException("Not a Debian package: ${deb.name}")
            while (true) {
                val header = ByteArray(60)
                val read = readFullyOrEof(input, header)
                if (read == 0) break
                if (read < 60) {
                    throw IllegalStateException(
                        "Truncated Debian package: ${deb.name} (${deb.length()} bytes, header read $read)",
                    )
                }
                val name = String(header, 0, 16, Charsets.US_ASCII).trim().trimEnd('/')
                val size = String(header, 48, 10, Charsets.US_ASCII).trim().toLong()
                if (name == "data.tar.xz") {
                    BoundedInputStream(input, size).use { slice ->
                        XZCompressorInputStream(slice).use { xz ->
                            extractTar(TarArchiveInputStream(xz), destRoot, unpacked)
                        }
                    }
                } else {
                    skipFully(input, size)
                }
                if (size % 2L == 1L) skipFully(input, 1)
            }
        }
    }

    private fun extractTar(tar: TarArchiveInputStream, destRoot: File, unpacked: LongArray) {
        while (true) {
            val entry = tar.nextEntry ?: break
            val rel = toolchainRelative(entry.name) ?: continue
            if (skipEntry(rel)) continue
            val path = ArchivePaths.output(destRoot, rel)
            when {
                entry.isDirectory -> ArchivePaths.directory(path)
                entry.isSymbolicLink -> ArchivePaths.symlink(path, toolchain, entry.linkName)
                entry.isFile -> {
                    ArchivePaths.openNewFile(path).use { output ->
                        val buffer = ByteArray(8192)
                        while (true) {
                            val read = tar.read(buffer)
                            if (read < 0) break
                            unpacked[0] += read
                            if (unpacked[0] > MAX_UNPACKED) {
                                throw IllegalStateException("Unpacked Android SDK is too large.")
                            }
                            output.write(buffer, 0, read)
                        }
                    }
                }
            }
        }
    }

    private fun toolchainRelative(name: String): String? {
        val normalized = name.replace('\\', '/')
        val marker = "com.termux/files/usr/"
        val index = normalized.indexOf(marker)
        if (index < 0) return null
        val rel = normalized.substring(index + marker.length).trimStart('/')
        if (rel.isEmpty() || rel.split('/').any { it == ".." }) return null
        return rel
    }

    private fun skipEntry(rel: String): Boolean =
        rel.startsWith("include/") ||
            rel.startsWith("share/doc/") ||
            rel.startsWith("share/man/") ||
            rel.startsWith("share/info/") ||
            rel.startsWith("share/locale/")

    private fun linkTarget(raw: String): String {
        val normalized = raw.replace('\\', '/')
        val marker = "com.termux/files/usr/"
        val index = normalized.indexOf(marker)
        if (index >= 0) return File(toolchain, normalized.substring(index + marker.length)).path
        if (normalized.startsWith("/")) throw IllegalStateException("Refusing symlink outside the toolchain: $raw")
        return raw
    }

    private fun replaceScript(file: File, text: String) {
        val path = file.toPath()
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.delete(path)
        file.parentFile?.mkdirs()
        file.writeText(lf(text))
        ProgramRun.markExecutable(file)
    }

    private fun lf(text: String): String = text.replace("\r\n", "\n").replace("\r", "\n")

    private fun javaTool(jar: String, main: String): String = """
        #!/system/bin/sh
        ROOT=${'$'}(CDPATH= cd "${'$'}(dirname "${'$'}0")/.." && pwd)
        exec "${'$'}ROOT/bin/java" -cp "${'$'}ROOT/share/java/$jar" $main "${'$'}@"
    """.trimIndent() + "\n"

    private fun javaJar(jar: String): String = """
        #!/system/bin/sh
        ROOT=${'$'}(CDPATH= cd "${'$'}(dirname "${'$'}0")/.." && pwd)
        exec "${'$'}ROOT/bin/java" -jar "${'$'}ROOT/share/java/$jar" "${'$'}@"
    """.trimIndent() + "\n"

    private fun toolWrapper(name: String): String = """
        #!/system/bin/sh
        ROOT=${'$'}(CDPATH= cd "${'$'}(dirname "${'$'}0")/.." && pwd)
        JAVA_HOME="${'$'}ROOT/lib/jvm/java-17-openjdk"
        export JAVA_HOME
        export LD_LIBRARY_PATH="${'$'}JAVA_HOME/lib:${'$'}JAVA_HOME/lib/server:${'$'}ROOT/lib${'$'}{LD_LIBRARY_PATH:+:${'$'}LD_LIBRARY_PATH}"
        exec "${'$'}JAVA_HOME/bin/$name" "${'$'}@"
    """.trimIndent() + "\n"

    private fun gradleWrapper(): String = """
        #!/system/bin/sh
        ROOT=${'$'}(CDPATH= cd "${'$'}(dirname "${'$'}0")/.." && pwd)
        JAVA_HOME="${'$'}ROOT/lib/jvm/java-17-openjdk"
        export JAVA_HOME
        export ANDROID_HOME="${'$'}ROOT/android-sdk"
        export ANDROID_SDK_ROOT="${'$'}ANDROID_HOME"
        export PATH="${'$'}ROOT/bin:${'$'}PATH"
        export LD_LIBRARY_PATH="${'$'}JAVA_HOME/lib:${'$'}JAVA_HOME/lib/server:${'$'}ROOT/lib${'$'}{LD_LIBRARY_PATH:+:${'$'}LD_LIBRARY_PATH}"
        mkdir -p "${'$'}ROOT/tmp"
        LAUNCHER=
        for jar in "${'$'}ROOT/opt/gradle/lib"/gradle-launcher-*.jar; do
          LAUNCHER="${'$'}jar"
          break
        done
        if [ ! -f "${'$'}LAUNCHER" ]; then
          echo "gradle launcher jar not found" >&2
          exit 1
        fi
        exec "${'$'}JAVA_HOME/bin/java" -Dorg.gradle.appname=gradle -Djava.io.tmpdir="${'$'}ROOT/tmp" -Xmx1024m -classpath "${'$'}LAUNCHER" org.gradle.launcher.GradleMain "${'$'}@"
    """.trimIndent() + "\n"

    private fun zipAlignWrapper(): String = """
        #!/system/bin/sh
        ROOT=${'$'}(CDPATH= cd "${'$'}(dirname "${'$'}0")/.." && pwd)
        exec "${'$'}ROOT/bin/java" -cp "${'$'}ROOT/share/java/zipalign-classes" ZipAlign "${'$'}@"
    """.trimIndent() + "\n"

    private fun nativeWrapper(name: String): String = """
        #!/system/bin/sh
        ROOT=${'$'}(CDPATH= cd "${'$'}(dirname "${'$'}0")/../../.." && pwd)
        export LD_LIBRARY_PATH="${'$'}ROOT/lib${'$'}{LD_LIBRARY_PATH:+:${'$'}LD_LIBRARY_PATH}"
        exec "${'$'}ROOT/bin/$name" "${'$'}@"
    """.trimIndent() + "\n"

    private fun binWrapper(name: String): String = """
        #!/system/bin/sh
        ROOT=${'$'}(CDPATH= cd "${'$'}(dirname "${'$'}0")/../../.." && pwd)
        exec "${'$'}ROOT/bin/$name" "${'$'}@"
    """.trimIndent() + "\n"

    private fun readFully(input: InputStream, buffer: ByteArray) {
        if (readFullyOrEof(input, buffer) != buffer.size) throw IllegalStateException("Truncated archive.")
    }

    private fun readFullyOrEof(input: InputStream, buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = input.read(buffer, offset, buffer.size - offset)
            if (read < 0) return offset
            offset += read
        }
        return offset
    }

    private fun skipFully(input: InputStream, bytes: Long) {
        var left = bytes
        while (left > 0) {
            val skipped = input.skip(left)
            if (skipped > 0) {
                left -= skipped
                continue
            }
            if (input.read() < 0) throw IllegalStateException("Truncated archive.")
            left--
        }
    }

    private class BoundedInputStream(private val source: InputStream, private var left: Long) : FilterInputStream(source) {
        override fun read(): Int {
            if (left <= 0) return -1
            val value = source.read()
            if (value >= 0) left--
            return value
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val read = source.read(b, off, minOf(len.toLong(), left).toInt())
            if (read > 0) left -= read
            return read
        }

        override fun skip(n: Long): Long = drain(n)

        override fun close() {
            drain(left)
        }

        private fun drain(n: Long): Long {
            if (n <= 0 || left <= 0) return 0
            var remaining = minOf(n, left)
            var skipped = 0L
            val buf = ByteArray(8192)
            while (remaining > 0) {
                val read = read(buf, 0, minOf(buf.size.toLong(), remaining).toInt())
                if (read < 0) break
                skipped += read
                remaining -= read
            }
            return skipped
        }
    }

    companion object {
        private const val BASE = "https://packages.termux.dev/apt/termux-main/"
        private const val PLATFORM_URL = "https://dl.google.com/android/repository/platform-36_r02.zip"
        private const val API = "36"
        private const val BUILD_TOOLS = "36.0.0"
        private const val STAMP_NAME = "android-sdk-stamp"
        private const val STAMP = "aapt2-16.0.0.4 gradle-9.7.1 platform-36"
        private const val TIMEOUT_SECONDS = 600L
        private const val MAX_DOWNLOAD = 80L * 1024L * 1024L
        private const val GRADLE_DOWNLOAD = 180L * 1024L * 1024L
        private const val MAX_UNPACKED = 800L * 1024L * 1024L
        private val AR_MAGIC = "!<arch>\n".toByteArray(Charsets.US_ASCII)
        private val PLATFORM_FILES = setOf(
            "android.jar",
            "source.properties",
            "package.xml",
            "build.prop",
            "sdk.properties",
            "framework.aidl",
            "core-for-system-modules.jar",
        )
        private val DEBS = listOf(
            "pool/main/z/zlib/zlib_1.3.2_aarch64.deb",
            "pool/main/libc/libc++/libc++_29_aarch64.deb",
            "pool/main/libp/libpng/libpng_1.6.58_aarch64.deb",
            "pool/main/libe/libexpat/libexpat_2.8.5_aarch64.deb",
            "pool/main/libz/libzopfli/libzopfli_1.0.3-5_aarch64.deb",
            "pool/main/f/fmt/fmt_1:11.2.0_aarch64.deb",
            "pool/main/a/abseil-cpp/abseil-cpp_20260526.0_aarch64.deb",
            "pool/main/libp/libprotobuf/libprotobuf_2:35.1_aarch64.deb",
            "pool/main/g/googletest/googletest_1.18.0_aarch64.deb",
            "pool/main/a/aapt/aapt_16.0.0.4-2_aarch64.deb",
            "pool/main/a/aapt2/aapt2_16.0.0.4-2_aarch64.deb",
            "pool/main/a/aidl/aidl_16.0.0.4-2_aarch64.deb",
            "pool/main/a/apksigner/apksigner_37.0.0_all.deb",
            "pool/main/d/d8/d8_37.0.0_all.deb",
            "pool/main/g/gradle/gradle_1:9.7.1-1_all.deb",
        )
        private const val ALREADY =
            "Android SDK platform 36, aapt2, d8, and Gradle 9.7.1 are already installed. " +
                "Run gradle assembleDebug with timeout_seconds of 600 or more."
        private const val INSTALLED =
            "Installed aapt2, aidl, d8, apksigner, zipalign, Gradle 9.7.1, and Android SDK platform 36. " +
                "ANDROID_HOME points at the SDK. Use Android Gradle Plugin 9.4.1, compileSdk 36, " +
                "and buildTools 36.0.0. Build with: gradle assembleDebug. " +
                "The first build downloads plugins, so set timeout_seconds to 600 or more."
    }
}
