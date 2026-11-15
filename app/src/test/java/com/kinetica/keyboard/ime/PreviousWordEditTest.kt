package com.kinetica.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The text edit of the previous-word fix lands only on the two words it was decided for. */
class PreviousWordEditTest {

    @Test
    fun thePreviousWordIsRewrittenInItsOwnCase() {
        val e = previousWordEdit("I went Tot the", "tot", "the", "to")!!
        assertEquals("Tot the", e.shown)
        assertEquals("To the", e.text)
        assertEquals("Tot the".length, e.span)
    }

    @Test
    fun theSpaceAfterTheWordStaysWhereItIs() {
        val e = previousWordEdit("went tot the ", "tot", "the", "to")!!
        assertEquals("tot the ", e.shown)
        assertEquals("to the ", e.text)
    }

    @Test
    fun textThatMovedOnIsLeftAlone() {
        // A letter of the next word is already down.
        assertNull(previousWordEdit("went tot the s", "tot", "the", "to"))
        // The previous word is not the one the fix was decided for.
        assertNull(previousWordEdit("went tat the", "tot", "the", "to"))
        // Punctuation between the two words.
        assertNull(previousWordEdit("went tot, the", "tot", "the", "to"))
        // Two spaces after the word.
        assertNull(previousWordEdit("went tot the  ", "tot", "the", "to"))
        // Nothing to change.
        assertNull(previousWordEdit("went to the", "to", "the", "to"))
    }

    @Test
    fun theFirstWordOfTheTextCanBeRevised() {
        val e = previousWordEdit("Tot the", "tot", "the", "to")!!
        assertEquals("To the", e.text)
    }
}
