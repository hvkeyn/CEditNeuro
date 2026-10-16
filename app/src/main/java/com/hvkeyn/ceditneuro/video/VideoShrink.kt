package com.hvkeyn.ceditneuro.video

import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import java.io.File
import java.nio.ByteBuffer

/** Writes a smaller mp4 with the phone's own H.264 encoder. AAC sound is copied when it is present. */
object VideoShrink {
    private const val MAX_BYTES = 200L * 1024 * 1024
    private const val MAX_MS = 180_000

    data class Report(
        val width: Int,
        val height: Int,
        val fps: Int,
        val before: Long,
        val after: Long,
        val audio: Boolean,
    )

    fun write(input: File, output: File, quality: Int, maxSide: Int?): Report {
        if (!input.isFile) error("No video at ${input.name}.")
        if (input.length() > MAX_BYTES) error("The video is over 200 MB.")
        if (input.canonicalFile == output.canonicalFile) error("Pick a different output file.")
        val q = ShrinkPlan.quality(quality)
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(input.absolutePath)
            val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                ?: error("Could not read ${input.name}.")
            val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                ?: error("Could not read ${input.name}.")
            val durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                ?: error("Could not read the length of ${input.name}.")
            if (durationMs <= 0L || durationMs > MAX_MS) error("A video to shrink stays under 3 minutes.")
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            val turned = rotation == 90 || rotation == 270
            val srcW = if (turned) height else width
            val srcH = if (turned) width else height
            val (frameW, frameH) = ShrinkPlan.frame(srcW, srcH, q, maxSide)
            val fps = ShrinkPlan.videoFps(q)
            val bitrate = ShrinkPlan.videoBitrate(frameW, frameH, q, fps)
            val audio = copyAac(input, durationMs * 1000L)
            val encoder = Mp4Encoder(output, frameW, frameH, fps, audio, bitrate)
            try {
                var at = 0L
                val step = 1_000_000L / fps
                val end = durationMs * 1000L
                while (at < end) {
                    val frame = retriever.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST) ?: break
                    val upright = turn(frame, rotation)
                    val scaled = if (upright.width == frameW && upright.height == frameH) {
                        upright
                    } else {
                        Bitmap.createScaledBitmap(upright, frameW, frameH, true)
                    }
                    encoder.add(scaled)
                    if (scaled !== upright) scaled.recycle()
                    if (upright !== frame) upright.recycle()
                    frame.recycle()
                    at += step
                }
                if (encoder.frames == 0) error("No frames could be read from ${input.name}.")
                encoder.finish()
            } catch (error: Throwable) {
                encoder.release()
                output.delete()
                throw error
            }
            return Report(frameW, frameH, fps, input.length(), output.length(), audio != null)
        } finally {
            runCatching { retriever.release() }
        }
    }

    private fun turn(frame: Bitmap, rotation: Int): Bitmap {
        if (rotation == 0) return frame
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        return Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, matrix, true)
    }

    private fun copyAac(file: File, maxUs: Long): AudioMix.Encoded? {
        val extractor = MediaExtractor()
        return try {
            extractor.setDataSource(file.absolutePath)
            val index = (0 until extractor.trackCount).firstOrNull { track ->
                extractor.getTrackFormat(track).getString(MediaFormat.KEY_MIME) == MediaFormat.MIMETYPE_AUDIO_AAC
            } ?: return null
            val format = extractor.getTrackFormat(index)
            extractor.selectTrack(index)
            val samples = ArrayList<AudioMix.Sample>()
            val buffer = ByteBuffer.allocate(256 * 1024)
            while (true) {
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                val time = extractor.sampleTime
                if (time > maxUs) break
                val bytes = ByteArray(size)
                buffer.position(0)
                buffer.get(bytes)
                samples += AudioMix.Sample(bytes, time.coerceAtLeast(0L), 0)
                extractor.advance()
            }
            if (samples.isEmpty()) null else AudioMix.Encoded(format, samples)
        } catch (_: Throwable) {
            null
        } finally {
            runCatching { extractor.release() }
        }
    }
}
