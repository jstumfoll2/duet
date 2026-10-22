package com.kinetica.keyboard.ime

/**
 * How fast the user is typing right now, as a multiplier on the autospace delays
 * ([com.kinetica.keyboard.settings.Prefs.AUTOSPACE_ADAPTIVE]): under 1 while fast, over 1 while
 * slow, 1 with nothing recent to go on. The sliders set the wait at an ordinary pace; this
 * moves it with the moment.
 *
 * Two readings, one per kind of word:
 * - A swiped word follows the words-per-minute of the last few seconds ([TypingSpeed]):
 *   [REF_WPM] over the rate. Measured on eight capture batches (3,275 swiped words followed by
 *   another word, 26 pauses inside a swiped word), this sits on the same curve as a fixed delay:
 *   at a 60 ms base it glued 9 next words onto the previous one against 11, at 150 ms 68
 *   against 74, and split no word either way. Swiped words rarely leave a pause inside: two
 *   thumbs overlap and one thumb draws the word in one stroke.
 * - A tapped word follows the median pause between letters of the last few tapped words:
 *   that over [REF_LETTER_GAP_MS]. On 9 batches of pecked words at a 200 ms base it cut
 *   premature spaces from 6.7% to 6.0% of mid-word pauses and spaced 57.5% of words before the
 *   user's own space against 56.8%; at 300 ms 4.1% to 3.1% and 39.6% to 41.7%.
 *
 * Both factors stay within [MIN_FACTOR] and [MAX_FACTOR], so the setting still means something.
 */
class AutospacePace(
    private val speed: TypingSpeed = TypingSpeed(),
) {
    private class Gap(val atMs: Long, val gapMs: Long)

    private val gaps = ArrayDeque<Gap>()
    private var lastEndMs = -1L

    /**
     * A committed word of [letters] letters typed from [startMs] to [endMs], with the start
     * times of its tapped letters in [tapStarts] (empty for a swiped word).
     */
    fun onWord(startMs: Long, endMs: Long, letters: Int, tapStarts: List<Long>) {
        if (startMs >= 0 && letters > 0) {
            speed.logWord(startMs, endMs, letters)
            lastEndMs = maxOf(startMs, endMs)
        }
        for ((a, b) in tapStarts.zipWithNext()) {
            val g = b - a
            if (g in LETTER_GAP_MIN_MS..LETTER_GAP_MAX_MS) gaps.addLast(Gap(b, g))
        }
        while (gaps.size > GAPS_KEPT) gaps.removeFirst()
    }

    /** The multiplier on the swipe delay at [nowMs]. */
    fun swipeFactor(nowMs: Long): Float {
        if (lastEndMs < 0 || nowMs - lastEndMs > TypingSpeed.IDLE_BREAK_MS) return 1f
        val wpm = speed.rate() ?: return 1f
        if (wpm <= 0f) return 1f
        return (REF_WPM / wpm).coerceIn(MIN_FACTOR, MAX_FACTOR)
    }

    /** The multiplier on the tap delay at [nowMs]. */
    fun tapFactor(nowMs: Long): Float {
        val recent = gaps.filter { nowMs - it.atMs in 0..GAP_WINDOW_MS }.map { it.gapMs }.sorted()
        if (recent.size < GAPS_NEEDED) return 1f
        return (recent[recent.size / 2].toFloat() / REF_LETTER_GAP_MS).coerceIn(MIN_FACTOR, MAX_FACTOR)
    }

    fun clear() {
        speed.clear()
        gaps.clear()
        lastEndMs = -1L
    }

    companion object {
        /** The pace a slider's value is for: about the owner's median while swiping. */
        const val REF_WPM = 50f

        /** The median pause between pecked letters over the owner's captures. */
        const val REF_LETTER_GAP_MS = 150f

        const val MIN_FACTOR = 0.5f
        const val MAX_FACTOR = 2f

        /** Letter pauses older than this say nothing about the pace now. */
        const val GAP_WINDOW_MS = 30_000L
        const val GAPS_KEPT = 12
        const val GAPS_NEEDED = 4

        // As medianLetterGap: two-thumb near-simultaneous pairs and breaks are not a pace.
        private const val LETTER_GAP_MIN_MS = 20L
        private const val LETTER_GAP_MAX_MS = 1500L
    }
}

/**
 * [base] scaled by [factor], kept inside the slider's own range so an adapted wait is one the
 * user could have set.
 */
internal fun pacedDelay(base: Long, factor: Float, minMs: Long, maxMs: Long): Long =
    (base * factor).toLong().coerceIn(minMs, maxMs)
