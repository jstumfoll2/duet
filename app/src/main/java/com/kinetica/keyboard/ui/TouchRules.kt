package com.kinetica.keyboard.ui

import com.kinetica.keyboard.engine.KineticaConstants

/**
 * Touches that are not the word, decided apart from the view so each rule is testable.
 *
 * A thumb resting on the keys, a third finger, or a numbers-row key brushed by the other thumb
 * mid-swipe would each become a letter or a digit and end the word in progress.
 */
object TouchRules {

    /**
     * A numbers-row digit tapped while the other thumb draws a swipe is dropped: it would
     * commit the word so far and leave the rest of the swipe a word of its own.
     */
    fun dropsDigitTap(fromNumberRow: Boolean, otherSwiping: Boolean, plainTap: Boolean): Boolean =
        fromNumberRow && otherSwiping && plainTap

    /**
     * A spacebar touch landing mid-word cannot slide the cursor: another thumb is drawing, or a
     * letter lifted under [MID_WORD_SPACE_MS] ago. A cursor step there abandons the pending word,
     * so its letters were committed as drawn and the rest of the word started over at the new
     * cursor, where autocorrect only ever saw the pieces. Owner traces, 2026-10-19: the gap
     * between strokes of one word is under 250 ms for 95% of words.
     *
     * A space typed since that letter ([spacedSinceLetter]) ended the word, so the next spacebar
     * touch is not mid-word. Locked anyway, it handed a thumb resting on the bar after `they `
     * over as a stroke and typed `cd` (batch 10).
     */
    fun locksSpaceSlide(otherSwiping: Boolean, sinceLetterLiftMs: Long, spacedSinceLetter: Boolean = false): Boolean =
        otherSwiping || (!spacedSinceLetter && sinceLetterLiftMs in 0 until MID_WORD_SPACE_MS)

    /**
     * A spacebar touch that travels past the drift allowance onto a letter is a stroke that
     * landed low, and goes to the engine like a digit handed over: mid-word ([locked]) whichever
     * way it went, and at a word's start when it [rose] (more up than sideways) without having
     * become a cursor slide. A cursor slide runs along the bar, so a rise onto the letters is a
     * word being started from the spacebar; before this it typed a space and dropped every
     * letter the thumb crossed (owner report, 2026-10-19: touching the spacebar first ruins the
     * word).
     */
    fun spaceHandsOverToSwipe(
        locked: Boolean,
        travelPx: Float,
        driftLimitPx: Float,
        onLetter: Boolean,
        rose: Boolean = false,
        sliding: Boolean = false,
    ): Boolean = onLetter && travelPx >= driftLimitPx && (locked || (rose && !sliding))

    const val MID_WORD_SPACE_MS = 300L

    /**
     * A touch that began on a numbers-row digit becomes a swipe once it travels past the hold
     * slop onto a letter: a word started a little high is still a word, not a digit.
     */
    fun handsOverToSwipe(fromNumberRow: Boolean, travelSq: Float, slopSq: Float, onLetter: Boolean): Boolean =
        fromNumberRow && onLetter && travelSq > slopSq

    /**
     * A stream held still for [KineticaConstants.RESTING_FINGER_MIN_MS] while another stream
     * was down is a finger at rest. Its tap would land at the front of the word, by start time.
     */
    fun isRestingFinger(stationary: Boolean, heldMs: Long, overlapped: Boolean): Boolean =
        stationary && overlapped && heldMs >= KineticaConstants.RESTING_FINGER_MIN_MS

    /**
     * A third finger on a letter, with both thumbs already drawing, is ignored: typed, it would end
     * the word with its raw letter.
     */
    fun ignoresExtraFinger(onLetter: Boolean, engineFull: Boolean): Boolean = onLetter && engineFull

    /** No long-press menu opens while another thumb is on the letters: two thumbs down is typing. */
    fun holdOpensMenu(otherOnLetters: Boolean): Boolean = !otherOnLetters
}
