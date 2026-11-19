package com.kinetica.keyboard.engine

import kotlin.math.ln

/**
 * Whether the word after it says the previous word was read wrong (decoder rework step 7, #130).
 *
 * A word is committed on the evidence up to it. The next word is evidence too: after `i went
 * tot he`, nothing follows `tot` well, and `to the` was the second reading of both. The decoder
 * keeps each word's ranked readings; when the next word is decoded, the previous word's close
 * runners-up are tried as its context, and the pair that scores best together wins:
 *
 *     joint(p) = ln S_prev(p) + ln max over c of S_cur(c | p)
 *
 * The previous word is flipped to runner-up `p` when `joint(p)` beats the committed word's by
 * more than [MARGIN] nats. Scores multiply as probabilities only under the log-linear score
 * ([ScoreModel.Mode.LOGLINEAR]), which is what this is fitted for.
 *
 * Pure: the caller supplies the decodes. [candidates] says which runners-up are worth a decode.
 */
object PreviousWordFix {

    /**
     * Whether the keyboard revises at all. Off by default; the developer build turns it on from
     * the traces screen. It only acts under the log-linear score, whose scores it was fitted on.
     */
    var enabled: Boolean
        get() = DecoderConfig.current().previousWordFix
        set(value) = DecoderConfig.update { it.copy(previousWordFix = value) }

    /** A runner-up further than this behind the committed word, in nats, is not tried. */
    @Volatile @JvmField var GAP = 2f

    /** How far a runner-up's pair must lead the committed word's, in nats, to flip. */
    @Volatile @JvmField var MARGIN = 0.75f

    /** At most this many runners-up are tried: each costs a decode of the next word. */
    @Volatile @JvmField var MAX_ALTERNATES = 2

    /** A ranked reading: the word as shown and its score. */
    data class Reading(val word: String, val score: Float)

    /**
     * The runners-up of [previous] (best first, as decoded) worth trying in place of
     * [committed]: within [GAP] nats of it and not the same word. Empty when [committed] is
     * not on the list (the user typed or picked something else, which is not ours to revise).
     */
    fun candidates(previous: List<Reading>, committed: String): List<Reading> {
        val own = previous.firstOrNull { it.word.equals(committed, ignoreCase = true) } ?: return emptyList()
        if (own.score <= 0f) return emptyList()
        val out = ArrayList<Reading>(MAX_ALTERNATES)
        for (r in previous) {
            if (out.size >= MAX_ALTERNATES) break
            if (r.word.equals(committed, ignoreCase = true) || r.score <= 0f) continue
            if (ln(own.score / r.score) > GAP) continue
            out.add(r)
        }
        return out
    }

    /**
     * The runner-up to flip the previous word to, or null to leave it. [previous] is the
     * previous word's list, [committed] what was committed from it, [nextGivenCommitted] the
     * next word's list as decoded after it, and [nextGiven] the next word's list decoded after
     * each runner-up from [candidates].
     */
    fun decide(
        previous: List<Reading>,
        committed: String,
        nextGivenCommitted: List<Reading>,
        nextGiven: Map<String, List<Reading>>,
    ): String? {
        val own = previous.firstOrNull { it.word.equals(committed, ignoreCase = true) } ?: return null
        val ownNext = nextGivenCommitted.maxOfOrNull { it.score } ?: return null
        if (own.score <= 0f || ownNext <= 0f) return null
        val base = ln(own.score) + ln(ownNext)
        var best: String? = null
        var bestJoint = base + MARGIN
        for (r in candidates(previous, committed)) {
            val next = nextGiven[r.word]?.maxOfOrNull { it.score } ?: continue
            if (next <= 0f) continue
            val joint = ln(r.score) + ln(next)
            if (joint > bestJoint) {
                bestJoint = joint
                best = r.word
            }
        }
        return best
    }
}
