package com.kinetica.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The space-bold switch (#115): the bar's bold names the word a space writes, and the tapped
 * letters ride second so they are on the first page.
 */
class SpaceBoldTest {

    private fun space(
        tentative: String = "teh",
        hasSwipe: Boolean = false,
        mayCorrect: Boolean = true,
        correction: String? = null,
    ): String = spaceCommitWord(tentative, hasSwipe, mayCorrect) { correction }

    @Test
    fun tappedLettersAreWrittenAsTypedUnlessACorrectionFires() {
        assertEquals("teh", space())
        assertEquals("the", space(correction = "the"))
    }

    @Test
    fun aSwipeWritesTheWordOnScreen() {
        assertEquals("then", space(tentative = "then", hasSwipe = true, correction = "x"))
    }

    @Test
    fun lettersTheCommitKeepsAreNeverCorrected() {
        assertEquals("teh", space(mayCorrect = false, correction = "the"))
    }

    @Test
    fun boldFindsTheWordCaseAsideOrNothing() {
        val bar = listOf("the", "teh", "then")
        assertEquals(1, boldIndex(bar, "teh"))
        assertEquals(0, boldIndex(bar, "The"))
        assertEquals(-1, boldIndex(bar, "tea"))
        assertEquals(-1, boldIndex(bar, null))
        assertEquals(-1, boldIndex(bar, ""))
    }

    @Test
    fun theLiteralRidesSecondWithTheSwitch() {
        assertEquals(listOf("the", "teh", "then", "ten"), suggestionZoneWords(listOf("the", "then", "ten"), "teh", literalSecond = true))
        assertEquals(listOf("xqz"), suggestionZoneWords(emptyList(), "xqz", literalSecond = true))
        // Already first or second: left where the decode put it.
        assertEquals(listOf("teh", "the"), suggestionZoneWords(listOf("teh", "the"), "teh", literalSecond = true))
        assertEquals(listOf("the", "teh", "ten"), suggestionZoneWords(listOf("the", "teh", "ten"), "teh", literalSecond = true))
        // Further down: moved up, not repeated.
        assertEquals(
            listOf("the", "teh", "then", "ten", "tea"),
            suggestionZoneWords(listOf("the", "then", "ten", "tea", "teh"), "teh", literalSecond = true),
        )
        assertEquals(listOf("the", "then"), suggestionZoneWords(listOf("the", "then"), "", literalSecond = true))
        // Without the switch: last, as before.
        assertEquals(listOf("the", "then", "teh"), suggestionZoneWords(listOf("the", "then"), "teh"))
    }
}
