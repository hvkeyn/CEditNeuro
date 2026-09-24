package com.hvkeyn.ceditneuro.agent

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

/**
 * Copies a picked file into the project and turns it into text the model can use
 * on every later turn. Vision models also receive the image bytes.
 */
object Attachments {
    private const val TEXT_CAP = 60_000
    private const val IMAGE_EDGE = 1280

    data class Prepared(
        val prompt: String,
        val imageDataUrls: List<String>,
    )

    fun prepare(context: Context, projectRoot: File, prompt: String, uris: List<Uri>): Prepared {
        if (uris.isEmpty()) return Prepared(prompt, emptyList())
        val inbox = File(projectRoot, ".ceditneuro/inbox").apply { mkdirs() }
        val blocks = StringBuilder()
        val images = mutableListOf<String>()
        uris.forEach { uri ->
            val saved = copyIn(context, inbox, uri) ?: return@forEach
            blocks.append("\n\n--- attached file ---\n")
            blocks.append("path: ").append(saved.absolutePath).append('\n')
            blocks.append("name: ").append(saved.name).append('\n')
            blocks.append("bytes: ").append(saved.length()).append('\n')
            val text = readText(saved)
            if (text != null) {
                blocks.append("text:\n").append(text)
            } else if (isImage(saved)) {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(saved.absolutePath, bounds)
                blocks.append("image: ").append(bounds.outWidth).append('x').append(bounds.outHeight).append('\n')
                imageDataUrl(saved)?.let { images += it }
            } else {
                blocks.append("binary file. Use read_file only if it is text.\n")
            }
        }
        val body = buildString {
            append(prompt.trim())
            if (isNotEmpty()) append("\n")
            append("The files below are saved in this project. Use the text and paths as the real content.")
            append(blocks)
        }
        return Prepared(body, images)
    }

    private fun copyIn(context: Context, inbox: File, uri: Uri): File? = runCatching {
        val name = displayName(context, uri).ifBlank { "file" }.replace(Regex("[^A-Za-z0-9._-]"), "_")
        val dest = File(inbox, "${System.currentTimeMillis()}-$name")
        context.contentResolver.openInputStream(uri)?.use { input ->
            FileOutputStream(dest).use { input.copyTo(it) }
        } ?: return null
        dest
    }.getOrNull()

    private fun displayName(context: Context, uri: Uri): String {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index >= 0) return cursor.getString(index).orEmpty()
            }
        }
        return uri.lastPathSegment.orEmpty()
    }

    private fun readText(file: File): String? {
        val ext = file.extension.lowercase()
        val textLike = ext in setOf(
            "txt", "md", "markdown", "json", "csv", "xml", "html", "htm", "log",
            "kt", "kts", "java", "py", "js", "ts", "css", "yaml", "yml", "toml",
            "ini", "cfg", "conf", "sh", "gradle",
        )
        if (!textLike && file.length() > 256_000) return null
        val sample = runCatching { file.inputStream().use { it.readNBytes(4096) } }.getOrNull() ?: return null
        if (sample.any { it.toInt() == 0 }) return null
        if (!textLike && sample.count { it < 9 || (it in 14..31) } > sample.size / 10) return null
        val text = runCatching { file.readText() }.getOrNull() ?: return null
        return if (text.length > TEXT_CAP) text.take(TEXT_CAP) + "\n... truncated, read the file for the rest" else text
    }

    private fun isImage(file: File) = file.extension.lowercase() in setOf("jpg", "jpeg", "png", "webp", "gif")

    private fun imageDataUrl(file: File): String? = runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        var sample = 1
        val edge = maxOf(bounds.outWidth, bounds.outHeight).coerceAtLeast(1)
        while (edge / sample > IMAGE_EDGE) sample *= 2
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            ?: return null
        val bytes = java.io.ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, bytes)
        bitmap.recycle()
        "data:image/jpeg;base64," + android.util.Base64.encodeToString(bytes.toByteArray(), android.util.Base64.NO_WRAP)
    }.getOrNull()
}
