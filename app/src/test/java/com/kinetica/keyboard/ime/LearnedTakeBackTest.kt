package com.kinetica.keyboard.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A take-back undoes what the last commit taught, in the list it went to, once (#184). */
class LearnedTakeBackTest {

    @Test
    fun theWordIsTakenBackFromTheListItWentTo() {
        val t = LearnedTakeBack()
        t.learnedWord("Taco", "es")
        assertEquals("es", t.languageOf("taco"))
        assertEquals("es", t.languageOf("TACO"))
        assertNull("another word derives its own", t.languageOf("tacos"))
    }

    @Test
    fun thePairIsHandedOutOnce() {
        val t = LearnedTakeBack()
        t.learnedPair("good", "morning", "en")
        assertTrue(t.pairEndsWith("morning"))
        assertFalse(t.pairEndsWith("night"))
        assertEquals(("good" to "morning") to "en", t.takePair())
        assertNull("a second retype takes nothing back", t.takePair())
        assertFalse(t.pairEndsWith("morning"))
    }

    @Test
    fun nothingLearnedNothingToTake() {
        val t = LearnedTakeBack()
        assertNull(t.commit)
        assertNull(t.takePair())
        assertNull(t.languageOf("a"))
    }
}
