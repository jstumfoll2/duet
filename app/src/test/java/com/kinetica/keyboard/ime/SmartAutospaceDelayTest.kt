package com.kinetica.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Smart timing for a tapped word's automatic space ([smartTapDelay]): the tap delay scaled by
 * how likely the letters are the whole word, and held to the user's own letter pace while in
 * doubt.
 */
class SmartAutospaceDelayTest {

    @Test
    fun aWordThatIsAlmostAlwaysWholeSpacesSooner() {
        // `if`, `my`, `and`: 0.4 of a 150 ms delay.
        assertEquals(60L, smartTapDelay(base = 150L, endShare = 0.99f, letterGapMs = -1L))
    }

    @Test
    fun anAmbiguousWordWaitsTheDelay() {
        // `i`, `it`, `so`.
        assertEquals(150L, smartTapDelay(base = 150L, endShare = 0.4f, letterGapMs = -1L))
    }

    @Test
    fun theStartOfALongerWordWaitsThreeTimesAsLong() {
        // `th`, `al`: in the wordlist, but nearly always on the way to more.
        assertEquals(450L, smartTapDelay(base = 150L, endShare = 0.01f, letterGapMs = -1L))
    }

    @Test
    fun spellingSlowlyHoldsTheSpaceToYourPace() {
        // `the` spelled one letter every half second, on the way to `them`.
        assertEquals(500L, smartTapDelay(base = 60L, endShare = 0.66f, letterGapMs = 500L))
    }

    @Test
    fun aSureWordIgnoresThePace() {
        assertEquals(24L, smartTapDelay(base = 60L, endShare = 0.95f, letterGapMs = 500L))
    }

    @Test
    fun theWaitStaysInBounds() {
        assertEquals(SMART_MIN_DELAY_MS, smartTapDelay(base = 10L, endShare = 1f, letterGapMs = -1L))
        assertEquals(SMART_MAX_DELAY_MS, smartTapDelay(base = 800L, endShare = 0f, letterGapMs = -1L))
    }

    @Test
    fun theLetterGapIsTheMedianOfRealPauses() {
        assertEquals(200L, medianLetterGap(listOf(0L, 150L, 350L, 650L)))
        // A 1 ms reload seed and a long break count for nothing.
        assertEquals(150L, medianLetterGap(listOf(0L, 1L, 151L, 5_000L)))
        assertEquals(-1L, medianLetterGap(listOf(10L)))
        assertEquals(-1L, medianLetterGap(listOf(10L, 11L, 12L)))
    }
}
