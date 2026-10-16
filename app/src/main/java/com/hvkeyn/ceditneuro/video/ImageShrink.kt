package com.hvkeyn.ceditneuro.video

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.File

/** Writes a smaller jpg, png, or webp with the phone's own picture encoder. */
object ImageShrink {
    private const val MAX_BYTES = 40L * 1024 * 1024

    data class Report(val width: Int, val height: Int, val before: Long, val after: Long)

    fun write(input: File, output: File, quality: Int, maxSide: Int?): Report {
        if (!input.isFile) error("No picture at ${input.name}.")
        if (input.length() > MAX_BYTES) error("The picture is over 40 MB.")
        if (input.canonicalFile == output.canonicalFile) error("Pick a different output file.")
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(input.absolutePath, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("Could not read ${input.name}.")
        val q = ShrinkPlan.quality(quality)
        val (width, height) = ShrinkPlan.frame(bounds.outWidth, bounds.outHeight, q, maxSide)
        val options = BitmapFactory.Options().apply {
            inSampleSize = ShrinkPlan.sampleSize(bounds.outWidth, bounds.outHeight, maxOf(width, height))
            inPreferredConfig = Bitmap.Config.ARGB_8888
        }
        val decoded = BitmapFactory.decodeFile(input.absolutePath, options)
            ?: error("Could not read ${input.name}.")
        val scaled = if (decoded.width == width && decoded.height == height) {
            decoded
        } else {
            Bitmap.createScaledBitmap(decoded, width, height, true)
        }
        try {
            if (output.extension.lowercase() == "png") {
                val pixels = IntArray(scaled.width * scaled.height)
                scaled.getPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
                ShrinkPlan.quantize(pixels, q)
                scaled.setPixels(pixels, 0, scaled.width, 0, 0, scaled.width, scaled.height)
            }
            val format = when (output.extension.lowercase()) {
                "jpg", "jpeg" -> Bitmap.CompressFormat.JPEG
                "webp" -> Bitmap.CompressFormat.WEBP
                "png" -> Bitmap.CompressFormat.PNG
                else -> error("Use jpg, png, or webp.")
            }
            val level = if (format == Bitmap.CompressFormat.PNG) 100 else q
            output.parentFile?.mkdirs()
            output.outputStream().use { stream ->
                if (!scaled.compress(format, level, stream)) error("The picture encoder stopped.")
            }
        } finally {
            if (scaled !== decoded) scaled.recycle()
            decoded.recycle()
        }
        return Report(width, height, input.length(), output.length())
    }
}
