package com.hvkeyn.ceditneuro.video

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import java.io.File
import java.nio.ByteOrder

/**
 * The composition's audio elements mixed into one AAC track: each file decoded once,
 * placed at its data-start, trimmed by data-media-start and data-duration, scaled by
 * data-volume and its volume lane, and resampled to 44.1 kHz stereo.
 */
object AudioMix {
    const val RATE = 44_100

    data class Track(
        val id: String,
        val file: File,
        val start: Double,
        val duration: Double?,
        val mediaStart: Double,
        val volume: Double,
        val rate: Double,
        val lane: List<Pair<Double, Double>>,
    )

    class Encoded(val format: MediaFormat, val samples: List<Sample>)
    class Sample(val data: ByteArray, val pts: Long, val flags: Int)

    private class Pcm(val rate: Int, val channels: Int, val data: ShortArray) {
        val frames: Int get() = data.size / channels
    }

    /** A lane holds its first value before the first point and its last value after the last one. */
    fun laneValue(points: List<Pair<Double, Double>>, t: Double): Double {
        if (points.isEmpty()) return 1.0
        if (t <= points.first().first) return points.first().second
        if (t >= points.last().first) return points.last().second
        for (i in 1 until points.size) {
            val (t1, v1) = points[i]
            if (t <= t1) {
                val (t0, v0) = points[i - 1]
                val span = t1 - t0
                return if (span <= 0) v1 else v0 + (v1 - v0) * (t - t0) / span
            }
        }
        return points.last().second
    }

    /** Mixes [tracks] over [seconds] and encodes AAC. Returns null when nothing could be decoded. */
    fun mix(tracks: List<Track>, seconds: Double, skipped: MutableList<String>): Encoded? {
        val decoded = tracks.mapNotNull { track ->
            val need = (track.duration ?: (seconds - track.start)).coerceAtLeast(0.0) * track.rate
            if (need <= 0.0) return@mapNotNull null
            val pcm = runCatching { decode(track.file, track.mediaStart, need) }.getOrNull()
            if (pcm == null || pcm.frames == 0) {
                skipped += "${track.id} (${track.file.name} could not be decoded)"
                null
            } else {
                track to pcm
            }
        }
        if (decoded.isEmpty()) return null
        val total = (seconds * RATE).toInt()
        val encoder = AacEncoder()
        val chunk = 2048
        val out = ShortArray(chunk * 2)
        var frame = 0
        while (frame < total) {
            val count = minOf(chunk, total - frame)
            for (i in 0 until count) {
                val time = (frame + i).toDouble() / RATE
                var l = 0.0
                var r = 0.0
                for ((track, pcm) in decoded) {
                    val local = time - track.start
                    if (local < 0) continue
                    if (track.duration != null && local >= track.duration) continue
                    val position = local * track.rate * pcm.rate
                    val index = position.toInt()
                    if (index + 1 >= pcm.frames) continue
                    val frac = position - index
                    val gain = track.volume * laneValue(track.lane, local)
                    val c = pcm.channels
                    val a0 = pcm.data[index * c].toDouble()
                    val a1 = pcm.data[(index + 1) * c].toDouble()
                    val b0 = if (c > 1) pcm.data[index * c + 1].toDouble() else a0
                    val b1 = if (c > 1) pcm.data[(index + 1) * c + 1].toDouble() else a1
                    l += (a0 + (a1 - a0) * frac) * gain
                    r += (b0 + (b1 - b0) * frac) * gain
                }
                out[i * 2] = limit(l)
                out[i * 2 + 1] = limit(r)
            }
            encoder.feed(out, count, frame.toLong() * 1_000_000L / RATE)
            frame += count
        }
        return encoder.finish(total.toLong() * 1_000_000L / RATE)
    }

    private fun limit(value: Double): Short {
        val x = value / 32768.0
        val soft = if (x > 0.9) 0.9 + (1 - 0.9) * kotlin.math.tanh((x - 0.9) / 0.1)
        else if (x < -0.9) -0.9 - (1 - 0.9) * kotlin.math.tanh((-x - 0.9) / 0.1)
        else x
        return (soft * 32767).toInt().coerceIn(-32768, 32767).toShort()
    }

    /** Decodes [seconds] of [file] from [from], as 16-bit PCM at the file's own rate. */
    private fun decode(file: File, from: Double, seconds: Double): Pcm? {
        val extractor = MediaExtractor()
        val input = java.io.FileInputStream(file)
        try {
            extractor.setDataSource(input.fd)
            val trackIndex = (0 until extractor.trackCount).firstOrNull {
                extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true
            } ?: return null
            extractor.selectTrack(trackIndex)
            val format = extractor.getTrackFormat(trackIndex)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val fromUs = (from * 1_000_000).toLong()
            if (fromUs > 0) extractor.seekTo(fromUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var data = ShortArray(1 shl 16)
            var size = 0
            var inputDone = false
            val endUs = fromUs + (seconds * 1_000_000).toLong()
            try {
                while (true) {
                    if (!inputDone) {
                        val index = codec.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val buffer = codec.getInputBuffer(index)!!
                            val read = extractor.readSampleData(buffer, 0)
                            if (read < 0 || extractor.sampleTime > endUs + 200_000) {
                                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(index, 0, read, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val out = codec.dequeueOutputBuffer(info, 10_000)
                    if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        rate = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } else if (out >= 0) {
                        val buffer = codec.getOutputBuffer(out)
                        if (buffer != null && info.size > 0) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val shorts = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                            val n = shorts.remaining()
                            val frameUs = info.presentationTimeUs
                            val skipFrames = if (frameUs < fromUs) ((fromUs - frameUs) * rate / 1_000_000L).toInt() else 0
                            val skip = (skipFrames * channels).coerceAtMost(n)
                            val keep = n - skip
                            if (keep > 0) {
                                if (size + keep > data.size) data = data.copyOf(maxOf(data.size * 2, size + keep))
                                shorts.position(skip)
                                shorts.get(data, size, keep)
                                size += keep
                            }
                        }
                        codec.releaseOutputBuffer(out, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                        if (size / channels.coerceAtLeast(1) > (seconds + 0.5) * rate) break
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            return Pcm(rate, channels.coerceAtLeast(1), data.copyOf(size))
        } finally {
            extractor.release()
            input.close()
        }
    }

    private class AacEncoder {
        private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
        private val info = MediaCodec.BufferInfo()
        private val samples = ArrayList<Sample>()
        private var format: MediaFormat? = null

        init {
            val config = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, RATE, 2).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 160_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16_384)
            }
            codec.configure(config, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        }

        fun feed(pcm: ShortArray, frames: Int, pts: Long) {
            var offset = 0
            var at = pts
            while (offset < frames) {
                val index = codec.dequeueInputBuffer(10_000)
                if (index < 0) { drain(false); continue }
                val buffer = codec.getInputBuffer(index)!!
                buffer.clear()
                val room = buffer.remaining() / 4
                val n = minOf(room, frames - offset)
                val shorts = buffer.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                shorts.put(pcm, offset * 2, n * 2)
                codec.queueInputBuffer(index, 0, n * 4, at, 0)
                offset += n
                at += n * 1_000_000L / RATE
                drain(false)
            }
        }

        fun finish(pts: Long): Encoded? {
            while (true) {
                val index = codec.dequeueInputBuffer(10_000)
                if (index >= 0) {
                    codec.queueInputBuffer(index, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                    break
                }
                drain(false)
            }
            drain(true)
            runCatching { codec.stop() }
            codec.release()
            val f = format ?: return null
            return Encoded(f, samples)
        }

        private fun drain(end: Boolean) {
            while (true) {
                val index = codec.dequeueOutputBuffer(info, if (end) 10_000 else 0)
                when {
                    index == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!end) return
                    index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> format = codec.outputFormat
                    index >= 0 -> {
                        val buffer = codec.getOutputBuffer(index)
                        val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                        if (buffer != null && info.size > 0 && !config) {
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            val bytes = ByteArray(info.size)
                            buffer.get(bytes)
                            samples += Sample(bytes, info.presentationTimeUs, info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM.inv())
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                    }
                }
            }
        }
    }
}
