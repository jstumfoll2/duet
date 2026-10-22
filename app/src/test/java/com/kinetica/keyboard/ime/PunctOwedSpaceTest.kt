package com.kinetica.keyboard.ime

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A sentence mark that eats a word's automatic space owes it to the next word: batch 13 had
 * `easier` + autospace + `.` + a swiped `this` read `easier.This`, because the space was taken
 * back and nothing put one after the period.
 */
class PunctOwedSpaceTest {

    @Test
    fun sentenceAndClauseMarksAfterAWordOweTheSpace() {
        for (mark in listOf(".", ",", "!", "?", ";", ":", "…")) {
            assertTrue(mark, owesSpaceAfter(mark, "er$mark"))
        }
        assertTrue(owesSpaceAfter(".", "42."))
    }

    @Test
    fun bracketsQuotesAndOtherMarksOweNothing() {
        // Closers are left to what the user types next; a hyphen or slash added to the
        // hugging list must never split `e-mail` or `a/b`.
        for (mark in listOf(")", "]", "\"", "-", "/", "'", "@")) {
            assertFalse(mark, owesSpaceAfter(mark, "er$mark"))
        }
        assertFalse(owesSpaceAfter("..", "er.."))
    }

    @Test
    fun oneLetterBeforeTheMarkOwesNothing() {
        // A tapped `a` earns its autospace; the period after it is more often `a.m.` than a
        // sentence ending in a lone letter.
        assertFalse(owesSpaceAfter(".", " a."))
        assertFalse(owesSpaceAfter(".", "a."))
    }

    @Test
    fun aRunOfMarksPassesItOn() {
        // `Wait...` and `really?!`: the caller only asks when the mark before was owed one.
        assertTrue(owesSpaceAfter(".", "t.."))
        assertTrue(owesSpaceAfter("!", "y?!"))
    }

    @Test
    fun nothingBeforeTheMarkOwesNothing() {
        assertFalse(owesSpaceAfter(".", "."))
        assertFalse(owesSpaceAfter(".", ""))
        assertFalse(owesSpaceAfter(".", "ab"))
    }
}
