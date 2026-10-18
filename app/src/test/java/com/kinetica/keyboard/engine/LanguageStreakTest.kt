package com.kinetica.keyboard.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LanguageStreakTest {

    @Test
    fun threeForeignWordsInARowSuggestThatLanguageOnce() {
        val s = LanguageStreak(3)
        assertNull(s.onCommit("en", activeHolds = false))
        assertNull(s.onCommit("en", activeHolds = false))
        assertEquals("en", s.onCommit("en", activeHolds = false))
        // Not again on every later word of the same run.
        assertNull(s.onCommit("en", activeHolds = false))
    }

    @Test
    fun anActiveWordBreaksTheRun() {
        val s = LanguageStreak(3)
        s.onCommit("en", activeHolds = false)
        s.onCommit("en", activeHolds = false)
        assertNull(s.onCommit(null, activeHolds = true))
        assertNull(s.onCommit("en", activeHolds = false))
        assertNull(s.onCommit("en", activeHolds = false))
        assertEquals("en", s.onCommit("en", activeHolds = false))
    }

    @Test
    fun unknownWordsNeitherCountNorBreak() {
        val s = LanguageStreak(3)
        s.onCommit("en", activeHolds = false)
        assertNull(s.onCommit(null, activeHolds = false))
        s.onCommit("en", activeHolds = false)
        assertEquals("en", s.onCommit("en", activeHolds = false))
    }

    @Test
    fun aDifferentLanguageStartsItsOwnRun() {
        val s = LanguageStreak(2)
        s.onCommit("en", activeHolds = false)
        assertNull(s.onCommit("it", activeHolds = false))
        assertEquals("it", s.onCommit("it", activeHolds = false))
    }
}
