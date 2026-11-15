package com.kinetica.keyboard.engine.trace

/**
 * The developer build's "did you mean" prompt after a retype (decoder rework step 2, #125).
 *
 * Replay already guesses that a free-typed word the user undid (a backspace, or a buffer
 * dropped after it) and typed again as a close word was meant as that word, and counts it as a
 * gold label (`how = "retyped"`). A guess is not a label: the user may have changed their mind.
 * This watches the same word lines and key presses replay reads, in the same order and by the
 * same rule ([retypeOf]), and when replay would relabel a line it offers the pair once, so one
 * tap turns the guess into a confirmed label (or says it was a change of mind). The answer is
 * a `confirm` line in the trace ([SwipeTrace.encodeConfirm]).
 *
 * Rate-limited: at most one offer per [minGapMs], so it shows now and then, never on every
 * fix. Practice lines carry their own label and never offer. Main thread only.
 */
class GoldPrompt(private val minGapMs: Long = MIN_GAP_MS) {

    /** A retype replay would relabel: the line committed as [from] was meant as [to]. */
    data class Offer(val from: String, val to: String)

    // The last free-typed commit, and whether it has been undone since.
    private var last: String? = null
    private var undone = false
    private var lastOfferMs = Long.MIN_VALUE / 2

    /**
     * The offer waiting for a quiet bar, or null. The bar is often busy right after the retyped
     * commit (its correction strip), so the offer waits up to [PENDING_WORDS] more word lines;
     * replay finds the retyped commit by its spelling, so a later answer still lands on it.
     */
    var pending: Offer? = null
        private set
    private var pendingAge = 0

    /** Whether offers are made at all (the trace screen's switch). */
    var enabled: () -> Boolean = { true }

    /** A word line was written: [committed] null for a buffer dropped without a commit. */
    fun onWord(committed: String?, target: String?, nowMs: Long) {
        if (pending != null && ++pendingAge > PENDING_WORDS) pending = null
        if (committed == null) {
            if (last != null) undone = true
            return
        }
        val prev = last
        if (undone && prev != null && retypeOf(prev, committed) == Retype.CLOSE &&
            target == null && enabled() && nowMs - lastOfferMs >= minGapMs
        ) {
            pending = Offer(prev, committed)
            pendingAge = 0
            lastOfferMs = nowMs
        }
        undone = false
        last = if (target == null) committed else null
    }

    /** A key line was written. A backspace after a commit undoes it, as replay reads it. */
    fun onKey(name: String) {
        if (name == "backspace" && last != null) undone = true
    }

    /** The offer, once: the keyboard shows it on the bar. */
    fun take(): Offer? = pending.also { pending = null }

    enum class Retype { NONE, CLOSE, EXTENDS }

    companion object {
        /** Five minutes between offers: a handful per session of real typing. */
        const val MIN_GAP_MS = 5 * 60_000L

        /** Word lines an unshown offer outlives its retyped commit by. */
        const val PENDING_WORDS = 3

        /**
         * How [retyped] relates to the earlier commit [was]: CLOSE when it differs by at most
         * half the longer word's letters (go/to, gels/tells, bit/but), EXTENDS when it only adds
         * letters at the end, NONE otherwise (a stray "j" before "the", or a different word).
         * Replay relabels by the same rule.
         */
        fun retypeOf(was: String?, retyped: String): Retype {
            val a = was?.lowercase() ?: return Retype.NONE
            val b = retyped.lowercase()
            if (a == b || a.isEmpty()) return Retype.NONE
            if (b.startsWith(a)) return Retype.EXTENDS
            return if (com.kinetica.keyboard.engine.RetypePenalties.closeRetype(a, b)) Retype.CLOSE else Retype.NONE
        }
    }
}
