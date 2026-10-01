package com.hvkeyn.ceditneuro.video

import java.io.BufferedOutputStream
import java.io.File
import java.io.RandomAccessFile
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.tanh
import kotlin.random.Random

/**
 * A music bed made on the phone. The agent picks the mood, tempo, key, scale, length, and
 * intensity from the brief; this writes the arrangement (pad, arpeggio, bass, drums, melody)
 * and renders it in chunks, so a long bed does not sit in memory.
 */
object MusicSynth {
    enum class Scale(val harmony: IntArray, val melody: IntArray) {
        MAJOR(intArrayOf(0, 2, 4, 5, 7, 9, 11), intArrayOf(0, 2, 4, 5, 7, 9, 11)),
        MINOR(intArrayOf(0, 2, 3, 5, 7, 8, 10), intArrayOf(0, 2, 3, 5, 7, 8, 10)),
        DORIAN(intArrayOf(0, 2, 3, 5, 7, 9, 10), intArrayOf(0, 2, 3, 5, 7, 9, 10)),
        HARMONIC_MINOR(intArrayOf(0, 2, 3, 5, 7, 8, 11), intArrayOf(0, 2, 3, 5, 7, 8, 11)),
        PENTATONIC(intArrayOf(0, 2, 4, 5, 7, 9, 11), intArrayOf(0, 2, 4, 7, 9)),
        MINOR_PENTATONIC(intArrayOf(0, 2, 3, 5, 7, 8, 10), intArrayOf(0, 3, 5, 7, 10)),
    }

    enum class Mood(
        val bpm: Int,
        val scale: Scale,
        val progression: IntArray,
        val bass: Int,
        val drums: Int,
        val arp: Voice?,
        val lead: Voice,
        val tom: Boolean = false,
    ) {
        CALM(84, Scale.MAJOR, intArrayOf(0, 5, 3, 4), bass = 1, drums = 1, arp = Voice.PIANO, lead = Voice.PIANO),
        UPLIFTING(108, Scale.MAJOR, intArrayOf(0, 4, 5, 3), bass = 2, drums = 3, arp = Voice.PIANO, lead = Voice.LEAD),
        CORPORATE(108, Scale.MAJOR, intArrayOf(0, 5, 3, 4), bass = 2, drums = 3, arp = Voice.PLUCK, lead = Voice.PIANO),
        CINEMATIC(92, Scale.MINOR, intArrayOf(0, 5, 2, 6), bass = 3, drums = 0, arp = Voice.PIANO, lead = Voice.LEAD, tom = true),
        EPIC(120, Scale.HARMONIC_MINOR, intArrayOf(0, 5, 2, 4), bass = 3, drums = 2, arp = Voice.PLUCK, lead = Voice.LEAD, tom = true),
        TENSE(100, Scale.MINOR, intArrayOf(0, 0, 5, 4), bass = 4, drums = 4, arp = Voice.PLUCK, lead = Voice.LEAD),
        PLAYFUL(115, Scale.PENTATONIC, intArrayOf(0, 3, 4, 3), bass = 2, drums = 1, arp = Voice.PLUCK, lead = Voice.PLUCK),
        DARK(76, Scale.MINOR, intArrayOf(0, 5, 0, 6), bass = 1, drums = 0, arp = null, lead = Voice.PAD, tom = true),
        AMBIENT(70, Scale.DORIAN, intArrayOf(0, 3, 5, 4), bass = 1, drums = 0, arp = Voice.PIANO, lead = Voice.PAD),
        ELECTRONIC(100, Scale.MINOR_PENTATONIC, intArrayOf(0, 5, 3, 6), bass = 4, drums = 3, arp = Voice.PLUCK, lead = Voice.LEAD),
    }

    enum class Voice { PAD, PIANO, PLUCK, LEAD, BASS, KICK, SNARE, HAT, CLAP, TOM }

    data class Spec(
        val mood: Mood,
        val bpm: Int,
        val root: Int,
        val scale: Scale,
        val seconds: Double,
        val intensity: Double,
        val seed: Long,
    ) {
        fun describe(): String =
            "${mood.name.lowercase()}, $bpm BPM, ${NOTE_NAMES[root]} ${scale.name.lowercase().replace('_', ' ')}, " +
                "intensity ${"%.2f".format(java.util.Locale.US, intensity)}, seed $seed, ${"%.1f".format(java.util.Locale.US, seconds)} s"
    }

    val NOTE_NAMES = listOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")

    /** The words of a brief decide the mood, the way the HyperFrames engine infers its prompt. */
    fun moodFor(text: String): Mood {
        val t = text.lowercase()
        fun has(vararg words: String) = words.any { t.contains(it) }
        return when {
            has("epic", "trailer", "battle", "heroic", "эпич", "битв", "героич", "трейлер") -> Mood.EPIC
            has("tense", "suspense", "thriller", "danger", "напряж", "тревож", "триллер", "опасн") -> Mood.TENSE
            has("dark", "horror", "sinister", "мрач", "тёмн", "темн", "ужас", "зловещ") -> Mood.DARK
            has("ambient", "space", "dream", "meditat", "эмбиент", "космос", "космич", "медитац", "мечта") -> Mood.AMBIENT
            has("cinematic", "film", "drama", "story", "кино", "фильм", "драм", "истори") -> Mood.CINEMATIC
            has("crypto", "nft", "web3", "defi", "token", "blockchain", "electronic", "synth", "techno", "cyber", "крипт", "электрон", "синт", "техно", "кибер") -> Mood.ELECTRONIC
            has("finance", "fintech", "bank", "payment", "invest", "wealth", "calm", "soft", "relax", "gentle", "финанс", "банк", "спокой", "мягк", "нежн", "тих") -> Mood.CALM
            has("creative", "agency", "design", "studio", "art", "fun", "playful", "kids", "креатив", "дизайн", "студи", "весел", "игрив", "дет") -> Mood.PLAYFUL
            has("corporate", "business", "корпорат", "бизнес") -> Mood.CORPORATE
            else -> Mood.UPLIFTING
        }
    }

    fun spec(
        prompt: String,
        seconds: Double,
        mood: Mood? = null,
        bpm: Int? = null,
        key: String? = null,
        scale: Scale? = null,
        intensity: Double? = null,
        seed: Long? = null,
    ): Spec {
        val chosen = mood ?: moodFor(prompt)
        val lower = prompt.lowercase()
        val stated = Regex("""(\d{2,3})\s*bpm""").find(lower)?.groupValues?.get(1)?.toIntOrNull()
        val pickedScale = scale ?: when {
            lower.contains("minor") || lower.contains("минор") -> if (chosen.scale == Scale.PENTATONIC) Scale.MINOR_PENTATONIC else Scale.MINOR
            lower.contains("major") || lower.contains("мажор") -> if (chosen.scale == Scale.MINOR_PENTATONIC) Scale.PENTATONIC else Scale.MAJOR
            else -> chosen.scale
        }
        val root = key?.let { noteIndex(it) } ?: (if (pickedScale.harmony[2] == 3) 9 else 0)
        val level = intensity ?: when (chosen) {
            Mood.AMBIENT, Mood.DARK, Mood.CALM -> 0.35
            Mood.EPIC, Mood.ELECTRONIC, Mood.TENSE -> 0.8
            else -> 0.6
        }
        return Spec(
            mood = chosen,
            bpm = (bpm ?: stated ?: chosen.bpm).coerceIn(50, 180),
            root = root,
            scale = pickedScale,
            seconds = seconds.coerceIn(1.0, 300.0),
            intensity = level.coerceIn(0.0, 1.0),
            seed = seed ?: (prompt.hashCode().toLong() and 0xFFFF),
        )
    }

    fun noteIndex(name: String): Int? {
        val clean = name.trim().replace('♯', '#').replace('♭', 'b')
        if (clean.isEmpty()) return null
        val base = when (clean[0].uppercaseChar()) {
            'C' -> 0; 'D' -> 2; 'E' -> 4; 'F' -> 5; 'G' -> 7; 'A' -> 9; 'B', 'H' -> 11
            else -> return null
        }
        val shift = when (clean.getOrNull(1)) { '#' -> 1; 'b' -> -1; else -> 0 }
        return (base + shift + 12) % 12
    }

    class Note(
        val start: Double,
        val length: Double,
        val midi: Int,
        val velocity: Float,
        val voice: Voice,
        val pan: Float,
        val send: Float,
        val id: Int,
    ) {
        val end: Double get() = start + length + release(voice)
    }

    fun arrange(spec: Spec): List<Note> {
        val random = Random(spec.seed)
        val beat = 60.0 / spec.bpm
        val bar = beat * 4
        val bars = kotlin.math.ceil(spec.seconds / bar).toInt().coerceAtLeast(1)
        val notes = ArrayList<Note>()
        var id = 0
        fun add(start: Double, length: Double, midi: Int, velocity: Float, voice: Voice, pan: Float = 0f, send: Float = 0f) {
            if (start >= spec.seconds) return
            notes += Note(start, length, midi, velocity, voice, pan, send, id++)
        }
        val mood = spec.mood
        val level = spec.intensity
        val harmony = spec.scale.harmony
        val melody = spec.scale.melody
        fun pitch(steps: IntArray, degree: Int, octave: Int): Int {
            val n = steps.size
            val wrapped = Math.floorMod(degree, n)
            val carry = Math.floorDiv(degree, n)
            return 12 * (octave + 1) + spec.root + steps[wrapped] + 12 * carry
        }
        val motif = IntArray(8) { random.nextInt(-2, 3) }
        val rhythm = BooleanArray(8) { it == 0 || random.nextFloat() < 0.45f + level.toFloat() * 0.2f }
        for (b in 0 until bars) {
            val t0 = b * bar
            val chord = mood.progression[b % mood.progression.size]
            val intro = b < 2 && bars > 6
            val outro = b >= bars - 2 && bars > 6
            val lift = (b / 8) % 2 == 1 || bars <= 8
            val tones = intArrayOf(chord, chord + 2, chord + 4)

            for ((i, degree) in tones.withIndex()) {
                add(t0, bar * 1.02, pitch(harmony, degree, 4), 0.16f, Voice.PAD, pan = (i - 1) * 0.35f, send = 0.35f)
            }
            add(t0, bar * 1.02, pitch(harmony, chord, 3), 0.12f, Voice.PAD, send = 0.25f)

            val arp = mood.arp
            if (arp != null && level >= 0.2 && !intro) {
                val step = if (mood == Mood.AMBIENT || mood == Mood.DARK) beat else beat / 2
                val count = (bar / step).toInt()
                val order = intArrayOf(0, 1, 2, 3, 2, 1, 0, 1)
                for (k in 0 until count) {
                    val degree = chord + order[k % order.size] * 2
                    val octave = if (k % 8 >= 4) 5 else 4
                    add(t0 + k * step, step * 0.9, pitch(harmony, degree, octave), 0.11f, arp, pan = if (k % 2 == 0) -0.3f else 0.3f, send = 0.4f)
                }
            }

            if (level >= 0.25 || mood.bass >= 3) {
                val low = pitch(harmony, chord, 2)
                when (mood.bass) {
                    1 -> add(t0, bar, low, 0.3f, Voice.BASS)
                    2 -> for (k in 0 until 4) add(t0 + k * beat, beat * 0.85, low, 0.28f, Voice.BASS)
                    3 -> { add(t0, bar / 2, low, 0.32f, Voice.BASS); add(t0 + bar / 2, bar / 2, low, 0.3f, Voice.BASS) }
                    else -> for (k in 0 until 8) add(t0 + k * beat / 2, beat * 0.4, if (k % 4 == 3) low + 12 else low, 0.26f, Voice.BASS)
                }
            }

            if (mood.tom && level >= 0.35 && !intro) {
                add(t0, 0.9, 0, 0.5f, Voice.TOM)
                if (mood == Mood.EPIC) add(t0 + beat * 2.5, 0.9, 0, 0.4f, Voice.TOM)
            }

            if (mood.drums > 0 && level >= 0.4 && !intro && !outro) {
                for (k in 0 until 16) {
                    val t = t0 + k * beat / 4
                    val quarter = k % 4 == 0
                    when (mood.drums) {
                        1 -> {
                            if (k == 0 || k == 8) add(t, 0.5, 0, 0.45f, Voice.KICK)
                            if (k == 4 || k == 12) add(t, 0.3, 0, 0.18f, Voice.CLAP)
                            if (k % 4 == 2) add(t, 0.1, 0, 0.08f, Voice.HAT, pan = 0.25f)
                        }
                        2 -> {
                            if (k == 0 || k == 6 || k == 8 || k == 10) add(t, 0.5, 0, 0.55f, Voice.KICK)
                            if (k == 4 || k == 12) add(t, 0.3, 0, 0.35f, Voice.SNARE)
                            if (k % 2 == 0) add(t, 0.1, 0, 0.09f, Voice.HAT, pan = 0.25f)
                        }
                        3 -> {
                            if (quarter) add(t, 0.5, 0, 0.5f, Voice.KICK)
                            if (k == 4 || k == 12) add(t, 0.3, 0, 0.25f, Voice.CLAP)
                            if (k % 4 == 2) add(t, 0.1, 0, 0.12f, Voice.HAT, pan = 0.25f)
                            if (level > 0.75 && k % 2 == 1) add(t, 0.1, 0, 0.05f, Voice.HAT, pan = -0.25f)
                        }
                        else -> {
                            if (k == 0 || k == 8) add(t, 0.5, 0, 0.5f, Voice.KICK)
                            add(t, 0.1, 0, if (quarter) 0.1f else 0.06f, Voice.HAT, pan = 0.2f)
                            if (k == 12) add(t, 0.3, 0, 0.25f, Voice.SNARE)
                        }
                    }
                }
            }

            if (level >= 0.5 && lift && !intro && !outro) {
                val vary = b % 4 == 3
                var degree = chord * melody.size / 7
                for (k in 0 until 8) {
                    if (!rhythm[k]) continue
                    degree += if (vary && k >= 4) -motif[k] else motif[k]
                    if (k == 0) degree = chord * melody.size / 7 + melody.size
                    val length = if (k == 7 || !rhythm.getOrElse(k + 1) { false }) beat * 0.9 else beat * 0.45
                    add(t0 + k * beat / 2, length, pitch(melody, degree, 4), 0.13f, mood.lead, pan = 0.1f, send = 0.35f)
                }
            }
        }
        notes.sortBy { it.start }
        return notes
    }

    /** Renders [spec] as 16-bit stereo, calling [sink] with interleaved chunks. */
    fun render(spec: Spec, rate: Int, sink: (ShortArray, Int) -> Unit) {
        val notes = arrange(spec)
        val total = (spec.seconds * rate).toInt()
        val chunk = 4096
        val dryL = FloatArray(chunk)
        val dryR = FloatArray(chunk)
        val sendL = FloatArray(chunk)
        val sendR = FloatArray(chunk)
        val out = ShortArray(chunk * 2)
        val beat = 60.0 / spec.bpm
        val delayFrames = (beat * 0.75 * rate).toInt().coerceAtLeast(1)
        val lineL = FloatArray(delayFrames)
        val lineR = FloatArray(delayFrames)
        var linePos = 0
        val fadeIn = if (spec.mood == Mood.AMBIENT) 1.5 else 0.3
        val fadeOut = minOf(2.5, spec.seconds * 0.15)
        var next = 0
        val active = ArrayList<Note>()
        var frame = 0
        while (frame < total) {
            val count = minOf(chunk, total - frame)
            java.util.Arrays.fill(dryL, 0f); java.util.Arrays.fill(dryR, 0f)
            java.util.Arrays.fill(sendL, 0f); java.util.Arrays.fill(sendR, 0f)
            val a = frame.toDouble() / rate
            val b = (frame + count).toDouble() / rate
            while (next < notes.size && notes[next].start < b) active += notes[next++]
            active.removeAll { it.end <= a }
            for (note in active) {
                val angle = (note.pan + 1f) * (PI / 4).toFloat()
                val gl = kotlin.math.cos(angle) * note.velocity
                val gr = kotlin.math.sin(angle) * note.velocity
                val from = maxOf(0, ((note.start - a) * rate).toInt())
                val to = minOf(count, ((note.end - a) * rate).toInt() + 1)
                val freq = 440.0 * Math.pow(2.0, (note.midi - 69) / 12.0)
                for (i in from until to) {
                    val t = (frame + i).toDouble() / rate - note.start
                    if (t < 0) continue
                    val v = voice(note, t, freq, frame + i).toFloat()
                    dryL[i] += v * gl; dryR[i] += v * gr
                    if (note.send > 0f) { sendL[i] += v * gl * note.send; sendR[i] += v * gr * note.send }
                }
            }
            for (i in 0 until count) {
                val echoL = lineL[linePos]
                val echoR = lineR[linePos]
                lineL[linePos] = sendL[i] + echoR * 0.35f
                lineR[linePos] = sendR[i] + echoL * 0.35f
                linePos = (linePos + 1) % delayFrames
                val time = (frame + i).toDouble() / rate
                val fade = minOf(1.0, time / fadeIn, (spec.seconds - time) / fadeOut).coerceIn(0.0, 1.0).toFloat()
                val l = tanh((dryL[i] + echoL * 0.5f) * 0.9f) * fade
                val r = tanh((dryR[i] + echoR * 0.5f) * 0.9f) * fade
                out[i * 2] = (l * 30000f).toInt().toShort()
                out[i * 2 + 1] = (r * 30000f).toInt().toShort()
            }
            sink(out, count)
            frame += count
        }
    }

    private fun release(voice: Voice): Double = when (voice) {
        Voice.PAD -> 0.8
        Voice.PIANO -> 0.25
        Voice.PLUCK -> 0.1
        Voice.LEAD -> 0.15
        Voice.BASS -> 0.08
        else -> 0.0
    }

    private val TABLE = 4096
    private fun table(vararg harmonics: Double): FloatArray {
        val peak = harmonics.sum()
        return FloatArray(TABLE) { i ->
            var s = 0.0
            for ((n, amp) in harmonics.withIndex()) s += amp * sin(2 * PI * (n + 1) * i / TABLE)
            (s / peak).toFloat()
        }
    }
    private val PAD_WAVE = table(1.0, 0.45, 0.3, 0.2, 0.14, 0.1, 0.07, 0.05)
    private val PIANO_WAVE = table(1.0, 0.35, 0.18, 0.08, 0.04, 0.02)
    private val LEAD_WAVE = table(1.0, 0.2, 0.33, 0.1, 0.2, 0.05, 0.14, 0.03, 0.1)
    private val BASS_WAVE = table(1.0, 0.4, 0.15, 0.06)

    private fun lookup(wave: FloatArray, phase: Double): Float {
        val p = phase - kotlin.math.floor(phase)
        return wave[(p * TABLE).toInt() and (TABLE - 1)]
    }

    private fun noise(id: Int, frame: Int): Float {
        var x = id * 374761393 + frame * 668265263
        x = (x xor (x ushr 13)) * 1274126177
        x = x xor (x ushr 16)
        return (x and 0xFFFF) / 32768f - 1f
    }

    private fun voice(note: Note, t: Double, freq: Double, frame: Int): Double {
        val held = note.length
        fun releaseGain(r: Double) = if (t <= held) 1.0 else (1.0 - (t - held) / r).coerceAtLeast(0.0)
        return when (note.voice) {
            Voice.PAD -> {
                val attack = minOf(0.5, held * 0.3)
                val env = minOf(1.0, t / attack) * releaseGain(0.8)
                0.65 * env * (lookup(PAD_WAVE, freq * 1.0017 * t) + lookup(PAD_WAVE, freq * 0.9983 * t))
            }
            Voice.PIANO -> 1.4 * minOf(1.0, t / 0.004) * exp(-t / 0.7) * releaseGain(0.25) * lookup(PIANO_WAVE, freq * t)
            Voice.PLUCK -> 1.4 * minOf(1.0, t / 0.002) * exp(-t / 0.22) * releaseGain(0.1) * lookup(PIANO_WAVE, freq * t) * (1 + 0.4 * exp(-t / 0.03))
            Voice.LEAD -> {
                val vibrato = if (t > 0.2) 1 + 0.003 * sin(2 * PI * 5.0 * t) else 1.0
                1.3 * minOf(1.0, t / 0.02) * (0.8 + 0.2 * exp(-t / 0.2)) * releaseGain(0.15) * lookup(LEAD_WAVE, freq * vibrato * t)
            }
            Voice.BASS -> 0.7 * minOf(1.0, t / 0.008) * releaseGain(0.08) * lookup(BASS_WAVE, freq * t)
            Voice.KICK -> {
                val phase = 45 * t + 110 * 0.04 * (1 - exp(-t / 0.04))
                exp(-t / 0.3) * sin(2 * PI * phase) * 1.05
            }
            Voice.SNARE -> exp(-t / 0.12) * noise(note.id, frame) * 0.7 + exp(-t / 0.07) * sin(2 * PI * 185 * t) * 0.5
            Voice.HAT -> exp(-t / 0.035) * (noise(note.id, frame) - noise(note.id, frame - 1)) * 0.6
            Voice.CLAP -> {
                val burst = if (t < 0.03) (if ((t * 1000).toInt() % 10 < 4) 1.0 else 0.3) else exp(-(t - 0.03) / 0.1)
                burst * noise(note.id, frame)
            }
            Voice.TOM -> {
                val phase = 70 * t + 40 * 0.12 * (1 - exp(-t / 0.12))
                exp(-t / 0.7) * sin(2 * PI * phase) * 0.8
            }
        }
    }
}

/** 16-bit PCM WAV, written as it is produced. */
object Wav {
    fun write(file: File, rate: Int, channels: Int, frames: Int, produce: ((ShortArray, Int) -> Unit) -> Unit) {
        file.parentFile?.mkdirs()
        val bytes = frames.toLong() * channels * 2
        BufferedOutputStream(file.outputStream(), 1 shl 16).use { out ->
            fun int32(v: Long) { for (i in 0 until 4) out.write(((v shr (8 * i)) and 0xFF).toInt()) }
            fun int16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }
            out.write("RIFF".toByteArray()); int32(36 + bytes); out.write("WAVE".toByteArray())
            out.write("fmt ".toByteArray()); int32(16); int16(1); int16(channels)
            int32(rate.toLong()); int32(rate.toLong() * channels * 2); int16(channels * 2); int16(16)
            out.write("data".toByteArray()); int32(bytes)
            val buffer = ByteArray(1 shl 15)
            produce { samples, count ->
                var n = 0
                for (i in 0 until count * channels) {
                    val s = samples[i].toInt()
                    buffer[n++] = (s and 0xFF).toByte()
                    buffer[n++] = ((s shr 8) and 0xFF).toByte()
                    if (n == buffer.size) { out.write(buffer, 0, n); n = 0 }
                }
                out.write(buffer, 0, n)
            }
        }
    }

    /** Length in seconds from the header, or from the file size when the data size is not filled in. */
    fun seconds(file: File): Double? = runCatching {
        RandomAccessFile(file, "r").use { raf ->
            val head = ByteArray(12)
            raf.readFully(head)
            if (String(head, 0, 4) != "RIFF" || String(head, 8, 4) != "WAVE") return null
            var byteRate = 0L
            while (raf.filePointer + 8 <= raf.length()) {
                val idBytes = ByteArray(4)
                raf.readFully(idBytes)
                val size = Integer.reverseBytes(raf.readInt()).toLong() and 0xFFFFFFFFL
                val id = String(idBytes)
                if (id == "fmt ") {
                    val fmt = ByteArray(size.toInt().coerceAtMost(64))
                    raf.readFully(fmt)
                    byteRate = (fmt[8].toLong() and 0xFF) or ((fmt[9].toLong() and 0xFF) shl 8) or
                        ((fmt[10].toLong() and 0xFF) shl 16) or ((fmt[11].toLong() and 0xFF) shl 24)
                    raf.seek(raf.filePointer + size - fmt.size)
                } else if (id == "data") {
                    if (byteRate <= 0) return null
                    val remaining = raf.length() - raf.filePointer
                    val data = if (size == 0L || size == 0xFFFFFFFFL || size > remaining) remaining else size
                    return data.toDouble() / byteRate
                } else {
                    raf.seek(raf.filePointer + size + (size and 1))
                }
            }
            null
        }
    }.getOrNull()
}