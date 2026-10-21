package com.kinetica.keyboard.engine.models

import kotlin.math.exp
import kotlin.math.ln

/**
 * A candidate's score taken apart: the natural log of each factor that went into
 * [WordCandidate.score], by where it came from.
 *
 * A ledger, not the arithmetic. The score itself is still the float product the decoder has
 * always computed, so a list ranks bit for bit as before; the terms ride beside it so a
 * capture or a replay can say which factor lost a word ("lost on freq", "lost on geo") and so
 * the factors can be fitted together later (issues #66, #68). [total] matches `ln(score)` to
 * float rounding, which ScoreTermsTest checks on real decodes.
 *
 * Immutable: every change returns a new ledger.
 */
class ScoreTerms private constructor(private val v: FloatArray) {

    enum class Term(val key: String) {
        /** Unigram frequency weight, `FREQ_WEIGHT_FLOOR + (1 - floor) * byte / 255`. */
        FREQ("freq"),

        /**
         * Shape fit: `geometricTerm(dTotal)`, where dTotal is the DTW fit plus the tap, edit
         * and completion charges in kw. For an interleaved reading, `InterleavedSearch.geometric`.
         */
        GEO("geo"),

        /** Bigram boost as applied (faded with the fit). */
        BIGRAM("bigram"),

        /** Trigram boost as applied. */
        TRIGRAM("trigram"),

        /** Personal word boost as applied. */
        PERSONAL("personal"),

        /** Personal pair boost as applied. */
        PERSONAL_PAIR("pair"),

        /**
         * Charges collected along the search path: uncontacted letters, relaxed start and end
         * gates (rescue, beam), and the short-reading keep. One term because the search
         * multiplies them into one running value; an interleaved reading's per-stroke short
         * keep lands here too.
         */
        PATH("path"),

        /** INTERLEAVE_WEIGHT on an interleaved reading. */
        INTERLEAVE("interleave"),

        /** Held-double boost, or the miss keep. */
        HELD_DOUBLE("held"),

        /** APOSTROPHE_MISS_KEEP on a reading without the apostrophe the swipe asked for. */
        APOSTROPHE("apostrophe"),

        /** ContactRepair.REGROUP_KEEP on a reading of the regrouped buffer. */
        REGROUP("regroup"),

        /** The user's own downrank (WordPenalties). */
        PENALTY("penalty"),

        /** The neural rerank, `-beta * cost`. */
        CTC("ctc"),
    }

    /** ln of [t]'s factor; 0 when it never applied. */
    operator fun get(t: Term): Float = v[t.ordinal]

    /** [t]'s factor itself, 1 when it never applied. */
    fun factor(t: Term): Float = exp(v[t.ordinal])

    /** Sum of every term: ln(score) to float rounding. */
    val total: Float get() = v.sum()

    /** This ledger with [factor] multiplied into [t]. */
    fun times(t: Term, factor: Float): ScoreTerms = plusLog(t, ln(factor))

    /** This ledger with [lnFactor] added to [t]. */
    fun plusLog(t: Term, lnFactor: Float): ScoreTerms {
        if (lnFactor == 0f) return this
        val out = v.copyOf()
        out[t.ordinal] += lnFactor
        return ScoreTerms(out)
    }

    /** This ledger with [t] set to [factor], whatever it held. */
    fun with(t: Term, factor: Float): ScoreTerms {
        val out = v.copyOf()
        out[t.ordinal] = ln(factor)
        return ScoreTerms(out)
    }

    /** The non-zero terms as `key=ln` pairs, for decode logs and replay dumps. */
    override fun toString(): String =
        Term.entries.filter { v[it.ordinal] != 0f }
            .joinToString(" ") { "${it.key}=${"%.3f".format(v[it.ordinal])}" }

    override fun equals(other: Any?): Boolean = other is ScoreTerms && v.contentEquals(other.v)

    override fun hashCode(): Int = v.contentHashCode()

    companion object {
        /** No terms: every factor 1. */
        val NONE = ScoreTerms(FloatArray(Term.entries.size))

        /** A reading's ledger from the factors its score multiplies. */
        fun of(
            freq: Float,
            geo: Float,
            bigram: Float = 1f,
            personal: Float = 1f,
            pair: Float = 1f,
            path: Float = 1f,
        ): ScoreTerms {
            val v = FloatArray(Term.entries.size)
            v[Term.FREQ.ordinal] = ln(freq)
            v[Term.GEO.ordinal] = ln(geo)
            v[Term.BIGRAM.ordinal] = ln(bigram)
            v[Term.PERSONAL.ordinal] = ln(personal)
            v[Term.PERSONAL_PAIR.ordinal] = ln(pair)
            v[Term.PATH.ordinal] = ln(path)
            return ScoreTerms(v)
        }
    }
}
