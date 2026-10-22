package com.kinetica.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Test

/** The autospace delays move with the pace of the moment, within half and twice the slider. */
class AutospacePaceTest {

    /** Words of [letters] letters, each drawn in [drawMs] with [gapMs] before the next. */
    private fun swipe(pace: AutospacePace, words: Int, letters: Int, drawMs: Long, gapMs: Long): Long {
        var t = 0L
        repeat(words) {
            pace.onWord(t, t + drawMs, letters, emptyList())
            t += drawMs + gapMs
        }
        return t - gapMs
    }

    @Test
    fun nothingRecentLeavesTheSliderAsSet() {
        val pace = AutospacePace()
        assertEquals(1f, pace.swipeFactor(1_000L), 0f)
        assertEquals(1f, pace.tapFactor(1_000L), 0f)
    }

    @Test
    fun fastSwipingShortensAndSlowSwipingLengthens() {
        // 4 letters + space in 300 ms + 300 ms between: 5 chars per 0.6 s, about 100 wpm.
        val fast = AutospacePace()
        val fastEnd = swipe(fast, words = 6, letters = 4, drawMs = 300, gapMs = 300)
        assertEquals(AutospacePace.MIN_FACTOR, fast.swipeFactor(fastEnd + 50), 0.01f)

        // Three-letter words drawn in 1.5 s with 1.5 s between: about 18 wpm, held at twice
        // the slider.
        val slow = AutospacePace()
        val slowEnd = swipe(slow, words = 6, letters = 3, drawMs = 1_500, gapMs = 1_500)
        assertEquals(AutospacePace.MAX_FACTOR, slow.swipeFactor(slowEnd + 50), 0.01f)

        // 50 wpm is the slider's own pace.
        val mid = AutospacePace()
        val midEnd = swipe(mid, words = 8, letters = 4, drawMs = 600, gapMs = 600)
        assertEquals(1f, mid.swipeFactor(midEnd + 50), 0.1f)
    }

    @Test
    fun aPauseForgetsTheSwipePace() {
        val pace = AutospacePace()
        val end = swipe(pace, words = 6, letters = 4, drawMs = 300, gapMs = 300)
        assertEquals(1f, pace.swipeFactor(end + TypingSpeedPause), 0f)
    }

    @Test
    fun tapFactorFollowsTheMedianLetterPause() {
        val pace = AutospacePace()
        // Two pecked words with 300 ms between letters: twice the reference pause.
        pace.onWord(0, 900, 4, listOf(0L, 300L, 600L, 900L))
        pace.onWord(2_000, 2_600, 3, listOf(2_000L, 2_300L, 2_600L))
        assertEquals(2f, pace.tapFactor(3_000L), 0.01f)

        val quick = AutospacePace()
        quick.onWord(0, 300, 4, listOf(0L, 100L, 200L, 300L))
        quick.onWord(1_000, 1_200, 3, listOf(1_000L, 1_100L, 1_200L))
        assertEquals(100f / AutospacePace.REF_LETTER_GAP_MS, quick.tapFactor(1_500L), 0.01f)
    }

    @Test
    fun tapFactorNeedsEnoughRecentPauses() {
        val pace = AutospacePace()
        pace.onWord(0, 600, 3, listOf(0L, 300L, 600L))
        // Two pauses are not a pace.
        assertEquals(1f, pace.tapFactor(1_000L), 0f)
        pace.onWord(1_000, 1_600, 3, listOf(1_000L, 1_300L, 1_600L))
        assertEquals(2f, pace.tapFactor(2_000L), 0.01f)
        // Half a minute later they say nothing.
        assertEquals(1f, pace.tapFactor(1_600L + AutospacePace.GAP_WINDOW_MS + 1), 0f)
    }

    @Test
    fun twoThumbPairsAndBreaksAreNotAPace() {
        val pace = AutospacePace()
        // 5 ms apart (two thumbs) and 3 s apart (a break) are both dropped.
        pace.onWord(0, 6_000, 5, listOf(0L, 5L, 3_005L, 3_010L, 6_010L))
        assertEquals(1f, pace.tapFactor(6_100L), 0f)
    }

    @Test
    fun pacedDelayStaysInTheSlidersRange() {
        assertEquals(30L, pacedDelay(60L, 0.5f, 10L, 800L))
        assertEquals(800L, pacedDelay(600L, 2f, 10L, 800L))
        assertEquals(10L, pacedDelay(15L, 0.5f, 10L, 800L))
    }

    @Test
    fun clearForgetsEverything() {
        val pace = AutospacePace()
        val end = swipe(pace, words = 6, letters = 4, drawMs = 300, gapMs = 300)
        pace.onWord(0, 900, 4, listOf(0L, 300L, 600L, 900L))
        pace.clear()
        assertEquals(1f, pace.swipeFactor(end), 0f)
        assertEquals(1f, pace.tapFactor(1_000L), 0f)
    }

    private companion object {
        val TypingSpeedPause = TypingSpeed.IDLE_BREAK_MS + 1
    }
}
