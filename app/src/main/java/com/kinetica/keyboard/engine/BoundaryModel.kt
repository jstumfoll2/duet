package com.kinetica.keyboard.engine

import kotlin.math.ln
import kotlin.math.ln1p

/**
 * Whether a buffer read as two words beats it read as one: one fitted model for both places
 * the decoder asks (issue #67), in place of their separate ratios.
 *
 * - A tapped word then a swipe with no space ([PeckSplit.read]): today
 *   [KineticaConstants.PECK_SPLIT_RATIO], [KineticaConstants.PECK_SPLIT_QUICK_RATIO] and
 *   [KineticaConstants.PECK_SPLIT_QUICK_GAP_MS].
 * - A word whose automatic space was taken back, then typed on ([PeckSplit.readRejoin]): today
 *   [KineticaConstants.REJOIN_SPLIT_RATIO].
 *
 * The model is a logistic regression on the log scores the decoder already has:
 *
 *     z = BIAS + EVIDENCE * ln(min(tap, PECK_SPLIT_TAP_CAP) * rest / one)
 *         + [tapped word then swipe] (PECK + PECK_GAP * ln(1 + gap ms))
 *         + [rest has a swipe] REST_SWIPE
 *
 * and splits when z > 0. In ratio terms a tapped word then a swipe needs two words to read
 * 4.9 times one at a 20 ms gap, 1.9 at 100 ms and 1.2 at 200 ms (a smooth version of today's
 * two-step 3 / 1.25); a reopened word needs 1.5 with tapped letters after it and 0.15 with a
 * swipe, since a swipe that follows a pause long enough to space the word starts a new one.
 *
 * Fitted on the `splitdata` and `rejoindata` rows of all thirteen trace batches (8,822 rows,
 * 2026-10-21). Held out by halves (A = 10-02..10-13, B = 10-18..10-21, each fitted on the
 * other): wrong decisions 149 -> 110 on A and 129 -> 96 on B. Gap: the rejoin rows have no
 * real word finished with a swipe after its space was taken back, so [REST_SWIPE] is fitted on
 * splits alone; the phone's traces should show whether that case exists.
 *
 * Off by default ([enabled]); the developer build switches it from the trace screen.
 */
object BoundaryModel {

    /** Whether [PeckSplit] decides with this model instead of its ratios. */
    @Volatile
    var enabled = false

    const val BIAS = -1.375f
    const val EVIDENCE = 3.344f
    const val PECK = -17.789f
    const val PECK_GAP = 2.036f
    const val REST_SWIPE = 7.673f

    /**
     * Log-odds that [tapScore]'s word and [restScore]'s word, typed with no space, are two
     * words rather than the one word [oneScore] reads. [gapMs] is the time from the last tap's
     * lift to the swipe for a tapped word then a swipe, null for a reopened word.
     */
    fun logOdds(tapScore: Float, restScore: Float, oneScore: Float, gapMs: Long?, restSwipes: Boolean): Float {
        val tap = minOf(tapScore, KineticaConstants.PECK_SPLIT_TAP_CAP).toDouble()
        val evidence = ln(tap) + ln(restScore.toDouble()) - ln(oneScore.toDouble())
        var z = BIAS + EVIDENCE * evidence
        if (gapMs != null) z += PECK + PECK_GAP * ln1p(maxOf(gapMs, 0L).toDouble())
        if (restSwipes) z += REST_SWIPE
        return z.toFloat()
    }

    /** Whether the two words win. A buffer with no one-word reading always splits. */
    fun splits(tapScore: Float, restScore: Float, oneScore: Float, gapMs: Long?, restSwipes: Boolean): Boolean {
        if (tapScore <= 0f || restScore <= 0f) return false
        if (oneScore <= 0f) return true
        return logOdds(tapScore, restScore, oneScore, gapMs, restSwipes) > 0f
    }
}
