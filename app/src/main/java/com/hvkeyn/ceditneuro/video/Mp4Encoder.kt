package com.hvkeyn.ceditneuro.video

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File

/** H.264 in an MP4 through the phone's own encoder. One frame in, written at its own timestamp. */
class Mp4Encoder(
    private val out: File,
    private val width: Int,
    private val height: Int,
    private val fps: Int,
) {
    private val codec: MediaCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val muxer: MediaMuxer
    private var track = -1
    private var started = false
    private val info = MediaCodec.BufferInfo()
    private val pixels = IntArray(width * height)
    var frames = 0
        private set

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, (width.toLong() * height * fps / 6).coerceIn(1_000_000L, 20_000_000L).toInt())
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        codec.start()
        out.parentFile?.mkdirs()
        if (out.exists()) out.delete()
        muxer = MediaMuxer(out.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    }

    fun add(bitmap: Bitmap) {
        bitmap.getPixels(pixels, 0, width, 0, 0, width, height)
        var index: Int
        do {
            index = codec.dequeueInputBuffer(10_000)
            if (index < 0) drain(false)
        } while (index < 0)
        val image = codec.getInputImage(index) ?: error("The encoder gave no input image.")
        writeYuv(image)
        val pts = frames * 1_000_000L / fps
        codec.queueInputBuffer(index, 0, width * height * 3 / 2, pts, 0)
        frames++
        drain(false)
    }

    fun finish() {
        var index: Int
        do {
            index = codec.dequeueInputBuffer(10_000)
            if (index < 0) drain(false)
        } while (index < 0)
        codec.queueInputBuffer(index, 0, 0, frames * 1_000_000L / fps, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
        drain(true)
        release()
    }

    fun release() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        if (started) runCatching { muxer.stop() }
        runCatching { muxer.release() }
    }

    private fun drain(end: Boolean) {
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                    started = true
                }
                index >= 0 -> {
                    val buffer = codec.getOutputBuffer(index)
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (buffer != null && info.size > 0 && started && !config) {
                        buffer.position(info.offset)
                        buffer.limit(info.offset + info.size)
                        muxer.writeSampleData(track, buffer, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun writeYuv(image: android.media.Image) {
        val planes = image.planes
        val y = planes[0]
        val u = planes[1]
        val v = planes[2]
        val yBuf = y.buffer
        val uBuf = u.buffer
        val vBuf = v.buffer
        val yRow = ByteArray(width)
        for (row in 0 until height) {
            val base = row * width
            for (col in 0 until width) {
                val c = pixels[base + col]
                val r = c shr 16 and 0xFF
                val g = c shr 8 and 0xFF
                val b = c and 0xFF
                yRow[col] = (((66 * r + 129 * g + 25 * b + 128) shr 8) + 16).toByte()
            }
            if (y.pixelStride == 1) {
                yBuf.position(row * y.rowStride)
                yBuf.put(yRow)
            } else {
                for (col in 0 until width) yBuf.put(row * y.rowStride + col * y.pixelStride, yRow[col])
            }
        }
        for (row in 0 until height / 2) {
            val base = row * 2 * width
            for (col in 0 until width / 2) {
                val c = pixels[base + col * 2]
                val r = c shr 16 and 0xFF
                val g = c shr 8 and 0xFF
                val b = c and 0xFF
                val cb = (((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte()
                val cr = (((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte()
                uBuf.put(row * u.rowStride + col * u.pixelStride, cb)
                vBuf.put(row * v.rowStride + col * v.pixelStride, cr)
            }
        }
    }
}
