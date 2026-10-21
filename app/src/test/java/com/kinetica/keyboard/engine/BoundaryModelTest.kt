package com.kinetica.keyboard.engine

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fitted word-boundary model (#67) in ratio terms: how many times one word's score the two
 * words must reach. Pins the shape the fit found, so a refit that flips it is noticed.
 */
class BoundaryModelTest {

    // Two words reading `ratio` times one word, the tapped word under its cap.
    private fun peck(ratio: Float, gapMs: Long) = BoundaryModel.splits(0.5f, 2f * ratio, 1f, gapMs, restSwipes = true)

    private fun rejoin(ratio: Float, restSwipes: Boolean) = BoundaryModel.splits(0.5f, 2f * ratio, 1f, null, restSwipes)

    @Test
    fun aQuickSwipeAfterTapsNeedsMoreEvidenceThanASlowOne() {
        // A word started with taps runs straight on into its swipe; a new word takes a moment.
        assertFalse(peck(3f, gapMs = 20))
        assertTrue(peck(6f, gapMs = 20))
        assertFalse(peck(1.1f, gapMs = 200))
        assertTrue(peck(1.4f, gapMs = 200))
    }

    @Test
    fun aReopenedWordSplitsMoreReadilyBeforeASwipeThanBeforeTaps() {
        assertTrue(rejoin(0.5f, restSwipes = true))
        assertFalse(rejoin(1.2f, restSwipes = false))
        assertTrue(rejoin(2f, restSwipes = false))
    }

    @Test
    fun noOneWordReadingAlwaysSplitsAndNoTwoWordReadingNever() {
        assertTrue(BoundaryModel.splits(0.1f, 0.1f, 0f, 20, restSwipes = true))
        assertFalse(BoundaryModel.splits(0f, 1f, 0.5f, 900, restSwipes = true))
    }

    @Test
    fun theTappedWordCountsAtMostItsCap() {
        // `a` and `to` score far above one once context is in; past the cap it adds nothing.
        val capped = BoundaryModel.logOdds(KineticaConstants.PECK_SPLIT_TAP_CAP, 1f, 1f, 150, true)
        val boosted = BoundaryModel.logOdds(4f, 1f, 1f, 150, true)
        assertTrue(capped == boosted)
    }
}
