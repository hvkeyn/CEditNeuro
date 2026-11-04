package com.hvkeyn.ceditneuro.video

/** A cheap per-frame signature: average red, green, and blue, packed into 24 bits. */
data class ReaSample(val atMs: Long, val signature: Int, val hash: Int = 0)

data class ReaShot(
    val index: Int,
    val startMs: Long,
    val endMs: Long,
    val score: Int,
) {
    val durationMs: Long get() = (endMs - startMs).coerceAtLeast(0L)
}

/**
 * Offline shot splitter for the local REA-Video pass. It only uses sampled frame
 * signatures; it makes no claim about live action, AI generation, or 3D.
 */
object ReaVideoPlanner {
    const val MIN_SHOT_MS = 320L

    fun plan(
        durationMs: Long,
        samples: List<ReaSample>,
        threshold: Int,
        maxShots: Int = 24,
    ): List<ReaShot> {
        if (durationMs <= 0L || samples.isEmpty()) return emptyList()
        val ordered = samples.sortedBy { it.atMs }
        val starts = mutableListOf(0L)
        var previous = ordered.first()
        for (sample in ordered.drop(1)) {
            val diff = distance(previous, sample)
            if (diff >= threshold && sample.atMs - starts.last() >= MIN_SHOT_MS) {
                starts += sample.atMs
                if (starts.size >= maxShots) break
            }
            previous = sample
        }
        return starts.mapIndexed { index, start ->
            ReaShot(
                index = index + 1,
                startMs = start,
                endMs = starts.getOrNull(index + 1) ?: durationMs,
                score = 0,
            )
        }
    }

    fun distance(a: ReaSample, b: ReaSample): Int {
        if (a.hash != 0 || b.hash != 0) {
            return (Integer.bitCount(a.hash xor b.hash) * 2).coerceIn(0, 64)
        }
        return distance(a.signature, b.signature)
    }

    fun distance(a: Int, b: Int): Int {
        val ar = (a shr 16) and 0xff; val ag = (a shr 8) and 0xff; val ab = a and 0xff
        val br = (b shr 16) and 0xff; val bg = (b shr 8) and 0xff; val bb = b and 0xff
        return (kotlin.math.abs(ar - br) + kotlin.math.abs(ag - bg) + kotlin.math.abs(ab - bb)) / 3
    }
}
