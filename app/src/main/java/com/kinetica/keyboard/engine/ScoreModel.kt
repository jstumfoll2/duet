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

    @Volatile
    @JvmField
    var mode: Mode = Mode.LEGACY

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

    /** What an interleaved reading's score is worth against the passes' (legacy: INTERLEAVE_WEIGHT). */
    @Volatile @JvmField var IL_WEIGHT = 1f

    val logLinear: Boolean get() = mode == Mode.LOGLINEAR

    /** `B * shape(d)`, the shape term as a natural-log factor of the score. */
    fun lnShape(d: Float): Float {
        val r = d / SIGMA
        return -B * ln(1f + r * r)
    }

    /** The shape term as a factor of the score, 1.0 at a perfect fit, never flat. */
    fun shapeFactor(d: Float): Float = exp(lnShape(d))

    /** `exp(A * lm)`, the language model as a factor of the score. */
    fun priorFactor(lm: Float): Float = exp(A * lm)

    /**
     * Exact inverse of [shapeFactor] for the early-abandon budget: the largest d at which
     * [numerator] * shapeFactor(d) still beats [minScore]. Infinite when there is no heap minimum
     * yet, 0 when nothing can beat it.
     */
    fun maxD(numerator: Float, minScore: Float): Float {
        if (minScore <= 0f) return Float.POSITIVE_INFINITY
        val ratio = numerator / minScore
        if (ratio <= 1f) return 0f
        // numerator * (1 + r^2)^-B > minScore  <=>  r^2 < ratio^(1/B) - 1
        val r2 = exp(ln(ratio) / B) - 1f
        return SIGMA * sqrt(r2)
    }
}
