package com.hvkeyn.ceditneuro.tools

import com.android.apksig.ApkVerifier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile

class WebAppPackTest {
    @Test
    fun samePageKeepsThePackageId() {
        val first = WebAppPack.packageId("Notes", "url:https://example.com/notes")
        val second = WebAppPack.packageId("Notes", "url:https://example.com/notes")
        val other = WebAppPack.packageId("Notes", "url:https://example.com/other")
        assertEquals(first, second)
        assertTrue(first.startsWith("cedit.web.a"))
        assertEquals(WebAppPack.PACKAGE_SLOT.length, first.length)
        assertTrue(first != other)
    }

    @Test
    fun urlMustBeHttp() {
        assertEquals("https://example.com/a", WebAppPack.validateUrl(" https://example.com/a "))
        assertTrue(runCatching { WebAppPack.validateUrl("javascript:alert(1)") }.isFailure)
        assertTrue(runCatching { WebAppPack.validateUrl("file:///sdcard/index.html") }.isFailure)
        assertTrue(runCatching { WebAppPack.validateUrl("https://user:secret@example.com") }.isFailure)
    }

    @Test
    fun localFolderBecomesIndexAndKeepsCss() {
        val dir = kotlin.io.path.createTempDirectory("site").toFile()
        try {
            File(dir, "index.html").writeText("<html></html>")
            File(dir, "app.css").writeText("body{}")
            File(dir, "secret.env").writeText("nope")
            val packed = WebAppPack.collectSite(dir).map { it.first }.sorted()
            assertEquals(listOf("assets/www/app.css", "assets/www/index.html"), packed)
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun shellBecomesASignedApp() {
        val template = shellApk()
        val templateBytes = template.readBytes()
        assertFalse(String(templateBytes, StandardCharsets.ISO_8859_1).contains("cedit.web.aaaaaaaa classes"))
        ZipFile(template).use { zip ->
            val dex = zip.getEntry("classes.dex")
            assertTrue(dex != null)
            val dexBytes = zip.getInputStream(dex).readBytes()
            assertFalse(contains(dexBytes, WebAppPack.PACKAGE_SLOT))
            assertFalse(contains(dexBytes, WebAppPack.LABEL_SLOT))
            val manifest = zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readBytes()
            val resources = zip.getInputStream(zip.getEntry("resources.arsc")).readBytes()
            assertTrue(contains(manifest, WebAppPack.PACKAGE_SLOT) || containsUtf16(manifest, WebAppPack.PACKAGE_SLOT))
            assertTrue(contains(resources, WebAppPack.LABEL_SLOT) || containsUtf16(resources, WebAppPack.LABEL_SLOT))
        }
        val dir = kotlin.io.path.createTempDirectory("webapp").toFile()
        try {
            val output = File(dir, "out.apk")
            val built = WebAppPack.pack(
                template = templateBytes,
                name = "Заметки",
                source = "url:https://example.com/notes",
                startUrl = "https://example.com/notes",
                site = emptyList(),
                identityDir = File(dir, "keys"),
                output = output,
            )
            assertEquals("Заметки", built.label)
            assertEquals("Заметки", built.launcherLabel)
            assertTrue(output.isFile && output.length() > 0)
            val verified = ApkVerifier.Builder(output).build().verify()
            assertTrue(verified.errors.joinToString("\n"), verified.isVerified)
            ZipFile(output).use { zip ->
                val manifest = zip.getInputStream(zip.getEntry("AndroidManifest.xml")).readBytes()
                val resources = zip.getInputStream(zip.getEntry("resources.arsc")).readBytes()
                val start = zip.getInputStream(zip.getEntry("assets/start.txt")).readBytes()
                val title = zip.getInputStream(zip.getEntry("assets/title.txt")).readBytes()
                assertFalse(contains(manifest, WebAppPack.PACKAGE_SLOT))
                assertFalse(containsUtf16(manifest, WebAppPack.PACKAGE_SLOT))
                assertTrue(contains(manifest, built.packageId) || containsUtf16(manifest, built.packageId))
                assertFalse(contains(resources, WebAppPack.LABEL_SLOT))
                assertFalse(containsUtf16(resources, WebAppPack.LABEL_SLOT))
                assertTrue(
                    containsUtf8(resources, "Заметки") || containsUtf16(resources, "Заметки"),
                )
                assertEquals("https://example.com/notes", String(start, StandardCharsets.UTF_8).trim())
                assertEquals("Заметки", String(title, StandardCharsets.UTF_8).trim())
            }
            val again = WebAppPack.pack(
                template = templateBytes,
                name = "Заметки",
                source = "url:https://example.com/notes",
                startUrl = "https://example.com/notes",
                site = emptyList(),
                identityDir = File(dir, "keys"),
                output = File(dir, "out2.apk"),
            )
            assertEquals(built.packageId, again.packageId)
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun shellApk(): File {
        val candidates = listOf(
            File("webviewshell/build/outputs/apk/debug/webviewshell-debug.apk"),
            File("../webviewshell/build/outputs/apk/debug/webviewshell-debug.apk"),
        )
        return candidates.firstOrNull { it.isFile }
            ?: error("Missing web shell APK. Looked in ${candidates.joinToString { it.absolutePath }}")
    }

    private fun contains(data: ByteArray, text: String): Boolean =
        indexOf(data, text.toByteArray(StandardCharsets.US_ASCII)) >= 0

    private fun containsUtf8(data: ByteArray, text: String): Boolean =
        indexOf(data, text.toByteArray(StandardCharsets.UTF_8)) >= 0

    private fun containsUtf16(data: ByteArray, text: String): Boolean {
        val needle = ByteArray(text.length * 2)
        text.forEachIndexed { index, char ->
            needle[index * 2] = (char.code and 0xFF).toByte()
            needle[index * 2 + 1] = (char.code ushr 8).toByte()
        }
        return indexOf(data, needle) >= 0
    }

    private fun indexOf(data: ByteArray, needle: ByteArray): Int {
        if (needle.isEmpty() || needle.size > data.size) return -1
        for (index in 0..data.size - needle.size) {
            var matched = true
            for (offset in needle.indices) {
                if (data[index + offset] != needle[offset]) {
                    matched = false
                    break
                }
            }
            if (matched) return index
        }
        return -1
    }
}
