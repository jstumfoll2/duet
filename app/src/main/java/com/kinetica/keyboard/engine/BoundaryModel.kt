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
 *
 * The log-linear score ([ScoreModel.Mode.LOGLINEAR]) has its own weights (decoder rework step 9,
 * #132), and decides with them whether or not [enabled] is set: its scores are on another
 * scale, so the legacy ratios make 1,296 wrong decisions on the 18,894 rows of all trace files
 * (2026-11-15) against 688 under the legacy score, and the legacy weights 600. Refitted on
 * that score's own rows: 339, and held out by halves (A = files before 2026-10-22) 203 and 138
 * against the ratios' 501 and 795. The legacy weights were checked on the same rows and stand
 * (337 wrong against a refit's 334).
 *
 * Tried and left out: the language model's own say on the break,
 * `ln P(w1 | prev) + ln P(w2 | w1) - ln P(w | prev)` ([WordPredictor.splitLm]). Its fitted
 * weight is 0.06 on the legacy score and -0.02 on the log-linear one, and held-out errors do
 * not move (206 -> 202 and 132 -> 135 legacy; 203 -> 202 and 138 -> 138 log-linear): the
 * second word is already decoded after the first, so its score carries the pair. The replay
 * rows keep the column (`tools/experiment/boundary_fit.py`) for a later refit.
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

    // The same terms for the log-linear score.
    const val LL_BIAS = 0.693f
    const val LL_EVIDENCE = 1.154f
    const val LL_PECK = -20.311f
    const val LL_PECK_GAP = 2.044f
    const val LL_REST_SWIPE = 10.057f

    /** Whether this model decides now: switched on, or the score is the log-linear one. */
    val decides: Boolean get() = enabled || ScoreModel.logLinear

    /**
     * Log-odds that [tapScore]'s word and [restScore]'s word, typed with no space, are two
     * words rather than the one word [oneScore] reads. [gapMs] is the time from the last tap's
     * lift to the swipe for a tapped word then a swipe, null for a reopened word.
     */
    fun logOdds(tapScore: Float, restScore: Float, oneScore: Float, gapMs: Long?, restSwipes: Boolean): Float {
        val tap = minOf(tapScore, KineticaConstants.PECK_SPLIT_TAP_CAP).toDouble()
        val evidence = ln(tap) + ln(restScore.toDouble()) - ln(oneScore.toDouble())
        val ll = ScoreModel.logLinear
        var z = (if (ll) LL_BIAS else BIAS) + (if (ll) LL_EVIDENCE else EVIDENCE) * evidence
        if (gapMs != null) z += (if (ll) LL_PECK else PECK) + (if (ll) LL_PECK_GAP else PECK_GAP) * ln1p(maxOf(gapMs, 0L).toDouble())
        if (restSwipes) z += if (ll) LL_REST_SWIPE else REST_SWIPE
        return z.toFloat()
    }

    /** Whether the two words win. A buffer with no one-word reading always splits. */
    fun splits(tapScore: Float, restScore: Float, oneScore: Float, gapMs: Long?, restSwipes: Boolean): Boolean {
        if (tapScore <= 0f || restScore <= 0f) return false
        if (oneScore <= 0f) return true
        return logOdds(tapScore, restScore, oneScore, gapMs, restSwipes) > 0f
    }
}
