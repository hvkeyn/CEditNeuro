package com.hvkeyn.ceditneuro.video

/**
 * How small a picture or a video should become. The phone's own encoder does the work.
 * CompressO is not installed, and its programs are not bundled.
 */
object ShrinkPlan {
    fun quality(raw: Int): Int = raw.coerceIn(1, 100)

    /** Longest side. A named maximum wins. Otherwise a lower quality uses a smaller frame. */
    fun longSide(width: Int, height: Int, quality: Int, maxSide: Int?): Int {
        val longest = maxOf(width, height).coerceAtLeast(1)
        val cap = when {
            maxSide != null && maxSide > 0 -> maxSide
            quality >= 85 -> longest
            quality >= 60 -> minOf(longest, 1920)
            quality >= 35 -> minOf(longest, 1280)
            else -> minOf(longest, 854)
        }
        return cap.coerceAtLeast(2)
    }

    /** Even width and height, so the phone encoder can take the frame. */
    fun frame(width: Int, height: Int, quality: Int, maxSide: Int?): Pair<Int, Int> {
        val longest = maxOf(width, height).coerceAtLeast(1)
        val side = longSide(width, height, quality, maxSide)
        val scale = side.toDouble() / longest
        val w = ((width * scale).toInt().coerceAtLeast(2) / 2) * 2
        val h = ((height * scale).toInt().coerceAtLeast(2) / 2) * 2
        return w to h
    }

    /** Power-of-two decode step so a large picture is not opened at full size. */
    fun sampleSize(width: Int, height: Int, maxSide: Int): Int {
        var sample = 1
        val longest = maxOf(width, height).coerceAtLeast(1)
        val cap = maxSide.coerceAtLeast(2)
        while (longest / (sample * 2) >= cap) sample *= 2
        return sample
    }

    fun videoFps(quality: Int): Int = when {
        quality >= 80 -> 24
        quality >= 45 -> 15
        else -> 12
    }

    fun videoBitrate(width: Int, height: Int, quality: Int, fps: Int): Int {
        val q = quality(quality) / 100.0
        val rate = (width.toLong() * height * fps.coerceIn(8, 30) * (0.04 + 0.16 * q)).toInt()
        return rate.coerceIn(120_000, 8_000_000)
    }

    fun audioBitrate(quality: Int): Int = when {
        quality >= 80 -> 128_000
        quality >= 45 -> 96_000
        else -> 64_000
    }

    /** Fewer distinct colors, so a PNG can pack smaller. Quality 90 and above is left alone. */
    fun quantize(pixels: IntArray, quality: Int) {
        val drop = when {
            quality >= 90 -> return
            quality >= 70 -> 1
            quality >= 45 -> 2
            quality >= 25 -> 3
            else -> 4
        }
        val mask = (0xFF shl drop) and 0xFF
        for (i in pixels.indices) {
            val color = pixels[i]
            val a = color ushr 24
            val r = (color shr 16 and 0xFF) and mask
            val g = (color shr 8 and 0xFF) and mask
            val b = (color and 0xFF) and mask
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}
