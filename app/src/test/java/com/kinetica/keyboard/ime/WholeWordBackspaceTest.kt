package com.kinetica.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What one backspace tap takes under whole-word backspace: the swiped word the keyboard wrote
 * and the spaces after it, or nothing special (-1) once anything else is in the way.
 */
class WholeWordBackspaceTest {

    private val max = 8

    // "i want " with `want` swiped at offset 2.
    private val text = "i want "

    @Test
    fun theWordGoesWithItsAutospace() {
        assertEquals(5, wholeWordBackspaceSpan(text, "want", cursor = 7, armedStart = 2, maxTrailing = max))
    }

    @Test
    fun theWordGoesWithoutASpaceToo() {
        assertEquals(4, wholeWordBackspaceSpan("i want", "want", cursor = 6, armedStart = 2, maxTrailing = max))
    }

    @Test
    fun disarmedDeletesALetter() {
        assertEquals(-1, wholeWordBackspaceSpan(text, "want", cursor = 7, armedStart = -1, maxTrailing = max))
    }

    @Test
    fun punctuationTypedAfterTheWordIsTheUsers() {
        assertEquals(-1, wholeWordBackspaceSpan("i want.", "want", cursor = 7, armedStart = 2, maxTrailing = max))
        assertEquals(-1, wholeWordBackspaceSpan("i want, ", "want", cursor = 8, armedStart = 2, maxTrailing = max))
    }

    @Test
    fun theSameLettersElsewhereAreNotTheWord() {
        // `want` written at 2, the cursor now after a later `want` the user typed.
        assertEquals(-1, wholeWordBackspaceSpan("i want to want ", "want", cursor = 15, armedStart = 2, maxTrailing = max))
    }

    @Test
    fun aWordAlreadyEatenIntoIsLeftAlone() {
        assertEquals(-1, wholeWordBackspaceSpan("i wan", "want", cursor = 5, armedStart = 2, maxTrailing = max))
    }

    @Test
    fun aCorrectionPickKeepsTheArmBecauseTheStartHolds() {
        // `want` picked over to `wanted`: same start, longer word.
        assertEquals(7, wholeWordBackspaceSpan("i wanted ", "wanted", cursor = 9, armedStart = 2, maxTrailing = max))
    }
}
