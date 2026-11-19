package com.kinetica.keyboard.engine

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.sqrt

/**
 * Which candidate score the decoder uses (decoder rework step 1, issue #124).
 *
 * [Mode.LEGACY], the default, is the score the keyboard has always used:
 * `fw * geometricTerm(d) * bigram * personal * pair * keep`, with a frequency weight linear in log
 * count, a shape term flat past [KineticaConstants.GEO_SATURATION_KW] and the context and personal
 * boosts faded by the fit ([KineticaConstants.appliedBoost]).
 *
 * [Mode.LOGLINEAR] scores in log space with a real language model and a shape term that never goes
 * flat:
 *
 *     ln S = A * lm(w | u, v) + B * shape(d) + ln personal + ln pair + ln keep
 *     lm    = ln P3(w | u, v)  if the triple is in the table
 *           = ln P2(w | v)     else if the pair is
 *           = ln BACKOFF + ln P1(w)   (stupid backoff)
 *     shape = -ln(1 + (d / SIGMA)^2)
 *
 * The personal boosts apply at full strength: the shape term itself pays for a poor fit, so there
 * is nothing to fade. The two-word context is part of `lm`, so the after-search trigram pass is
 * skipped in this mode.
 *
 * Defaults fitted on replay (half A = trace files before 2026-10-22, half B = the rest), see the
 * step 1 PR. Volatile fields, read once per decode: the developer build switches the mode from the traces
 * screen, and the replay tool sets it and the weights from flags (`--score loglinear`, `--lmA`,
 * `--lmB`, `--lmSigma`, `--lmBackoff`).
 */
object ScoreModel {

    enum class Mode { LEGACY, LOGLINEAR }

    /** The score in use: this decode's ([DecoderConfig.current]); a write sets [DecoderConfig.global]. */
    var mode: Mode
        get() = DecoderConfig.current().mode
        set(value) = DecoderConfig.update { it.copy(mode = value) }

    /** Weight of the language model's log-probability. */
    @Volatile @JvmField var A = 0.3f

    /** Weight of the shape term. */
    @Volatile @JvmField var B = 3f

    /** Scale of the shape term, kw of mean distance. */
    @Volatile @JvmField var SIGMA = 0.25f

    /** Stupid-backoff factor for a word the context tables do not hold after its context. */
    @Volatile @JvmField var BACKOFF = 0.4f

    /** Scale from the interleaved reading's cost onto the DTW mean the shape term reads. */
    @Volatile @JvmField var IL_SCALE = 1f

    /**
     * Weight of the shape term on an interleaved reading, NaN for [B]. The interleaved cost is
     * the thumbs' distance at the letters' moments, which runs smaller than a pass's DTW mean
     * for the same fit, so it is weighed more: 4 fitted best on both replay halves (1 to 5 tried).
     */
    @Volatile @JvmField var IL_B = 4f

    /** What an interleaved reading's score is worth against the passes' (legacy: INTERLEAVE_WEIGHT). */
    @Volatile @JvmField var IL_WEIGHT = 1f

    val logLinear: Boolean get() = DecoderConfig.current().logLinear

    /**
     * Continuous tap likelihood (decoder rework step 4, #127). Off: a tap reads as the key under
     * the finger at no charge, and its neighbours only in the fuzzy passes (sparse heap or a tap
     * near an edge). On: every pass tries each key whose centre lies within [TAP_RADIUS] kw, at
     * the Gaussian charge `TAP_W * (d^2 - d0^2) / (2 * TAP_SIGMA^2)` (kw of shape distance per
     * nat), d0 the nearest centre's distance, so the key under the finger is free and a
     * neighbour costs what the touch spread says it should. Independent of [mode].
     */
    var taps: Boolean
        get() = DecoderConfig.current().taps
        set(value) = DecoderConfig.update { it.copy(taps = value) }

    /**
     * Touch spread, kw. The developer's taps scatter 0.25 to 0.35 kw around where they aim; 0.4
     * fitted best on replay (wider covers the thumbs' own offsets the model does not subtract).
     */
    @Volatile @JvmField var TAP_SIGMA = 0.4f

    /**
     * Shape-distance kw charged per nat of tap log-likelihood. 0.1 at first; at that weight a
     * short all-tap word whose exact keys were tapped lost to a commoner word one key over
     * (two letters are little evidence against the language model). Fitted again with the
     * two-thumb encoder on, on the trace files before 2026-10-22: 0.15 was best there for
     * all-tap words (82.2 -> 82.7%) and all words (83.0 -> 83.2%), true-label words 75.6 -> 75.5.
     * Held out on the later files: all-tap 82.9 -> 83.4% (legacy score 83.8), all words
     * 82.4 -> 82.5 (legacy 82.5), true-label 70.1 both. 0.2 and 0.3 gain a little more on taps
     * held out and lose true-label words.
     */
    @Volatile @JvmField var TAP_W = 0.15f

    /** Keys farther than this from the touch are never tried. */
    @Volatile @JvmField var TAP_RADIUS = 1.2f

    /** A key whose charge exceeds this many nats is not tried (prunes the search). */
    @Volatile @JvmField var TAP_MAX_NATS = 6f

    /**
     * Growth of [SIGMA] per letter past [SIGMA_LEN_FROM] (decoder rework step 5, #128). A long
     * word's path drifts further from its ideal shape than a short one's: thumbs cut corners
     * over a long trip, and the mean DTW distance rises with it. Legacy answers with a
     * saturation that widens on long buffers; here the shape term's scale widens instead.
     * 0 keeps one scale for every length; 0.05 fitted on replay (10+ letter words 64 -> 69%).
     */
    @Volatile @JvmField var SIGMA_LEN = 0.05f

    /** Word length from which [SIGMA_LEN] applies. */
    @Volatile @JvmField var SIGMA_LEN_FROM = 4

    /**
     * Run the cut, stream and fuzzy passes on a close call too, not only on a short list
     * (decoder rework step 5, #128): when the lead beats the runner-up by under [GATE_MARGIN]
     * nats. Off: today's "fewer than ten words" gate alone.
     */
    @Volatile @JvmField var marginGate = false

    /** The lead's margin, nats, under which the extra passes run with [marginGate]. */
    @Volatile @JvmField var GATE_MARGIN = 0.5f

    /** The shape scale for a word of [letters] letters. */
    fun sigmaFor(letters: Int): Float = SIGMA * (1f + SIGMA_LEN * maxOf(0, letters - SIGMA_LEN_FROM))

    /** `B * shape(d)`, the shape term as a natural-log factor of the score. */
    fun lnShape(d: Float, letters: Int = 0): Float {
        val r = d / sigmaFor(letters)
        return -B * ln(1f + r * r)
    }

    /** The shape factor of an interleaved reading of [letters] letters at cost [cost]. */
    fun interleavedShape(cost: Float, letters: Int): Float {
        val d = cost * IL_SCALE
        val r = d / sigmaFor(letters)
        val b = if (IL_B.isNaN()) B else IL_B
        return exp(-b * ln(1f + r * r))
    }

    /** The shape term as a factor of the score, 1.0 at a perfect fit, never flat. */
    fun shapeFactor(d: Float, letters: Int = 0): Float = exp(lnShape(d, letters))

    /** `exp(A * lm)`, the language model as a factor of the score. */
    fun priorFactor(lm: Float): Float = exp(A * lm)

    /**
     * Exact inverse of [shapeFactor] for the early-abandon budget: the largest d at which
     * [numerator] * shapeFactor(d) still beats [minScore]. Infinite when there is no heap minimum
     * yet, 0 when nothing can beat it.
     */
    fun maxD(numerator: Float, minScore: Float, letters: Int = 0): Float {
        if (minScore <= 0f) return Float.POSITIVE_INFINITY
        val ratio = numerator / minScore
        if (ratio <= 1f) return 0f
        // numerator * (1 + r^2)^-B > minScore  <=>  r^2 < ratio^(1/B) - 1
        val r2 = exp(ln(ratio) / B) - 1f
        return sigmaFor(letters) * sqrt(r2)
    }
}
