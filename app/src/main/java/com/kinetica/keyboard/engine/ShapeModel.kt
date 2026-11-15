package com.kinetica.keyboard.engine

import kotlin.math.pow

/**
 * How a swipe's shape is compared with a word's ideal path (decoder rework step 6, #129).
 *
 * The DTW reads 32 points spaced evenly along the path, which throws the finger's speed away.
 * Thumbs slow down at the keys they mean and fly over the ones between, so a miss where the
 * finger was slow says more than a miss where it was fast. With [speed] on, each resampled
 * point's DTW cost is weighed by the time the finger spent around it:
 *
 *     w[i] = (t[i + 1] - t[i - 1]) ^ GAMMA, scaled to mean 1, held within [1 / MAX_W, MAX_W]
 *
 * Mean 1 keeps the summed cost on the scale the score and the early-abandon budget expect.
 * Off, the default, is the unweighted DTW. Volatile, read when a pattern is built.
 */
object ShapeModel {

    @Volatile @JvmField var speed = false

    /** 0 is no weighting, 1 is weight proportional to time spent. */
    @Volatile @JvmField var GAMMA = 0.5f

    /** No point weighs more than this, or less than its inverse, before the final scaling. */
    @Volatile @JvmField var MAX_W = 3f

    /**
     * Weights for the resample positions whose times are [sampleT], or null when [speed] is off
     * or the piece has no usable timing (a synthetic path, or one drawn within a millisecond).
     */
    fun weights(sampleT: LongArray): FloatArray? {
        if (!speed) return null
        val n = sampleT.size
        if (n < 3 || sampleT[n - 1] - sampleT[0] < MIN_SPAN_MS) return null
        val w = FloatArray(n)
        var sum = 0f
        for (i in 0 until n) {
            val a = sampleT[maxOf(0, i - 1)]
            val b = sampleT[minOf(n - 1, i + 1)]
            // An end point spans one step where the others span two.
            val dt = (b - a).toFloat() * (if (i == 0 || i == n - 1) 2f else 1f)
            w[i] = (dt + 1f).pow(GAMMA)
            sum += w[i]
        }
        val mean = sum / n
        var clamped = 0f
        for (i in 0 until n) {
            w[i] = (w[i] / mean).coerceIn(1f / MAX_W, MAX_W)
            clamped += w[i]
        }
        val scale = n / clamped
        for (i in 0 until n) w[i] *= scale
        return w
    }

    private const val MIN_SPAN_MS = 30L
}
